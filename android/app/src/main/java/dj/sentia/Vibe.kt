package dj.sentia

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import dj.sentia.core.HapticPattern

object Vibe {
    @Suppress("DEPRECATION")
    private fun vibrator(c: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= 31) c.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else c.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

    fun play(c: Context, pattern: HapticPattern) {
        val v = vibrator(c) ?: return
        if (!v.hasVibrator()) return
        v.vibrate(VibrationEffect.createWaveform(pattern.timings, -1))
    }
}
