package dj.sentia

import android.content.Context
import android.content.SharedPreferences
import dj.sentia.core.ShakeDetector
import java.util.UUID

/** Réglages locaux. Rien de tout cela n'est un secret serveur : la clé de l'IA reste sur le serveur. */
class Settings(context: Context) {
    private val p: SharedPreferences = context.applicationContext.getSharedPreferences("sentia", Context.MODE_PRIVATE)

    /** "auto" ou fr / en / so / ar. */
    var language: String
        get() = p.getString("language", "auto") ?: "auto"
        set(v) = p.edit().putString("language", v).apply()

    /** "" tant que l'accueil n'est pas fait, puis blind / deaf / both / other. */
    var profile: String
        get() = p.getString("profile", "") ?: ""
        set(v) = p.edit().putString("profile", v).apply()

    /** 0 faible, 1 moyenne, 2 forte. */
    var sensitivity: Int
        get() = p.getInt("sensitivity", 1)
        set(v) = p.edit().putInt("sensitivity", v).apply()

    var shakeEnabled: Boolean
        get() = p.getBoolean("shake", true)
        set(v) = p.edit().putBoolean("shake", v).apply()

    var volumeEnabled: Boolean
        get() = p.getBoolean("volume", true)
        set(v) = p.edit().putBoolean("volume", v).apply()

    /** Par défaut : réponses parlées sauf pour les personnes sourdes. */
    var voiceReplies: Boolean
        get() = if (p.contains("voice")) p.getBoolean("voice", true) else profile != "deaf"
        set(v) = p.edit().putBoolean("voice", v).apply()

    /**
     * Interrompre SENTIA en parlant (« Stop » dit pendant qu'elle parle). Désactivé par défaut : micro ouvert pendant la voix =
     * risque d'écho (SENTIA s'entend elle-même) selon le téléphone. À activer pour essayer. Le bouton Stop et la secousse
     * interrompent toujours.
     */
    var bargeIn: Boolean
        get() = p.getBoolean("barge", false) // désactivé par défaut : d'abord une conversation fiable (voix finie, puis écoute)
        set(v) = p.edit().putBoolean("barge", v).apply()

    // Diagnostic de la veille (affiché dans les Réglages) : jamais envoyé nulle part.
    var diagServiceAt: Long
        get() = p.getLong("d_service", 0L)
        set(v) = p.edit().putLong("d_service", v).apply()
    var diagShakeAt: Long
        get() = p.getLong("d_shake", 0L)
        set(v) = p.edit().putLong("d_shake", v).apply()
    var diagWake: String
        get() = p.getString("d_wake", "") ?: ""
        set(v) = p.edit().putString("d_wake", v).apply()
    var diagServiceError: String
        get() = p.getString("d_err", "") ?: ""
        set(v) = p.edit().putString("d_err", v).apply()

    var soundsEnabled: Boolean
        get() = p.getBoolean("sounds", false)
        set(v) = p.edit().putBoolean("sounds", v).apply()

    var accessCode: String
        get() = p.getString("code", "") ?: ""
        set(v) = p.edit().putString("code", v).apply()

    val serverUrl: String get() = BuildConfig.SERVER_URL

    /** Identifiant aléatoire de l'installation (pas le téléphone) : sert seulement aux quotas, haché côté serveur. */
    val deviceId: String
        get() {
            val existing = p.getString("device", null)
            if (existing != null) return existing
            val id = UUID.randomUUID().toString()
            p.edit().putString("device", id).apply()
            return id
        }

    val needsOnboarding: Boolean get() = profile.isEmpty()
    val isBlindish: Boolean get() = profile == "blind" || profile == "both"
    val isDeafish: Boolean get() = profile == "deaf" || profile == "both"

    fun shakeConfig(): ShakeDetector.Config = when (sensitivity) {
        0 -> ShakeDetector.Config.LOW
        2 -> ShakeDetector.Config.HIGH
        else -> ShakeDetector.Config.MEDIUM
    }
}
