package dj.sentia

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
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

    init {
        tts = TextToSpeech(app) { status -> ready.complete(status == TextToSpeech.SUCCESS) }
    }

    /** Lit le texte et attend la fin. Annulable avec stop(). Renvoie false si rien n'a pu être lu. */
    suspend fun say(text: String, lang: String): Boolean {
        if (text.isBlank()) return true
        stop()
        val engine = if (ready.await()) tts else null
        if (engine != null) {
            val avail = engine.isLanguageAvailable(Lang.locale(lang))
            if (avail >= TextToSpeech.LANG_AVAILABLE) {
                engine.setLanguage(Lang.locale(lang))
                return speakLocal(engine, text)
            }
        }
        return speakServer(text)
    }

    private suspend fun speakLocal(engine: TextToSpeech, text: String): Boolean = suspendCancellableCoroutine { cont ->
        val id = "u" + (++counter)
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { if (utteranceId == id && cont.isActive) cont.resume(true) }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { if (utteranceId == id && cont.isActive) cont.resume(false) }
            override fun onStop(utteranceId: String?, interrupted: Boolean) { if (utteranceId == id && cont.isActive) cont.resume(true) }
        })
        val r = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        if (r != TextToSpeech.SUCCESS && cont.isActive) cont.resume(false)
        cont.invokeOnCancellation { engine.stop() }
    }

    private suspend fun speakServer(text: String): Boolean {
        val file = try {
            withContext(Dispatchers.IO) { client.speak(text, File(app.cacheDir, "reply.mp3")) }
        } catch (e: Exception) {
            return false
        }
        return suspendCancellableCoroutine { cont ->
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
        tts?.stop()
        player?.let { release(it) }
    }

    fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
    }
}
