package dj.sentia

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

object Notifications {
    const val CH_STANDBY = "standby"
    const val CH_WAKE = "wake"
    const val CH_SOUND = "sound"
    const val ID_STANDBY = 1
    const val ID_WAKE = 2
    const val ID_SOUND_SERVICE = 3
    const val ID_SOUND_EVENT = 4

    fun createChannels(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_STANDBY, c.getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_WAKE, c.getString(R.string.notif_wake_channel), NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_SOUND, c.getString(R.string.notif_sound_channel), NotificationManager.IMPORTANCE_HIGH))
    }

    private fun openIntent(c: Context, wake: Boolean = false): PendingIntent {
        val i = Intent(c, AssistantActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(AssistantActivity.EXTRA_WAKE, wake)
        return PendingIntent.getActivity(c, if (wake) 1 else 0, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun standby(c: Context): Notification =
        NotificationCompat.Builder(c, CH_STANDBY)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(c.getString(R.string.app_name))
            .setContentText(c.getString(R.string.notif_text))
            .setOngoing(true)
            .setContentIntent(openIntent(c))
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

    /**
     * Repli quand Android refuse d'ouvrir l'écran depuis l'arrière-plan : une notification « plein écran »
     * (comme un appel entrant) qui ouvre SENTIA, ou qu'on touche.
     */
    fun wake(c: Context) {
        val n = NotificationCompat.Builder(c, CH_WAKE)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(c.getString(R.string.notif_wake_title))
            .setContentText(c.getString(R.string.notif_wake_text))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .setContentIntent(openIntent(c, true))
            .setFullScreenIntent(openIntent(c, true), true)
            .build()
        c.getSystemService(NotificationManager::class.java).notify(ID_WAKE, n)
    }

    fun soundEvent(c: Context, text: String) {
        val n = NotificationCompat.Builder(c, CH_SOUND)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(c.getString(R.string.app_name))
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openIntent(c))
            .build()
        c.getSystemService(NotificationManager::class.java).notify(ID_SOUND_EVENT, n)
    }

    fun soundService(c: Context): Notification =
        NotificationCompat.Builder(c, CH_STANDBY)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(c.getString(R.string.app_name))
            .setContentText(c.getString(R.string.notif_sound_service))
            .setOngoing(true)
            .build()
}
