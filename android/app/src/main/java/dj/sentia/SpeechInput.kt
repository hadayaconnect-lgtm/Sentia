package dj.sentia

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.abs

/**
 * Enregistre la voix jusqu'au silence et renvoie un fichier WAV (16 kHz, mono). Il n'est envoyé au serveur que pour être
 * transcrit (Whisper), puis supprimé.
 *
 * Capture par AudioRecord (et non MediaRecorder) : on lit nous-mêmes les échantillons, donc
 *  - le niveau sonore est toujours disponible (MediaRecorder.maxAmplitude vaut parfois 0 selon le téléphone),
 *  - on sait distinguer « micro muet / bloqué par le système » (échantillons tous à zéro) de « personne n'a parlé »,
 *  - aucun mode « communication » qui baisserait le volume de la voix de SENTIA.
 *
 * Deux modes :
 *  - écoute normale : on attend que la personne parle ;
 *  - écoute pendant que SENTIA parle (`gate` vrai) : annulation d'écho si le téléphone la propose ; seule une voix FORTE et
 *    soutenue compte. Dès qu'elle est détectée, `onSpeechStart` est appelé (pour couper la voix de SENTIA).
 */
class SpeechInput(private val context: Context) {
    @Volatile private var cancelled = false

    /** Diagnostic : niveau maximal de la dernière écoute (0 à 32767) et raison d'un échec (micro refusé, occupé, muet…). */
    @Volatile var lastPeak: Int = 0
        private set
    @Volatile var startError: String? = null
        private set

    /**
     * @param gate renvoie true tant que SENTIA parle (le délai d'attente de la personne ne court pas, seuil plus haut)
     * @param onSpeechStart appelé une fois, dès que la personne prend la parole pendant que SENTIA parlait
     * @param noSpeechTimeoutMs attente maximale d'une première parole une fois que SENTIA a fini de parler
     * @return le fichier, ou null s'il n'y a pas eu de parole (ou si l'écoute a été annulée).
     */
    suspend fun record(
        gate: (() -> Boolean)? = null,
        onSpeechStart: (() -> Unit)? = null,
        noSpeechTimeoutMs: Long = NO_SPEECH_TIMEOUT_MS,
    ): File? {
        cancelled = false
        lastPeak = 0
        startError = null
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            startError = "permission micro refusée"
            return null
        }
        return withContext(Dispatchers.IO) { capture(gate, onSpeechStart, noSpeechTimeoutMs) }
    }

    @Suppress("MissingPermission")
    private suspend fun capture(gate: (() -> Boolean)?, onSpeechStart: (() -> Unit)?, noSpeechTimeoutMs: Long): File? {
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) { startError = "micro non pris en charge"; return null }
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf * 2, CHUNK * 4))
        } catch (e: Exception) {
            startError = e.javaClass.simpleName + ": " + (e.message ?: ""); return null
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            startError = "micro indisponible (utilisé par une autre application ?)"
            rec.release(); return null
        }
        var aec: AcousticEchoCanceler? = null
        var ns: NoiseSuppressor? = null
        try {
            if (AcousticEchoCanceler.isAvailable()) aec = AcousticEchoCanceler.create(rec.audioSessionId)?.also { it.enabled = true }
            if (NoiseSuppressor.isAvailable()) ns = NoiseSuppressor.create(rec.audioSessionId)?.also { it.enabled = true }
        } catch (_: Exception) {}

        val pcm = ByteArrayOutputStream()
        val preroll = ArrayDeque<ByteArray>() // 0,5 s avant la parole, pour ne pas couper le début de la phrase
        val chunk = ShortArray(CHUNK)
        var heardSpeech = false
        var silentMs = 0L
        var speechMs = 0L
        var idleMs = 0L
        var elapsed = 0L
        var hits = 0
        var floor = Int.MAX_VALUE
        var peak = 0
        var readError = false
        try {
            try { rec.startRecording() } catch (e: Exception) { startError = "démarrage du micro : " + (e.message ?: e.javaClass.simpleName); return null }
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) { startError = "le micro n'a pas démarré (occupé ?)"; return null }

            while (!cancelled) {
                currentCoroutineContext().ensureActive()
                val n = rec.read(chunk, 0, CHUNK) // bloque ~100 ms
                if (n < 0) { readError = true; break }
                if (n == 0) continue
                var amp = 0
                for (i in 0 until n) { val a = abs(chunk[i].toInt()); if (a > amp) amp = a }
                if (amp > peak) peak = amp
                val bytes = ByteArray(n * 2)
                for (i in 0 until n) { bytes[i * 2] = (chunk[i].toInt() and 0xFF).toByte(); bytes[i * 2 + 1] = (chunk[i].toInt() shr 8).toByte() }
                val stepMs = n * 1000L / RATE
                elapsed += stepMs
                val sentiaSpeaking = gate?.invoke() == true
                if (!heardSpeech && amp < floor) floor = amp
                if (elapsed <= (if (gate != null) BARGE_SETTLE_MS else SETTLE_MS)) continue // vibration de réveil, clic ; et début de la voix de SENTIA (écho)

                val noise = if (floor == Int.MAX_VALUE) 0 else floor
                val limit = if (sentiaSpeaking) BARGE_IN_LIMIT else minOf(maxOf(MIN_SPEECH_AMPLITUDE, noise * 3), MAX_SPEECH_LIMIT)
                if (amp > limit) hits++ else hits = 0
                val loud = hits >= (if (sentiaSpeaking) BARGE_IN_HITS else 1)
                if (loud) {
                    if (!heardSpeech) {
                        heardSpeech = true
                        preroll.takeLast(PREROLL_CHUNKS).forEach { pcm.write(it) }
                        preroll.clear()
                        if (sentiaSpeaking) withContext(Dispatchers.Main) { onSpeechStart?.invoke() }
                    }
                    silentMs = 0
                } else {
                    silentMs += stepMs
                }
                if (heardSpeech) {
                    pcm.write(bytes)
                    speechMs += stepMs
                    if (silentMs >= END_SILENCE_MS || speechMs >= MAX_MS) break
                } else {
                    preroll.addLast(bytes)
                    // Écoute normale : on garde 6 s récentes (au cas où la voix est faible mais bien là) ; sinon 0,5 s.
                    while (preroll.size > (if (gate == null) FAINT_KEEP_CHUNKS else PREROLL_CHUNKS)) preroll.removeFirst()
                    if (!sentiaSpeaking) {
                        idleMs += stepMs
                        if (idleMs >= noSpeechTimeoutMs) break
                    }
                }
            }
            // Écoute normale : niveau faible mais non nul → on laisse quand même le serveur essayer de transcrire.
            // Écoute pendant que SENTIA parle : seulement une vraie voix détectée (sinon on transcrirait sa propre voix).
            val worthSending = heardSpeech || (gate == null && peak >= FAINT_AMPLITUDE)
            if (!heardSpeech && worthSending) preroll.forEach { pcm.write(it) }
            lastPeak = peak
            if (readError) startError = "lecture du micro impossible"
            else if (peak == 0 && elapsed >= 1000) startError = "micro muet : aucun son reçu (micro bloqué par le système ?)"
            if (!worthSending || cancelled || pcm.size() == 0) return null
            return writeWav(pcm.toByteArray())
        } finally {
            lastPeak = peak
            try { rec.stop() } catch (_: Exception) {}
            try { aec?.release() } catch (_: Exception) {}
            try { ns?.release() } catch (_: Exception) {}
            try { rec.release() } catch (_: Exception) {}
        }
    }

    fun cancel() { cancelled = true }

    private fun writeWav(pcm: ByteArray): File {
        val out = File(context.cacheDir, "voice.wav")
        if (out.exists()) out.delete()
        RandomAccessFile(out, "rw").use { f ->
            val byteRate = RATE * 2
            fun le32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
            fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
            f.write("RIFF".toByteArray()); f.write(le32(36 + pcm.size)); f.write("WAVE".toByteArray())
            f.write("fmt ".toByteArray()); f.write(le32(16)); f.write(le16(1)); f.write(le16(1))
            f.write(le32(RATE)); f.write(le32(byteRate)); f.write(le16(2)); f.write(le16(16))
            f.write("data".toByteArray()); f.write(le32(pcm.size)); f.write(pcm)
        }
        return out
    }

    companion object {
        private const val RATE = 16000
        private const val CHUNK = 1600                // 100 ms
        private const val PREROLL_CHUNKS = 5
        private const val FAINT_KEEP_CHUNKS = 60
        private const val SETTLE_MS = 300L
        private const val BARGE_SETTLE_MS = 900L
        private const val MIN_SPEECH_AMPLITUDE = 550
        private const val MAX_SPEECH_LIMIT = 4000
        private const val FAINT_AMPLITUDE = 250
        /** Voix à couvrir quand SENTIA parle : plus haut que la fuite du haut-parleur, pour ne pas s'interrompre elle-même. */
        private const val BARGE_IN_LIMIT = 7000
        private const val BARGE_IN_HITS = 2
        private const val END_SILENCE_MS = 1500L
        const val NO_SPEECH_TIMEOUT_MS = 8000L
        private const val MAX_MS = 30000L
    }
}
