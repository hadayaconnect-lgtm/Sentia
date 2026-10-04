package dj.sentia

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

class SentiaApp : Application() {
    lateinit var settings: Settings
        private set

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        Notifications.createChannels(this)
        applyUiLanguage(settings.language)
    }

    companion object {
        /** Langue de l'interface : celle choisie, ou celle du téléphone en mode automatique. */
        fun applyUiLanguage(lang: String) {
            val list = if (lang == "auto") LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(lang)
            AppCompatDelegate.setApplicationLocales(list)
        }
    }
}

val android.content.Context.settings: Settings
    get() = (applicationContext as SentiaApp).settings
