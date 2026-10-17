package dj.sentia

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Préparation guidée, une seule fois, juste après le choix du profil :
 * 1) micro, caméra, notifications (fenêtres Android) ; 2) un bouton qui ouvre directement l'accessibilité
 * (Android interdit à une application de s'activer elle-même) ; 3) vérification puis « C'est prêt ».
 * Tout est dit à voix haute. Rien n'est bloquant : « Plus tard » mène directement à SENTIA.
 */
class SetupActivity : AppCompatActivity() {
    private lateinit var speaker: Speaker
    private lateinit var title: TextView
    private lateinit var message: TextView
    private lateinit var main: Button
    private lateinit var later: Button
    private var speakJob: Job? = null
    private var phase = Phase.PERMISSIONS
    private var wake = false
    private var opened = false

    private enum class Phase { PERMISSIONS, ACCESSIBILITY, OVERLAY, BATTERY, DONE }

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { afterPermissions() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)
        title = findViewById(R.id.suTitle)
        message = findViewById(R.id.suMessage)
        main = findViewById(R.id.suMain)
        later = findViewById(R.id.suLater)
        wake = intent.getBooleanExtra(AssistantActivity.EXTRA_WAKE, false)
        speaker = Speaker(this, AgentClient(this))
        main.setOnClickListener { onMain() }
        later.setOnClickListener { skip() }
        showPermissions()
    }

    private fun lang() = Lang.ui(settings.language)

    private fun say(text: String) {
        speakJob?.cancel()
        speakJob = lifecycleScope.launch { speaker.say(text, lang()) }
    }

    private fun missing(): List<String> {
        val wanted = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33) wanted += Manifest.permission.POST_NOTIFICATIONS
        return wanted.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
    }

    private fun serviceEnabled(): Boolean =
        (getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager)
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_GENERIC)
            .any { it.resolveInfo.serviceInfo.packageName == packageName }

    private fun overlayAllowed(): Boolean = Build.VERSION.SDK_INT < 23 || AndroidSettings.canDrawOverlays(this)

    private fun batteryAllowed(): Boolean =
        (getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)

    private fun showPermissions() {
        phase = Phase.PERMISSIONS
        title.setText(R.string.su_perm_title)
        message.setText(R.string.su_perm_msg)
        main.setText(R.string.su_perm_btn)
        later.visibility = View.GONE
        if (missing().isEmpty()) { afterPermissions(); return }
        say(getString(R.string.su_perm_msg))
        // Les fenêtres d'Android s'ouvrent après la phrase d'introduction (ou au bouton pour qui préfère toucher).
        lifecycleScope.launch {
            speakJob?.join()
            if (phase == Phase.PERMISSIONS && missing().isNotEmpty()) permissions.launch(missing().toTypedArray())
        }
    }

    private fun afterPermissions() {
        if (settings.isBlindish && !serviceEnabled()) showAccessibility() else afterAccessibility()
    }

    private fun afterAccessibility() {
        if (settings.isBlindish && !overlayAllowed()) showOverlay() else afterOverlay()
    }

    private fun afterOverlay() {
        if (settings.isBlindish && !batteryAllowed()) showBattery() else showReady()
    }

    private fun showStep(p: Phase, title: Int, msg: Int, btn: Int) {
        phase = p
        this.title.setText(title)
        message.setText(msg)
        main.setText(btn)
        later.setText(R.string.su_later)
        later.visibility = View.VISIBLE
        say(getString(msg))
    }

    private fun showOverlay() = showStep(Phase.OVERLAY, R.string.su_overlay_title, R.string.su_overlay_msg, R.string.su_overlay_btn)
    private fun showBattery() = showStep(Phase.BATTERY, R.string.su_battery_title, R.string.su_battery_msg, R.string.su_battery_btn)

    private fun showAccessibility() {
        phase = Phase.ACCESSIBILITY
        title.setText(R.string.su_a11y_title)
        message.setText(R.string.su_a11y_msg)
        main.setText(R.string.su_a11y_btn)
        later.setText(R.string.su_later)
        later.visibility = View.VISIBLE
        say(getString(R.string.su_a11y_msg))
    }

    private fun showReady() {
        phase = Phase.DONE
        title.setText(R.string.su_ready_title)
        message.setText(R.string.su_ready_msg)
        main.setText(R.string.su_ready_btn)
        later.visibility = View.GONE
        say(getString(R.string.su_ready_msg))
    }

    private fun skip() {
        speakJob?.cancel(); speaker.stop()
        when (phase) {
            Phase.ACCESSIBILITY -> afterAccessibility()
            Phase.OVERLAY -> afterOverlay()
            else -> showReady()
        }
    }

    private fun onMain() {
        when (phase) {
            Phase.PERMISSIONS -> if (missing().isEmpty()) afterPermissions() else permissions.launch(missing().toTypedArray())
            Phase.ACCESSIBILITY -> {
                speakJob?.cancel()
                speaker.stop()
                opened = true
                startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            Phase.OVERLAY -> {
                speakJob?.cancel(); speaker.stop()
                opened = true
                try { startActivity(Intent(AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
                catch (_: Exception) { startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
            }
            Phase.BATTERY -> {
                speakJob?.cancel(); speaker.stop()
                opened = true
                try { startActivity(Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) }
                catch (_: Exception) { startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            }
            Phase.DONE -> finishSetup()
        }
    }

    override fun onResume() {
        super.onResume()
        // Retour de l'écran d'accessibilité : on revérifie.
        if (!opened) return
        opened = false
        when (phase) {
            Phase.ACCESSIBILITY -> if (serviceEnabled()) afterAccessibility() else say(getString(R.string.su_a11y_retry))
            Phase.OVERLAY -> if (overlayAllowed()) afterOverlay() else say(getString(R.string.su_overlay_retry))
            Phase.BATTERY -> if (batteryAllowed()) showReady() else say(getString(R.string.su_battery_retry))
            else -> {}
        }
    }

    private fun finishSetup() {
        speakJob?.cancel()
        speaker.stop()
        startActivity(Intent(this, AssistantActivity::class.java).putExtra(AssistantActivity.EXTRA_WAKE, wake))
        finish()
    }

    override fun onDestroy() {
        speakJob?.cancel()
        speaker.shutdown()
        super.onDestroy()
    }
}
