package dj.sentia

import android.content.Context
import android.util.Base64
import dj.sentia.core.LiveEnd
import dj.sentia.core.LiveEvent
import dj.sentia.core.IdleWatch
import dj.sentia.core.LivePhotoGate
import dj.sentia.core.LiveProtocol
import dj.sentia.core.LiveReconnect
import dj.sentia.core.SpokenText
import dj.sentia.core.VoiceCommand
import dj.sentia.core.VoiceCommands
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * Conversation vocale en direct avec Gemini Live : le micro part en continu, la voix revient en continu, on peut couper la parole.
 * La photo reste ponctuelle : l'IA appelle l'outil « capture_camera » (UNE photo par demande), jamais de vidéo continue.
 * La clé secrète n'est jamais ici : le téléphone reçoit un jeton temporaire du serveur SENTIA.
 */
class LiveSession(
    private val context: Context,
    private val client: AgentClient,
    private val ui: Ui,
) {
    enum class State { CONNECTING, LISTENING, SPEAKING, LOOKING }
    enum class Notice { RECONNECTING, LOST, IDLE, MIC_ERROR }
    enum class End { USER, IDLE, LOST }

    interface Ui {
        fun onState(state: State)
        /** Dernier texte dit par SENTIA (affichage). */
        fun onCaption(text: String)
        /** UNE photo vérifiée (base64). Lève [AgentException] (IMAGE) si elle reste inutilisable. */
        suspend fun capturePhoto(): String
        suspend fun notice(n: Notice)
    }

    private sealed class Ws(val gen: Int) {
        class Opened(gen: Int) : Ws(gen)
        class Message(gen: Int, val text: String) : Ws(gen)
        class Closed(gen: Int, val detail: String) : Ws(gen)
        class Stop(val reason: End) : Ws(-1)
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // flux continu : pas de limite de lecture
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val events = Channel<Ws>(Channel.UNLIMITED)
    private val route = LiveRoute(context)
    private val player = LivePlayer()
    private val gate = LivePhotoGate()
    private val spoken = SpokenText()
    private val idle = IdleWatch(IDLE_LIMIT_MS)
    private val toolJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()

    @Volatile private var mic: LiveMic? = null
    @Volatile private var ws: WebSocket? = null
    private var gen = 0
    private var info: LiveToken? = null
    private var resumeHandle: String? = null

    @Volatile private var ready = false
    /** Vrai de l'ouverture à la fermeture de la session. */
    @Volatile var active = false
        private set
    private var state = State.CONNECTING
    private var caption = StringBuilder()
    private var dropAudio = false
    /** Vrai tant que l'IA est en train de répondre (audio ou texte reçu, fin de tour pas encore reçue). */
    private var modelTurnOpen = false
    /** Arrêt demandé (par la personne ou par la veille) pendant une connexion : il doit l'emporter sur tout échec. */
    private var pendingStop: End? = null
    private var goodbye = false

    // Mesures (affichées dans Réglages → Diagnostic)
    private var startedAt = 0L
    private var connectMs = 0L
    private var photoAskedAt = 0L
    private var photoSentAt = 0L
    private var cameraMs = 0L
    private var firstVoiceMs = -1L

    /** Coupe ce que SENTIA est en train de dire (bouton Stop, nouveau réveil). La conversation continue. */
    fun interruptSpeech() {
        player.clear()
        dropAudio = modelTurnOpen // le reste de la réponse EN COURS est ignoré ; la suivante sera jouée normalement
    }

    fun sendText(text: String) {
        if (!ready) return
        idle.activity(System.currentTimeMillis())
        ws?.send(LiveProtocol.text(text))
    }

    fun end() { events.trySend(Ws.Stop(End.USER)) }

    /**
     * Ouvre la session et la fait vivre jusqu'à sa fin.
     * @throws AgentException si la session n'a PAS pu démarrer (jeton, réseau, modèle, facturation, micro) : l'appelant peut alors
     * se replier sur le mode classique. Après le démarrage, les coupures sont gérées ici (reconnexion) et la fin est renvoyée.
     */
    suspend fun run(opening: String?): End = coroutineScope {
        active = true
        startedAt = System.currentTimeMillis()
        ui.onState(State.CONNECTING)
        try {
            info = fetchToken()
            route.open()
            if (!player.start()) throw AgentException(AgentException.Kind.SERVER, "haut-parleur indisponible")
            val err = connect(info!!, null)
            if (err != null) {
                pendingStop?.let { return@coroutineScope it }
                throw AgentException(AgentException.Kind.SERVER, err)
            }
            connectMs = System.currentTimeMillis() - startedAt
            val m = LiveMic { chunk ->
                // Sans annulation d'écho, on n'envoie pas le micro pendant que SENTIA parle (sinon elle s'écoute elle-même).
                if (ready && !(mic?.hasEchoCancel == false && player.speaking)) ws?.send(LiveProtocol.audio(Base64.encodeToString(chunk, Base64.NO_WRAP)))
            }
            mic = m
            if (!m.start()) throw AgentException(AgentException.Kind.SERVER, "micro indisponible")
            idle.activity(System.currentTimeMillis())
            setState(State.LISTENING)
            if (opening != null) ws?.send(LiveProtocol.text(opening))

            // Surveillance : fin de parole → écoute ; veille après un long silence.
            val ticker = launch {
                while (isActive) {
                    delay(300)
                    if (state == State.SPEAKING && !player.speaking) setState(State.LISTENING)
                    if (idle.expired(System.currentTimeMillis()) && !player.speaking) { events.trySend(Ws.Stop(End.IDLE)); break }
                }
            }
            var result = End.LOST
            try {
                loop@ while (true) {
                    when (val ev = events.receive()) {
                        is Ws.Stop -> { result = ev.reason; break@loop }
                        is Ws.Opened -> {}
                        is Ws.Message -> if (ev.gen == gen) for (e in LiveProtocol.parse(ev.text)) handle(e, this)
                        is Ws.Closed -> if (ev.gen == gen) {
                            if (goodbye) { result = End.USER; break@loop }
                            ready = false
                            if (!reconnect(this)) { result = pendingStop ?: End.LOST; break@loop }
                        }
                    }
                }
            } finally {
                ticker.cancel()
                toolJobs.values.forEach { it.cancel() }
            }
            result
        } finally {
            cleanup()
        }
    }

    // ---- Connexion ---------------------------------------------------------------------------------------------

    private suspend fun fetchToken(): LiveToken {
        val s = context.settings
        val time = OffsetDateTime.now().withNano(0).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        return client.liveToken(s.language, s.profile, time)
    }

    /** Ouvre le WebSocket (essaie chaque adresse du serveur) et envoie la configuration. @return null si prêt, sinon la raison de l'échec. */
    private suspend fun connect(t: LiveToken, handle: String?): String? {
        var last = "aucune adresse"
        for (u in t.urls) {
            val url = ("https:" + u.removePrefix("wss:").removePrefix("ws:")).toHttpUrlOrNull()?.newBuilder()?.addQueryParameter("access_token", t.token)?.build() ?: continue
            val g = ++gen
            ws?.cancel()
            val socket = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { events.trySend(Ws.Opened(g)) }
                override fun onMessage(webSocket: WebSocket, text: String) { events.trySend(Ws.Message(g, text)) }
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) { events.trySend(Ws.Message(g, bytes.utf8())) }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { events.trySend(Ws.Closed(g, "fermé $code $reason".trim())) }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null); events.trySend(Ws.Closed(g, "fermé $code $reason".trim())) }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    events.trySend(Ws.Closed(g, "échec" + (response?.let { " HTTP ${it.code}" } ?: "") + (t.message?.let { " – ${it.take(80)}" } ?: "")))
                }
            })
            ws = socket
            val opened = awaitReady(g, socket, t, handle)
            if (opened == null) return null
            last = opened
            socket.cancel()
            if (pendingStop != null) break
        }
        return last
    }

    /** Attend l'ouverture puis « setupComplete ». @return null si prêt, sinon la raison. */
    private suspend fun awaitReady(g: Int, socket: WebSocket, t: LiveToken, handle: String?): String? {
        var sentSetup = false
        val outcome = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
            while (true) {
                when (val ev = events.receive()) {
                    is Ws.Stop -> { pendingStop = ev.reason; return@withTimeoutOrNull "arrêt" }
                    is Ws.Opened -> if (ev.gen == g && !sentSetup) { sentSetup = true; socket.send(LiveProtocol.setup(t.model, t.instruction, t.toolsJson, handle)) }
                    is Ws.Closed -> if (ev.gen == g) return@withTimeoutOrNull ev.detail
                    is Ws.Message -> if (ev.gen == g) {
                        val parsed = LiveProtocol.parse(ev.text)
                        if (parsed.any { it is LiveEvent.SetupComplete }) { ready = true; return@withTimeoutOrNull null }
                        parsed.filterIsInstance<LiveEvent.Failure>().firstOrNull()?.let { return@withTimeoutOrNull it.message }
                    }
                }
            }
            @Suppress("UNREACHABLE_CODE") "inattendu"
        }
        return if (outcome == null && ready) null else outcome ?: "délai dépassé"
    }

    private suspend fun reconnect(scope: kotlinx.coroutines.CoroutineScope): Boolean {
        // L'annonce est dite pendant que la reconnexion se fait ; ensuite on rétablit l'affichage (la voix de l'annonce l'éteint).
        scope.launch { ui.notice(Notice.RECONNECTING); ui.onState(state) }
        for (attempt in 1..LiveReconnect.MAX_ATTEMPTS) {
            if (pendingStop != null) return false
            delay(LiveReconnect.delayMs(attempt))
            if (attempt >= 2) info = try { fetchToken() } catch (_: AgentException) { continue }
            if (connect(info ?: continue, resumeHandle) == null) { state = State.LISTENING; ui.onState(state); return true }
        }
        return false
    }

    private fun cleanup() {
        ready = false
        active = false
        try { mic?.stop() } catch (_: Exception) {}
        try { player.stop() } catch (_: Exception) {}
        try { ws?.close(1000, null) } catch (_: Exception) {}
        ws = null
        route.close()
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }

    // ---- Événements --------------------------------------------------------------------------------------------

    private fun setState(s: State) { if (state != s) { state = s; ui.onState(s) } }

    private suspend fun handle(e: LiveEvent, scope: kotlinx.coroutines.CoroutineScope) {
        val now = System.currentTimeMillis()
        when (e) {
            is LiveEvent.Audio -> {
                idle.activity(now)
                modelTurnOpen = true
                if (dropAudio) return
                if (photoSentAt != 0L && firstVoiceMs < 0) { firstVoiceMs = now - photoSentAt; saveStats() }
                setState(State.SPEAKING)
                player.write(Base64.decode(e.b64, Base64.DEFAULT))
            }
            is LiveEvent.Interrupted -> { player.clear(); dropAudio = false; modelTurnOpen = false; gate.turnEnded(); caption.setLength(0); setState(State.LISTENING) }
            is LiveEvent.TurnComplete -> { dropAudio = false; modelTurnOpen = false; gate.turnEnded(); spoken.reset(); caption.setLength(0); photoSentAt = 0L }
            is LiveEvent.InputText -> {
                idle.activity(now)
                val said = spoken.add(e.text)
                if (VoiceCommands.parse(said) == VoiceCommand.STOP) interruptSpeech()
                if (LiveEnd.isGoodbye(said)) { goodbye = true; scope.launch { delay(GOODBYE_GRACE_MS); events.trySend(Ws.Stop(End.USER)) } }
            }
            is LiveEvent.OutputText -> { idle.activity(now); modelTurnOpen = true; caption.append(e.text); ui.onCaption(caption.toString().trim()) }
            is LiveEvent.ToolCall -> for (c in e.calls) {
                val j = scope.launch { runTool(c) }
                toolJobs[c.id] = j
                j.invokeOnCompletion { toolJobs.remove(c.id) }
            }
            is LiveEvent.ToolCancel -> e.ids.forEach { toolJobs.remove(it)?.cancel() }
            is LiveEvent.Resumption -> if (e.resumable && e.handle.isNotEmpty()) resumeHandle = e.handle
            is LiveEvent.GoAway, is LiveEvent.SetupComplete, is LiveEvent.Failure -> {}
        }
    }

    private suspend fun runTool(c: LiveEvent.Call) {
        idle.activity(System.currentTimeMillis())
        when (c.name) {
            "capture_camera" -> {
                if (!gate.tryTake()) {
                    ws?.send(LiveProtocol.toolResponse(c.id, c.name, "Une photo a déjà été prise pour cette demande. Utilise-la. Si elle est inexploitable, demande à la personne de bouger le téléphone puis de redemander.", true))
                    return
                }
                setState(State.LOOKING)
                photoAskedAt = System.currentTimeMillis()
                val b64 = try {
                    ui.capturePhoto()
                } catch (e: AgentException) {
                    ws?.send(LiveProtocol.toolResponse(c.id, c.name, "Je n'arrive pas à obtenir une image claire. Dis exactement à la personne : « Je n'arrive pas à obtenir une image claire. Veuillez orienter le téléphone et réessayer. »", true))
                    setState(State.LISTENING)
                    return
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ws?.send(LiveProtocol.toolResponse(c.id, c.name, "La caméra est indisponible.", true))
                    setState(State.LISTENING)
                    return
                }
                photoSentAt = System.currentTimeMillis()
                cameraMs = photoSentAt - photoAskedAt
                firstVoiceMs = -1L
                ws?.send(LiveProtocol.photo(b64))
                ws?.send(LiveProtocol.toolResponse(c.id, c.name, "Photo reçue. Décris uniquement ce qui y est réellement visible, brièvement."))
            }
            "get_time" -> {
                val t = OffsetDateTime.now().withNano(0).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                ws?.send(LiveProtocol.toolResponse(c.id, c.name, t))
            }
            else -> ws?.send(LiveProtocol.toolResponse(c.id, c.name, "Outil indisponible.", true))
        }
    }

    private fun saveStats() {
        fun s(ms: Long) = String.format(java.util.Locale.FRANCE, "%.1f s", ms / 1000.0)
        context.settings.diagPerf = "Conversation en direct\n" +
            "Connexion (jeton + WebSocket + configuration) : ${s(connectMs)}\n" +
            "Demande de photo → photo envoyée : ${s(cameraMs)}\n" +
            "Photo envoyée → début de la voix : ${s(firstVoiceMs)}\n" +
            "Annulation d'écho : " + (if (mic?.hasEchoCancel == true) "oui" else "non (micro coupé pendant que SENTIA parle)")
    }

    companion object {
        /** Sans voix ni parole pendant ce temps, la session se met en veille (batterie, crédit). */
        const val IDLE_LIMIT_MS = 3 * 60_000L
        private const val CONNECT_TIMEOUT_MS = 12_000L
        /** Laisse finir la phrase d'au revoir avant de fermer. */
        private const val GOODBYE_GRACE_MS = 2_500L
    }
}
