package dj.sentia

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import dj.sentia.core.LiveProtocol
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Micro de la conversation en direct : PCM 16 bits, 16 kHz, envoyé par morceaux de 40 ms.
 * Source « communication » + annulation d'écho et réduction de bruit du téléphone quand ils existent.
 */
class LiveMic(private val onChunk: (ByteArray) -> Unit) {
    private var rec: AudioRecord? = null
    private var thread: Thread? = null
    private var echo: AcousticEchoCanceler? = null
    private var noise: NoiseSuppressor? = null
    @Volatile private var running = false

    /** Faux si le téléphone n'a pas d'annulation d'écho : l'application coupe alors le micro pendant que SENTIA parle. */
    @Volatile var hasEchoCancel = false
        private set

    @SuppressLint("MissingPermission") // la permission est demandée avant d'ouvrir la session
    fun start(): Boolean {
        if (running) return true
        val rate = LiveProtocol.SAMPLE_RATE_IN
        val chunk = rate / 25 * 2 // 40 ms en octets (16 bits)
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return false
        val r = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, chunk * 8))
        } catch (_: Exception) { return false }
        if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); return false }
        try {
            if (AcousticEchoCanceler.isAvailable()) echo = AcousticEchoCanceler.create(r.audioSessionId)?.also { it.enabled = true; hasEchoCancel = true }
            if (NoiseSuppressor.isAvailable()) noise = NoiseSuppressor.create(r.audioSessionId)?.also { it.enabled = true }
        } catch (_: Exception) {}
        try { r.startRecording() } catch (_: Exception) {
            try { echo?.release(); noise?.release() } catch (_: Exception) {}
            echo = null; noise = null; hasEchoCancel = false
            r.release(); return false
        }
        rec = r
        running = true
        thread = Thread({
            val buf = ByteArray(chunk)
            while (running) {
                val n = try { r.read(buf, 0, buf.size) } catch (_: Exception) { -1 }
                if (n > 0) onChunk(buf.copyOf(n)) else if (n < 0) break
            }
        }, "sentia-live-mic").also { it.start() }
        return true
    }

    fun stop() {
        running = false
        try { thread?.join(500) } catch (_: Exception) {}
        thread = null
        try { rec?.stop() } catch (_: Exception) {}
        try { echo?.release(); noise?.release() } catch (_: Exception) {}
        try { rec?.release() } catch (_: Exception) {}
        rec = null; echo = null; noise = null
    }
}

/** Voix de SENTIA en direct : PCM 16 bits, 24 kHz, jouée au fil de l'eau. `clear()` coupe net ce qui restait à dire (interruption). */
class LivePlayer {
    private var track: AudioTrack? = null
    private var thread: Thread? = null
    private val queue = LinkedBlockingQueue<ByteArray>()
    @Volatile private var running = false
    @Volatile private var busyUntil = 0L

    /** Vrai tant que de la voix est en file ou en cours de lecture. */
    val speaking: Boolean get() = System.currentTimeMillis() < busyUntil

    fun start(): Boolean {
        if (running) return true
        val rate = LiveProtocol.SAMPLE_RATE_OUT
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return false
        val t = try {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(maxOf(min, rate)) // 24 000 octets = 0,5 s en 16 bits mono à 24 kHz (12 000 échantillons) : tampon court, interruption rapide
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (_: Exception) { return false }
        try { t.play() } catch (_: Exception) { t.release(); return false }
        track = t
        running = true
        thread = Thread({
            while (running) {
                val b = try { queue.poll(100, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { null } ?: continue
                try { t.write(b, 0, b.size) } catch (_: Exception) {}
            }
        }, "sentia-live-player").also { it.start() }
        return true
    }

    fun write(pcm: ByteArray) {
        if (!running || pcm.isEmpty()) return
        val now = System.currentTimeMillis()
        busyUntil = maxOf(busyUntil, now) + pcm.size * 1000L / (LiveProtocol.SAMPLE_RATE_OUT * 2)
        queue.add(pcm)
    }

    /** Interruption : on vide la file et la mémoire tampon de la lecture. */
    fun clear() {
        queue.clear()
        busyUntil = 0L
        val t = track ?: return
        try { t.pause(); t.flush(); t.play() } catch (_: Exception) {}
    }

    fun stop() {
        running = false
        queue.clear()
        busyUntil = 0L
        try { thread?.join(500) } catch (_: Exception) {}
        thread = null
        try { track?.stop() } catch (_: Exception) {}
        try { track?.release() } catch (_: Exception) {}
        track = null
    }
}

/** Mode « communication » du téléphone (annulation d'écho) + haut-parleur, sauf si un casque ou des écouteurs sont branchés. */
class LiveRoute(context: Context) {
    private val audio = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var oldMode = AudioManager.MODE_NORMAL
    private var opened = false
    @Suppress("DEPRECATION") private var oldSpeaker = false
    private var focus: AudioFocusRequest? = null

    @Suppress("DEPRECATION")
    fun open() {
        opened = true
        oldMode = audio.mode
        oldSpeaker = audio.isSpeakerphoneOn
        try {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .build()
            focus = req
            audio.requestAudioFocus(req)
        } catch (_: Exception) {}
        try {
            audio.mode = AudioManager.MODE_IN_COMMUNICATION
            if (!headsetConnected()) audio.isSpeakerphoneOn = true
        } catch (_: Exception) {}
    }

    @Suppress("DEPRECATION")
    fun close() {
        if (!opened) return // rien n'a été modifié : on ne touche pas au routage audio de la personne
        opened = false
        try { audio.isSpeakerphoneOn = oldSpeaker; audio.mode = oldMode } catch (_: Exception) {}
        try { focus?.let { audio.abandonAudioFocusRequest(it) } } catch (_: Exception) {}
        focus = null
    }

    private fun headsetConnected(): Boolean = try {
        audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                it.type == android.media.AudioDeviceInfo.TYPE_USB_HEADSET
        }
    } catch (_: Exception) { false }
}
