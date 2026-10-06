package dj.sentia

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.core.content.ContextCompat
import dj.sentia.core.ShakeDetector

/**
 * Lecture de l'accéléromètre + détecteur de secousse. Utilisé par DEUX services : la veille (ShakeService) et le service
 * d'accessibilité (que le téléphone garde en vie et relance de lui-même, même après fermeture de l'application).
 * Si les deux tournent, le délai anti-répétition de WakeController évite un double réveil.
 * Aucune donnée de capteur n'est conservée ni envoyée.
 */
class ShakeListener(private val context: Context) : SensorEventListener {
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val detector = ShakeDetector()
    private var sensitivity = -1
    private var running = false
    private var lastDiag = 0L
    private var peak = 0f

    fun start() {
        stop()
        if (!context.settings.shakeEnabled) return
        sensitivity = context.settings.sensitivity
        detector.config = context.settings.shakeConfig()
        detector.reset()
        // Capteur « réveil » si dispo : il continue de fonctionner écran éteint, sans garder le processeur éveillé.
        val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER, true) ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        accel?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        val steps = sm.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
        if (steps != null && ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED) {
            sm.registerListener(this, steps, SensorManager.SENSOR_DELAY_NORMAL)
        }
        running = accel != null
        if (running) context.settings.diagServiceAt = System.currentTimeMillis()
    }

    fun stop() {
        sm.unregisterListener(this)
        running = false
    }

    override fun onSensorChanged(e: SensorEvent) {
        val st = context.settings
        if (!st.shakeEnabled) return
        if (st.sensitivity != sensitivity) { sensitivity = st.sensitivity; detector.config = st.shakeConfig() }
        when (e.sensor.type) {
            // Même horloge que l'accéléromètre (temps capteur) : en marche pendant 4 s après le dernier pas.
            Sensor.TYPE_STEP_DETECTOR -> detector.walkingUntilMs = e.timestamp / 1_000_000L + 4000
            Sensor.TYPE_ACCELEROMETER -> {
                val tsMs = e.timestamp / 1_000_000L
                val hit = detector.onSample(tsMs, e.values[0], e.values[1], e.values[2])
                if (detector.lastMag > peak) peak = detector.lastMag
                val nowMs = System.currentTimeMillis()
                if (nowMs - lastDiag > 1000) { lastDiag = nowMs; st.diagSamplesAt = nowMs; st.diagPeak = peak; peak = 0f }
                if (hit) {
                    st.diagShakeAt = System.currentTimeMillis()
                    WakeController.wake(context, "shake")
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
