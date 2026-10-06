package dj.sentia

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Service de veille (premier plan) : garde la détection de secousse active écran éteint ou application fermée
 * (balayée des applications récentes). Limites : certains constructeurs (Xiaomi/HyperOS, Tecno, Infinix, Samsung…) arrêtent
 * les services ; l'écran Réglages guide vers les autorisations. Après un « Forcer l'arrêt » Android interdit tout
 * redémarrage tant que la personne n'a pas rouvert l'application : c'est une règle du système.
 */
class ShakeService : Service() {
    private lateinit var listener: ShakeListener

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        listener = ShakeListener(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            ServiceCompat.startForeground(this, Notifications.ID_STANDBY, Notifications.standby(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            settings.diagServiceError = ""
        } catch (e: Exception) {
            // Android refuse parfois de passer en premier plan (démarrage depuis l'arrière-plan) : on le note pour le diagnostic.
            settings.diagServiceError = e.javaClass.simpleName + ": " + (e.message ?: "")
            stopSelf()
            return START_NOT_STICKY
        }
        listener.start()
        return START_STICKY
    }

    override fun onDestroy() {
        listener.stop()
        super.onDestroy()
    }

    companion object {
        fun start(c: Context) { ContextCompat.startForegroundService(c, Intent(c, ShakeService::class.java)) }
        fun stop(c: Context) { c.stopService(Intent(c, ShakeService::class.java)) }
    }
}
