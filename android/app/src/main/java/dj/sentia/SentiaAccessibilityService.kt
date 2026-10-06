package dj.sentia

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import dj.sentia.core.VolumeGesture
import dj.sentia.core.VolumeTriple

/**
 * Repère « les deux touches de volume tenues 1 seconde » et garde aussi la détection de secousse active. Il ne lit aucun contenu d'écran
 * (aucun événement d'accessibilité n'est exploité). Le comportement écran éteint / verrouillé varie selon les
 * téléphones : à valider sur chaque modèle.
 */
class SentiaAccessibilityService : AccessibilityService() {
    private val gesture = VolumeGesture(1000)
    private val triple = VolumeTriple()
    private val handler = Handler(Looper.getMainLooper())

    private val ticker = object : Runnable {
        override fun run() {
            if (gesture.tick(SystemClock.uptimeMillis())) WakeController.wake(this@SentiaAccessibilityService, "volume")
            if (gesture.bothHeld) handler.postDelayed(this, 100)
        }
    }

    private var shake: ShakeListener? = null
    private var tripleConsumeUp = false

    /** Le téléphone garde ce service en vie et le relance : la secousse y reste donc détectée après fermeture de l'application. */
    override fun onServiceConnected() {
        super.onServiceConnected()
        if (settings.shakeEnabled) shake = ShakeListener(this).also { it.start() }
    }

    override fun onDestroy() {
        shake?.stop(); shake = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!settings.volumeEnabled) return false
        val key = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> VolumeGesture.Key.UP
            KeyEvent.KEYCODE_VOLUME_DOWN -> VolumeGesture.Key.DOWN
            else -> return false
        }
        val down = event.action == KeyEvent.ACTION_DOWN
        // Trois appuis rapides sur Volume + : réveil (la 3e pression est consommée, les relâchements aussi).
        if (key == VolumeGesture.Key.UP && !gesture.bothHeld) {
            if (down && event.repeatCount == 0 && triple.onUpPress(SystemClock.uptimeMillis())) {
                tripleConsumeUp = true
                WakeController.wake(this, "volume")
                return true
            }
            if (!down && tripleConsumeUp) { tripleConsumeUp = false; return true }
        }
        val wasBoth = gesture.bothHeld
        val consume = gesture.onKey(key, down, SystemClock.uptimeMillis())
        if (!wasBoth && gesture.bothHeld) {
            handler.removeCallbacks(ticker)
            handler.postDelayed(ticker, 100)
        }
        return consume
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        handler.removeCallbacks(ticker)
        gesture.reset()
        shake?.stop(); shake = null
        return super.onUnbind(intent)
    }
}
