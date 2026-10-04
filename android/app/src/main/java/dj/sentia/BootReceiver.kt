package dj.sentia

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Relance la veille après le redémarrage du téléphone ou une mise à jour. Android 15 limite certains types de
 * services au démarrage : si le démarrage échoue, on ignore (la personne rouvre SENTIA une fois).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val s = context.settings
        if (!s.needsOnboarding && s.shakeEnabled) {
            try { ShakeService.start(context) } catch (_: Exception) {}
        }
    }
}
