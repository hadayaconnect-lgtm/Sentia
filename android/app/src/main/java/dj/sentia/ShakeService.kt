package dj.sentia

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dj.sentia.core.ShakeDetector

/**
 * Service de veille : lit l'accéléromètre et applique ShakeDetector. Aucune donnée de capteur n'est
 * conservée ni envoyée. Limites : certains constructeurs (Xiaomi, Tecno, Infinix, Samsung…) arrêtent les services
 * en arrière-plan ; l'écran Réglages guide vers l'exemption de batterie.
 */
class ShakeService : Service(), SensorEventListener {
    private lateinit var sm: SensorManager
    private val detector = ShakeDetector()
    private var accel: Sensor? = null
    private var steps: Sensor? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(this, Notifications.ID_STANDBY, Notifications.standby(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        detector.config = settings.shakeConfig()
        detector.reset()
        sm.unregisterListener(this)
        // Capteur « réveil » si dispo : il continue de fonctionner écran éteint, sans garder le processeur éveillé.
        accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER, true) ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        accel?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        steps = sm.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
        if (steps != null && ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACTIVITY_RECOGNITION) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            sm.registerListener(this, steps, SensorManager.SENSOR_DELAY_NORMAL)
        }
        return START_STICKY
    }

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_STEP_DETECTOR -> {
                // Même horloge que l'accéléromètre (temps capteur) : en marche pendant 4 s après le dernier pas.
                detector.walkingUntilMs = e.timestamp / 1_000_000L + 4000
            }
            Sensor.TYPE_ACCELEROMETER -> {
                val tsMs = e.timestamp / 1_000_000L
                if (detector.onSample(tsMs, e.values[0], e.values[1], e.values[2])) WakeController.wake(this, "shake")
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onDestroy() {
        sm.unregisterListener(this)
        super.onDestroy()
    }

    companion object {
        fun start(c: Context) { ContextCompat.startForegroundService(c, Intent(c, ShakeService::class.java)) }
        fun stop(c: Context) { c.stopService(Intent(c, ShakeService::class.java)) }
    }
}
