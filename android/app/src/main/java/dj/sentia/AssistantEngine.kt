package dj.sentia

import android.content.Context
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * Un tour complet : demande de l'utilisateur → (outils éventuels, au plus 5 tours) → réponse finale.
 *
 * Analyse caméra, trois délais distincts (voir les constantes) :
 *  - la prise de photo (caméra + capture + compression) : [CAPTURE_MAX_MS] ;
 *  - toute l'analyse, de la photo à la réponse de l'IA : [ANALYSIS_HARD_MS] (au-delà : arrêt net, jamais de boucle sans fin) ;
 *  - à [HOLD_AT_MS] sans réponse, l'application dit « Je regarde encore » (jamais de silence long pour une personne aveugle).
 * Ce sont des MAXIMUMS : dès que la réponse est prête, on la dit. Un échec RÉSEAU (pas un délai) est retenté une fois.
 */
class AssistantEngine(private val context: Context, private val client: AgentClient, private val tools: ToolExecutor) {
    val conversation = Conversation()

    /** Début de l'analyse en cours (0 = pas de photo dans ce tour). */
    private var analysisStart = 0L

    /** Identifiant de la demande en cours : toute réponse portant un autre identifiant est ignorée. */
    @Volatile private var currentId = 0

    /** Question libre. Si l'IA demande la caméra, le chronomètre de 10 s démarre à ce moment-là. */
    suspend fun ask(text: String, onPreface: (String) -> Unit = {}): String {
        Perf.beginIfStale()
        analysisStart = 0L
        currentId++
        conversation.addUserText(text)
        return run(onPreface)
    }

    /**
     * Demande de regarder (secousse, « Regarde encore », « Lis ça », « Quel est ce billet »…) : l'application prend la photo
     * elle-même, tout de suite, et l'envoie avec la demande en un seul aller-retour (plus rapide qu'un passage par l'outil).
     */
    suspend fun askLook(prompt: String, onPreface: (String) -> Unit = {}): String {
        Perf.beginIfStale()
        if (!tools.ensureCamera()) return context.getString(R.string.tool_denied)
        currentId++
        // Nouvelle analyse : on oublie les anciennes photos, seule la photo de CETTE analyse est envoyée à l'IA.
        conversation.dropImages()
        analysisStart = System.currentTimeMillis()
        Perf.mark("cam_start")
        val photo = withTimeoutOrNull(CAPTURE_MAX_MS) { tools.takePhoto() }
            ?: throw AgentException(AgentException.Kind.TIMEOUT, "capture")
        Perf.mark("cam_done")
        conversation.addUserBlocks(JSONArray().put(Conversation.image(photo)).put(Conversation.text(prompt)))
        return run(onPreface)
    }

    private fun remainingMs(): Long =
        if (analysisStart == 0L) 0L else ANALYSIS_HARD_MS - (System.currentTimeMillis() - analysisStart)

    private suspend fun run(onPreface: (String) -> Unit): String {
        val settings = context.settings
        val id = currentId
        try {
            var rounds = 0
            var retried = false
            while (true) {
                conversation.compact()
                val localTime = OffsetDateTime.now().withNano(0).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                // Pendant une analyse caméra : l'appel à l'IA reçoit le temps restant comme limite stricte.
                val deadline = if (analysisStart == 0L) 0L else remainingMs().also {
                    if (it < MIN_CALL_MS) throw AgentException(AgentException.Kind.TIMEOUT, "budget")
                }
                val reply = try {
                    client.agent(conversation.messages, settings.language, settings.profile, localTime, deadline)
                } catch (e: AgentException) {
                    // Coupure réseau brève (signal faible, changement d'antenne) : une seule nouvelle tentative, s'il reste du temps.
                    if (e.kind == AgentException.Kind.NETWORK && !retried && (analysisStart == 0L || remainingMs() > RETRY_MIN_LEFT_MS)) {
                        retried = true
                        delay(400)
                        continue
                    }
                    throw e
                }
                // Réponse d'une analyse devenue obsolète (une nouvelle demande a démarré entre-temps) : on l'ignore.
                if (id != currentId) throw kotlinx.coroutines.CancellationException("analyse obsolète")
                when (reply) {
                    is AgentReply.Final -> {
                        Perf.mark("ai_done")
                        conversation.addAssistantText(reply.text.ifBlank { "…" })
                        return reply.text
                    }
                    is AgentReply.ToolCalls -> {
                        if (reply.preface.isNotBlank()) onPreface(reply.preface)
                        conversation.addAssistantBlocks(reply.assistant)
                        val results = JSONArray()
                        for (call in reply.calls) {
                            val out = if (call.name == "capture_camera") captureWithinBudget(call) else tools.run(call.name, call.input)
                            results.put(Conversation.toolResult(call.id, out.content, out.isError))
                        }
                        conversation.addUserBlocks(results)
                        if (++rounds > 5) throw AgentException(AgentException.Kind.SERVER, "too_many_rounds")
                    }
                }
            }
        } catch (e: Exception) {
            repairAfterError()
            throw e
        }
    }

    /** Photo demandée par l'IA en cours de conversation : le chronomètre de 10 s démarre ici (une seule fois par demande). */
    private suspend fun captureWithinBudget(call: AgentReply.Call): ToolOutput {
        if (!tools.ensureCamera()) return ToolOutput.text(context.getString(R.string.tool_denied), true)
        if (analysisStart == 0L) analysisStart = System.currentTimeMillis()
        val left = remainingMs()
        if (left < MIN_CALL_MS) throw AgentException(AgentException.Kind.TIMEOUT, "budget")
        return withTimeoutOrNull(minOf(left, CAPTURE_MAX_MS)) { tools.run(call.name, call.input) }
            ?: throw AgentException(AgentException.Kind.TIMEOUT, "capture")
    }

    /** Après une erreur, on retire le tour inachevé pour ne pas envoyer une conversation incohérente. */
    private fun repairAfterError() {
        val m = conversation.messages
        // Retire tout jusqu'au dernier vrai message utilisateur (sans résultat d'outil) inclus.
        while (m.length() > 0) {
            val last = m.getJSONObject(m.length() - 1)
            m.remove(m.length() - 1)
            if (last.getString("role") == "user") {
                val c = last.getJSONArray("content")
                var plain = true
                for (i in 0 until c.length()) if (c.getJSONObject(i).getString("type") == "tool_result") plain = false
                if (plain) break
            }
        }
    }

    fun reset() = conversation.clear()

    companion object {
        /** Prise de photo : démarrage de la caméra + exposition + capture + compression (estimation : 1,5 à 3 s ; la mesure réelle s'affiche dans Réglages). */
        const val CAPTURE_MAX_MS = 6_000L
        /** Toute l'analyse, de la photo à la réponse : envoi + serveur + IA (estimation : 4 à 12 s selon le réseau ; mesure réelle dans Réglages). */
        const val ANALYSIS_HARD_MS = 30_000L
        /** Sans réponse à ce moment, l'application dit « Je regarde encore » (une seule fois). */
        const val HOLD_AT_MS = 10_000L
        /** Temps minimal restant pour retenter après un échec réseau. */
        private const val RETRY_MIN_LEFT_MS = 6_000L
        /** Sous ce temps restant, inutile de lancer un appel à l'IA : l'analyse s'arrête. */
        private const val MIN_CALL_MS = 1_500L
    }
}
