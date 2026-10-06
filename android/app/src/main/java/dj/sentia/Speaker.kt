package dj.sentia

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/**
 * Voix : synthèse du téléphone d'abord (gratuite, hors ligne), voix du serveur en repli quand la langue
 * n'est pas installée (c'est probable pour le somali). L'arabe et le somali dépendent des voix installées.
 */
class Speaker(context: Context, private val client: AgentClient) {
    private val app = context.applicationContext
    private var tts: TextToSpeech? = null
    private val ready = CompletableDeferred<Boolean>()
    private var player: MediaPlayer? = null
    @Volatile private var counter = 0

    /** Vrai pendant que SENTIA parle (synthèse du téléphone ou voix du serveur). */
    @Volatile var speaking = false
        private set
    @Volatile private var currentText = ""
    @Volatile private var currentOffset = 0
    @Volatile private var remainder: String? = null
    @Volatile private var sayToken = 0
    @Volatile private var serverCont: CancellableContinuation<Boolean>? = null

    /** Dernière raison d'échec de la voix (pour le diagnostic affiché à l'écran). */
    @Volatile var lastError: String? = null
        private set

    private val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focus: AudioFocusRequest? = null

    /** Volume des médias à zéro : la voix serait « lue » sans qu'on l'entende. On le remonte à un niveau audible. */
    private fun ensureAudible() {
        try {
            if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) {
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, (audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * 0.6).toInt().coerceAtLeast(1), 0)
            }
        } catch (_: Exception) {}
    }

    private fun takeFocus() {
        try {
            val r = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .build()
            focus = r
            audio.requestAudioFocus(r)
        } catch (_: Exception) {}
    }

    private fun dropFocus() {
        try { focus?.let { audio.abandonAudioFocusRequest(it) } } catch (_: Exception) {}
        focus = null
    }

    init {
        tts = TextToSpeech(app) { status ->
            if (status == TextToSpeech.SUCCESS) {
                try {
                    tts?.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                } catch (_: Exception) {}
            } else {
                lastError = "synthèse vocale du téléphone non initialisée (code $status)"
            }
            ready.complete(status == TextToSpeech.SUCCESS)
        }
    }

    /**
     * « SENTIA » en capitales est épelé lettre par lettre par la synthèse vocale : on l'envoie comme un vrai mot.
     * (Le texte affiché garde « SENTIA ».) En arabe, le nom est écrit en lettres arabes pour la même raison.
     */
    private fun pronounceable(text: String, lang: String): String {
        val name = if (lang == "ar") "سنتيا" else "Sentia"
        return NAME.replace(text, name)
    }

    /** Lit le texte et attend la fin. Annulable avec stop(). Renvoie false si rien n'a pu être lu. */
    suspend fun say(text: String, lang: String): Boolean {
        if (text.isBlank()) return true
        val spoken = pronounceable(text, lang)
        stop()
        val token = ++sayToken
        currentText = spoken
        currentOffset = 0
        remainder = null
        speaking = true
        lastError = null
        ensureAudible()
        takeFocus()
        try {
            val engine = if (ready.await()) tts else null
            if (engine != null) {
                val avail = engine.isLanguageAvailable(Lang.locale(lang))
                if (avail >= TextToSpeech.LANG_AVAILABLE) {
                    engine.setLanguage(Lang.locale(lang))
                    if (speakLocal(engine, spoken)) return true
                    if (sayToken != token || !speaking) return true // interrompu par stop() : ce n'est pas une panne
                }
            }
            if (speakServer(spoken, token)) return true
            if (sayToken != token || !speaking) return true
            // Dernier recours : la voix par défaut du téléphone, même si la langue n'est pas exactement la bonne.
            return if (engine != null) speakLocal(engine, spoken) else false
        } finally {
            if (sayToken == token) { speaking = false; dropFocus() }
        }
    }

    private suspend fun speakLocal(engine: TextToSpeech, text: String): Boolean = suspendCancellableCoroutine { cont ->
        val id = "u" + (++counter)
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                if (utteranceId == id) currentOffset = start
            }
            override fun onDone(utteranceId: String?) { if (utteranceId == id && cont.isActive) cont.resume(true) }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { lastError = "erreur de la synthèse vocale"; if (utteranceId == id && cont.isActive) cont.resume(false) }
            override fun onError(utteranceId: String?, errorCode: Int) { lastError = "erreur de la synthèse vocale (code $errorCode)"; if (utteranceId == id && cont.isActive) cont.resume(false) }
            override fun onStop(utteranceId: String?, interrupted: Boolean) { if (utteranceId == id && cont.isActive) cont.resume(true) }
        })
        val r = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        if (r != TextToSpeech.SUCCESS && cont.isActive) cont.resume(false)
        cont.invokeOnCancellation { engine.stop() }
    }

    private suspend fun speakServer(text: String, token: Int): Boolean {
        val file = try {
            withContext(Dispatchers.IO) { client.speak(text, File(app.cacheDir, "reply.mp3")) }
        } catch (e: Exception) {
            return false
        }
        if (sayToken != token || !speaking) return true // interrompu pendant le téléchargement
        return suspendCancellableCoroutine { cont ->
            serverCont = cont
            val mp = MediaPlayer()
            player = mp
            try {
                mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                mp.setDataSource(file.absolutePath)
                mp.setOnCompletionListener { release(mp); if (cont.isActive) cont.resume(true) }
                mp.setOnErrorListener { _, _, _ -> release(mp); if (cont.isActive) cont.resume(false); true }
                mp.setOnPreparedListener { it.start() }
                mp.prepareAsync()
            } catch (e: Exception) {
                release(mp); if (cont.isActive) cont.resume(false)
            }
            cont.invokeOnCancellation { release(mp) }
        }
    }

    private fun release(mp: MediaPlayer) {
        try { mp.release() } catch (_: Exception) {}
        if (player === mp) player = null
    }

    fun stop() {
        speaking = false
        tts?.stop()
        // Voix du serveur : on libère lecteur ET on débloque l'attente (sinon say() ne rendrait jamais la main).
        val c = serverCont
        serverCont = null
        player?.let { release(it) }
        if (c != null && c.isActive) c.resume(true)
    }

    /**
     * Arrête la voix tout de suite (commande « Stop », « Pause » ou la personne qui reprend la parole) et retient
     * ce qu'il restait à dire, depuis le début de la phrase en cours, pour pouvoir dire « Continue ».
     */
    fun interrupt() {
        if (speaking) {
            val t = currentText
            if (t.isNotEmpty()) {
                val off = currentOffset.coerceIn(0, t.length)
                val from = if (off <= 0) 0 else t.lastIndexOfAny(charArrayOf('.', '!', '?', '؟', '\n'), off - 1).let { if (it < 0) 0 else it + 1 }
                remainder = t.substring(from).trim().ifEmpty { null }
            }
        }
        stop()
    }

    /** Ce qu'il restait à dire quand SENTIA a été interrompue (une seule fois), ou null. */
    fun takeRemainder(): String? {
        val r = remainder
        remainder = null
        return r
    }

    fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
    }

    private companion object {
        val NAME = Regex("sentia", RegexOption.IGNORE_CASE)
    }
}
