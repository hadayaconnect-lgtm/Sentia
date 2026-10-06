package dj.sentia

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import androidx.lifecycle.LifecycleOwner
import dj.sentia.core.HapticPattern
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import kotlin.coroutines.resume

/** Ce que l'écran doit fournir à l'exécuteur d'outils : permissions, confirmations, cycle de vie. */
interface ToolHost {
    val context: Context
    val lifecycleOwner: LifecycleOwner
    suspend fun ensurePermission(permission: String): Boolean
    suspend fun confirm(title: String, message: String): Boolean
}

class ToolOutput(val content: JSONArray, val isError: Boolean = false) {
    companion object {
        fun text(t: String, isError: Boolean = false) = ToolOutput(JSONArray().put(Conversation.text(t)), isError)
    }
}

/**
 * Exécute les outils demandés par l'IA. C'est l'APPLICATION qui décide : permission du téléphone, confirmation
 * pour les actions sensibles. L'IA ne touche jamais directement au téléphone.
 */
class ToolExecutor(private val host: ToolHost, private val settings: Settings) {

    suspend fun run(name: String, input: JSONObject): ToolOutput {
        val c = host.context
        return try {
            when (name) {
                "capture_camera" -> capture(input.optString("purpose", "scene"))
                "get_location" -> location()
                "get_time" -> ToolOutput.text(DateFormat.getDateTimeInstance(DateFormat.FULL, DateFormat.MEDIUM).format(Date()) + " (" + java.util.TimeZone.getDefault().id + ")")
                "get_recent_sounds" -> sounds()
                "vibrate" -> {
                    val pattern = HapticPattern.fromKey(input.optString("pattern")) ?: HapticPattern.INFO
                    Vibe.play(c, pattern)
                    ToolOutput.text("OK")
                }
                "change_language" -> {
                    val l = input.optString("language", "auto")
                    if (l == "auto" || l in Lang.ALL) { settings.language = l; ToolOutput.text("OK") }
                    else ToolOutput.text(c.getString(R.string.tool_unavailable), true)
                }
                "start_navigation" -> navigate(input.optString("destination"))
                else -> ToolOutput.text(c.getString(R.string.tool_unavailable), true)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // le délai d'analyse (withTimeoutOrNull) ne doit pas être avalé
        } catch (e: Exception) {
            ToolOutput.text("Erreur : " + (e.message ?: e.javaClass.simpleName), true)
        }
    }

    /** Permission caméra (demandée avant de lancer le chronomètre d'analyse : la boîte de dialogue ne compte pas). */
    suspend fun ensureCamera(): Boolean = host.ensurePermission(Manifest.permission.CAMERA)

    /** Prend UNE photo (JPEG base64). Annulable. */
    suspend fun takePhoto(): String = CameraCapture.capture(host.context, host.lifecycleOwner, highRes = true)

    private suspend fun capture(purpose: String): ToolOutput {
        val c = host.context
        if (!host.ensurePermission(Manifest.permission.CAMERA)) return ToolOutput.text(c.getString(R.string.tool_denied), true)
        val b64 = CameraCapture.capture(c, host.lifecycleOwner, highRes = purpose != "color") // texte, billets et scènes : définition plus haute pour bien lire
        val content = JSONArray()
            .put(Conversation.image(b64))
            .put(Conversation.text("Photo prise (but : $purpose). Cadrage non vérifié : si l'image est floue, coupée ou vide, dis-le et explique comment tenir le téléphone."))
        return ToolOutput(content)
    }

    private suspend fun location(): ToolOutput {
        val c = host.context
        val fine = host.ensurePermission(Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = fine || host.ensurePermission(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (!coarse) return ToolOutput.text(c.getString(R.string.tool_denied), true)
        val loc = currentLocation() ?: return ToolOutput.text("Position introuvable pour le moment.", true)
        val address = withContext(Dispatchers.IO) { reverseGeocode(loc) }
        val text = buildString {
            if (address != null) append("Adresse approximative : $address. ")
            append("Coordonnées : %.5f, %.5f (précision environ %d m).".format(java.util.Locale.US, loc.latitude, loc.longitude, loc.accuracy.toInt()))
        }
        return ToolOutput.text(text)
    }

    @SuppressLint("MissingPermission")
    private suspend fun currentLocation(): Location? {
        val lm = host.context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = lm.getProviders(true)
        // Position récente d'abord (instantanée).
        val last = providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .filter { System.currentTimeMillis() - it.time < 2 * 60_000 }
            .minByOrNull { it.accuracy }
        if (last != null) return last
        if (Build.VERSION.SDK_INT >= 30 && providers.isNotEmpty()) {
            val provider = if (LocationManager.GPS_PROVIDER in providers) LocationManager.GPS_PROVIDER else providers.first()
            return withContext(Dispatchers.Main) {
                suspendCancellableCoroutine<Location?> { cont ->
                    val cancel = CancellationSignal()
                    cont.invokeOnCancellation { cancel.cancel() }
                    lm.getCurrentLocation(provider, cancel, host.context.mainExecutor) { l -> if (cont.isActive) cont.resume(l) }
                }
            }
        }
        return providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.minByOrNull { it.accuracy }
    }

    @Suppress("DEPRECATION")
    private fun reverseGeocode(loc: Location): String? = try {
        if (!Geocoder.isPresent()) null
        else Geocoder(host.context, Lang.locale(if (settings.language == "auto") "fr" else settings.language))
            .getFromLocation(loc.latitude, loc.longitude, 1)?.firstOrNull()?.let { a ->
                (0..a.maxAddressLineIndex).joinToString(", ") { a.getAddressLine(it) }.ifBlank { null }
            }
    } catch (e: Exception) {
        null
    }

    private fun sounds(): ToolOutput {
        val c = host.context
        val recent = SoundHistory.recent(System.currentTimeMillis(), 2 * 60_000)
        if (!settings.soundsEnabled) return ToolOutput.text("La détection de sons est désactivée.")
        if (recent.isEmpty()) return ToolOutput.text("Aucun son notable détecté ces deux dernières minutes. Détection expérimentale, elle peut en manquer.")
        val lines = recent.joinToString("\n") { e ->
            val secs = (System.currentTimeMillis() - e.timeMs) / 1000
            "- il y a $secs s : un son pouvant ressembler à ${e.category.key} (confiance ${if (e.confidence == dj.sentia.core.Confidence.HIGH) "plutôt bonne" else "faible"})"
        }
        return ToolOutput.text("Sons détectés (expérimental, jamais certain) :\n$lines")
    }

    private suspend fun navigate(destination: String): ToolOutput {
        val c = host.context
        val dest = destination.trim()
        if (dest.isEmpty()) return ToolOutput.text(c.getString(R.string.tool_unavailable), true)
        val ok = host.confirm(c.getString(R.string.nav_confirm_title), c.getString(R.string.nav_confirm_msg, dest))
        if (!ok) return ToolOutput.text(c.getString(R.string.cancelled), true)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(dest))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val fallback = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(dest))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            c.startActivity(intent); ToolOutput.text("Navigation ouverte vers : $dest")
        } catch (e: Exception) {
            try { c.startActivity(fallback); ToolOutput.text("Carte ouverte vers : $dest") }
            catch (e2: Exception) { ToolOutput.text(c.getString(R.string.tool_unavailable), true) }
        }
    }
}
