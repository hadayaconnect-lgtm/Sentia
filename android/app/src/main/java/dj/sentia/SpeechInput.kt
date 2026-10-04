package dj.sentia

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import kotlinx.coroutines.delay
import java.io.File

/**
 * Enregistre la voix jusqu'au silence (détection simple par niveau sonore, locale) et renvoie un fichier m4a.
 * Le fichier n'est envoyé au serveur que pour être transcrit, puis supprimé.
 */
class SpeechInput(private val context: Context) {
    private var recorder: MediaRecorder? = null
    @Volatile private var cancelled = false

    /** @return le fichier, ou null s'il n'y a pas eu de parole (ou si l'écoute a été annulée). */
    @Suppress("DEPRECATION")
    suspend fun record(): File? {
        cancelled = false
        val out = File(context.cacheDir, "voice.m4a")
        if (out.exists()) out.delete()
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
        recorder = r
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(16000)
            r.setAudioEncodingBitRate(48000)
            r.setAudioChannels(1)
            r.setOutputFile(out.absolutePath)
            r.prepare()
            r.start()
        } catch (e: Exception) {
            cleanup(r)
            return null
        }

        var heardSpeech = false
        var silentMs = 0L
        var elapsed = 0L
        var floor = 0
        var floorSamples = 0
        val first = r.maxAmplitude // premier appel : renvoie 0 (ignoré)
        if (first < 0) return null
        try {
            while (!cancelled) {
                delay(STEP_MS)
                elapsed += STEP_MS
                val amp = try { r.maxAmplitude } catch (e: Exception) { 0 }
                if (elapsed <= CALIBRATION_MS) {
                    floor += amp; floorSamples++
                    continue
                }
                val noise = if (floorSamples > 0) floor / floorSamples else 0
                val limit = maxOf(MIN_SPEECH_AMPLITUDE, noise * 3)
                if (amp > limit) { heardSpeech = true; silentMs = 0 } else silentMs += STEP_MS
                if (heardSpeech && silentMs >= END_SILENCE_MS) break
                if (!heardSpeech && elapsed >= NO_SPEECH_TIMEOUT_MS) break
                if (elapsed >= MAX_MS) break
            }
        } finally {
            cleanup(r)
        }
        return if (heardSpeech && !cancelled && out.exists() && out.length() > 0) out else null
    }

    fun cancel() { cancelled = true }

    private fun cleanup(r: MediaRecorder) {
        try { r.stop() } catch (_: Exception) {}
        try { r.release() } catch (_: Exception) {}
        if (recorder === r) recorder = null
    }

    companion object {
        private const val STEP_MS = 100L
        private const val CALIBRATION_MS = 400L
        private const val MIN_SPEECH_AMPLITUDE = 1800
        private const val END_SILENCE_MS = 1500L
        private const val NO_SPEECH_TIMEOUT_MS = 8000L
        private const val MAX_MS = 30000L
    }
}
