package dj.sentia

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import kotlinx.coroutines.delay
import java.io.File

/**
 * Enregistre la voix jusqu'au silence (détection simple par niveau sonore, locale) et renvoie un fichier m4a.
 * Le fichier n'est envoyé au serveur que pour être transcrit, puis supprimé.
 *
 * Deux modes :
 *  - écoute normale : on attend que la personne parle ;
 *  - écoute pendant que SENTIA parle (« gate » vrai) : le micro est ouvert en mode communication (annulation d'écho
 *    quand le téléphone la propose) et seule une voix FORTE et soutenue compte. Dès qu'elle est détectée,
 *    `onSpeechStart` est appelé (pour couper la voix de SENTIA) et l'enregistrement continue pour capter la phrase.
 */
class SpeechInput(private val context: Context) {
    private var recorder: MediaRecorder? = null
    @Volatile private var cancelled = false

    /** Diagnostic : niveau sonore maximal de la dernière écoute (0 à 32767) et raison d'un échec de démarrage. */
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
    @Suppress("DEPRECATION")
    suspend fun record(
        gate: (() -> Boolean)? = null,
        onSpeechStart: (() -> Unit)? = null,
        noSpeechTimeoutMs: Long = NO_SPEECH_TIMEOUT_MS,
    ): File? {
        recorder?.let { cleanup(it) } // une écoute précédente encore ouverte (annulée à l'instant) libère le micro
        cancelled = false
        lastPeak = 0
        startError = null
        val out = File(context.cacheDir, "voice.m4a")
        if (out.exists()) out.delete()
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
        recorder = r
        try {
            r.setAudioSource(if (gate != null) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(16000)
            r.setAudioEncodingBitRate(48000)
            r.setAudioChannels(1)
            r.setOutputFile(out.absolutePath)
            r.prepare()
            r.start()
        } catch (e: Exception) {
            startError = e.javaClass.simpleName + ": " + (e.message ?: "")
            cleanup(r)
            return null
        }

        var heardSpeech = false
        var silentMs = 0L      // silence depuis la dernière parole
        var speechMs = 0L      // durée depuis la première parole
        var idleMs = 0L        // attente sans parole, hors périodes où SENTIA parle
        var elapsed = 0L
        var hits = 0           // échantillons forts consécutifs
        var floor = Int.MAX_VALUE
        var peak = 0
        try {
            try { r.maxAmplitude } catch (_: Exception) {} // premier appel : renvoie 0 (ignoré)
            while (!cancelled) {
                delay(STEP_MS)
                elapsed += STEP_MS
                val amp = try { r.maxAmplitude } catch (e: Exception) { 0 }
                if (amp > peak) peak = amp
                val sentiaSpeaking = gate?.invoke() == true
                // Bruit de fond = le plus bas niveau mesuré avant la parole (robuste si la personne parle tout de suite).
                if (!heardSpeech && amp < floor) floor = amp
                // Les premières 300 ms (vibration de réveil, clic du bouton) ne comptent pas.
                if (elapsed <= SETTLE_MS) continue

                val noise = if (floor == Int.MAX_VALUE) 0 else floor
                val limit = if (sentiaSpeaking) BARGE_IN_LIMIT
                else minOf(maxOf(MIN_SPEECH_AMPLITUDE, noise * 3), MAX_SPEECH_LIMIT)
                if (amp > limit) hits++ else hits = 0
                val loud = hits >= (if (sentiaSpeaking) BARGE_IN_HITS else 1)
                if (loud) {
                    if (!heardSpeech) {
                        heardSpeech = true
                        if (sentiaSpeaking) onSpeechStart?.invoke()
                    }
                    silentMs = 0
                } else {
                    silentMs += STEP_MS
                }
                if (heardSpeech) {
                    speechMs += STEP_MS
                    if (silentMs >= END_SILENCE_MS || speechMs >= MAX_MS) break
                } else if (!sentiaSpeaking) {
                    idleMs += STEP_MS
                    if (idleMs >= noSpeechTimeoutMs) break
                }
            }
        } finally {
            lastPeak = peak
            cleanup(r)
        }
        // Écoute normale : si le niveau est faible mais non nul, on laisse quand même le serveur essayer de transcrire.
        // Écoute pendant que SENTIA parle : seulement si une vraie voix a été détectée (sinon on transcrirait sa propre voix).
        val worthSending = heardSpeech || (gate == null && peak >= FAINT_AMPLITUDE)
        return if (worthSending && !cancelled && out.exists() && out.length() > 0) out else null
    }

    fun cancel() { cancelled = true }

    private fun cleanup(r: MediaRecorder) {
        try { r.stop() } catch (_: Exception) {}
        try { r.release() } catch (_: Exception) {}
        if (recorder === r) recorder = null
    }

    companion object {
        private const val STEP_MS = 100L
        private const val SETTLE_MS = 300L
        private const val MIN_SPEECH_AMPLITUDE = 1200
        private const val MAX_SPEECH_LIMIT = 5000
        private const val FAINT_AMPLITUDE = 500
        /** Voix à couvrir quand SENTIA parle : plus haut que la fuite du haut-parleur, pour ne pas s'interrompre elle-même. */
        private const val BARGE_IN_LIMIT = 7000
        private const val BARGE_IN_HITS = 2
        private const val END_SILENCE_MS = 1500L
        const val NO_SPEECH_TIMEOUT_MS = 8000L
        private const val MAX_MS = 30000L
    }
}
