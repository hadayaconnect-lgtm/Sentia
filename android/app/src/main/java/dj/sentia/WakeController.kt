package dj.sentia

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import dj.sentia.core.HapticPattern

/**
 * Point d'entrée UNIQUE du réveil (secousse ou touches de volume).
 * 1. vibration « réveillé » (la seule confirmation sûre pour une personne sourde-aveugle),
 * 2. ouverture de l'écran SENTIA,
 * 3. repli : notification plein écran si Android refuse d'ouvrir l'écran depuis l'arrière-plan.
 *
 * Limites Android : l'ouverture d'un écran depuis l'arrière-plan peut être refusée selon la version et la marque ;
 * c'est la raison du repli. À valider sur de vrais téléphones.
 */
object WakeController {
    private const val COOLDOWN_MS = 2500L
    @Volatile private var lastWake = 0L
    private val main = Handler(Looper.getMainLooper())

    fun wake(context: Context, source: String) {
        val c = context.applicationContext
        val now = System.currentTimeMillis()
        if (now - lastWake < COOLDOWN_MS) return
        lastWake = now
        Vibe.play(c, HapticPattern.AWAKE)

        // Allume l'écran un court instant si besoin.
        val pm = c.getSystemService(Context.POWER_SERVICE) as PowerManager
        @Suppress("DEPRECATION")
        val lock = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "sentia:wake")
        try { lock.acquire(5000) } catch (_: Exception) {}

        val intent = Intent(c, AssistantActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            .putExtra(AssistantActivity.EXTRA_WAKE, true)
            .putExtra(AssistantActivity.EXTRA_SOURCE, source)
        try { c.startActivity(intent) } catch (_: Exception) {}
        // Dans tous les cas on pose aussi la notification plein écran : si l'activité s'est ouverte,
        // elle l'efface ; sinon c'est elle qui ouvre SENTIA.
        Notifications.wake(c)
        main.postDelayed({ if (!AssistantActivity.visible) Notifications.wake(c) }, 800)
    }

    @Suppress("unused")
    fun isLocked(c: Context): Boolean = (c.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked
}
