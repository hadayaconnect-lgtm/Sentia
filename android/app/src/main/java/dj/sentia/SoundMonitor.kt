package dj.sentia

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import dj.sentia.core.Confidence
import dj.sentia.core.HapticPattern
import dj.sentia.core.SoundCategory
import dj.sentia.core.SoundEvent
import dj.sentia.core.SoundEventFilter
import org.tensorflow.lite.task.audio.classifier.AudioClassifier
import java.util.concurrent.CopyOnWriteArrayList

/** Mémoire courte des derniers sons détectés (uniquement en mémoire vive, jamais envoyée telle quelle). */
object SoundHistory {
    private val events = CopyOnWriteArrayList<SoundEvent>()
    fun add(e: SoundEvent) { events.add(e); while (events.size > 20) events.removeAt(0) }
    fun recent(nowMs: Long, windowMs: Long): List<SoundEvent> = events.filter { nowMs - it.timeMs <= windowMs }
}

fun SoundCategory.labelRes(): Int = when (this) {
    SoundCategory.DOORBELL -> R.string.snd_doorbell
    SoundCategory.CAR_HORN -> R.string.snd_car_horn
    SoundCategory.ALARM -> R.string.snd_alarm
    SoundCategory.RINGTONE -> R.string.snd_ringtone
    SoundCategory.BABY_CRY -> R.string.snd_baby_cry
}

/**
 * Surveillance des sons (EXPÉRIMENTAL). Classification 100 % locale avec YAMNet (TensorFlow Lite) :
 * l'audio ne quitte jamais le téléphone et n'est pas enregistré. Le fichier assets/yamnet.tflite doit être ajouté
 * (voir le guide) ; sans lui, la fonction reste simplement inactive.
 *
 * Android 14+ : un service « microphone » ne peut être démarré que depuis l'écran ouvert. On l'active donc
 * depuis les Réglages, et il ne redémarre pas seul au démarrage du téléphone.
 */
class SoundService : Service() {
    @Volatile private var running = false
    private var thread: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(this, Notifications.ID_SOUND_SERVICE, Notifications.soundService(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        if (thread == null) {
            running = true
            thread = Thread({ loop() }, "sentia-sounds").also { it.start() }
        }
        return START_NOT_STICKY
    }

    private fun loop() {
        val filter = SoundEventFilter()
        var classifier: AudioClassifier? = null
        var record: android.media.AudioRecord? = null
        try {
            classifier = AudioClassifier.createFromFile(this, MODEL_FILE)
            val tensor = classifier.createInputTensorAudio()
            record = classifier.createAudioRecord()
            record.startRecording()
            while (running) {
                Thread.sleep(500)
                tensor.load(record)
                val result = classifier.classify(tensor)
                val categories = result.firstOrNull()?.categories ?: continue
                val now = System.currentTimeMillis()
                for (c in categories) {
                    val event = filter.accept(c.label, c.score, now) ?: continue
                    onEvent(event)
                }
            }
        } catch (e: Exception) {
            // Modèle absent ou micro indisponible : on s'arrête sans bruit.
        } finally {
            try { record?.stop() } catch (_: Exception) {}
            try { classifier?.close() } catch (_: Exception) {}
            stopSelf()
        }
    }

    private fun onEvent(e: SoundEvent) {
        SoundHistory.add(e)
        Vibe.play(this, HapticPattern.DETECTED)
        val label = getString(e.category.labelRes())
        val text = getString(if (e.confidence == Confidence.HIGH) R.string.sound_like_high else R.string.sound_like_low, label)
        Notifications.soundEvent(this, text)
    }

    override fun onDestroy() {
        running = false
        thread?.interrupt()
        thread = null
        super.onDestroy()
    }

    companion object {
        const val MODEL_FILE = "yamnet.tflite"

        fun modelInstalled(c: Context): Boolean = try { c.assets.open(MODEL_FILE).close(); true } catch (e: Exception) { false }

        fun start(c: Context) { androidx.core.content.ContextCompat.startForegroundService(c, Intent(c, SoundService::class.java)) }
        fun stop(c: Context) { c.stopService(Intent(c, SoundService::class.java)) }
    }
}
