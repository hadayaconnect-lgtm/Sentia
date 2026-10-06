package dj.sentia

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Réglages réduits à l'essentiel : la voix, le « Stop » vocal, le réveil par Volume + et la sensibilité sont automatiques.
 * Restent : la langue, le code d'accès, les autorisations Android (qu'aucune application ne peut s'accorder seule) et un
 * diagnostic en direct pour savoir pourquoi le réveil ou l'IA ne répond pas.
 */
class SettingsActivity : AppCompatActivity() {
    private val languages = listOf("auto", "fr", "en", "so", "ar")

    private lateinit var spLanguage: Spinner
    private lateinit var etCode: EditText
    private lateinit var diagStatus: TextView
    private val ui = Handler(Looper.getMainLooper())
    private val refresher = object : Runnable {
        override fun run() { refreshDiag(); ui.postDelayed(this, 1500) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        title = getString(R.string.settings_title)

        spLanguage = findViewById(R.id.spLanguage)
        etCode = findViewById(R.id.etCode)
        diagStatus = findViewById(R.id.diagStatus)

        spLanguage.adapter = adapter(listOf(R.string.lang_auto, R.string.lang_fr, R.string.lang_en, R.string.lang_so, R.string.lang_ar))
        val s = settings
        spLanguage.setSelection(languages.indexOf(s.language).coerceAtLeast(0))
        etCode.setText(s.accessCode)

        findViewById<Button>(R.id.btnBattery).setOnClickListener { openBatterySettings() }
        findViewById<Button>(R.id.btnOverlay).setOnClickListener {
            try { startActivity(Intent(AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
            catch (e: Exception) { openAppDetails() }
        }
        findViewById<Button>(R.id.btnAppPerms).setOnClickListener { openAppPermissions() }
        findViewById<Button>(R.id.btnA11y).setOnClickListener { startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS)) }
        findViewById<Button>(R.id.btnProfile).setOnClickListener {
            settings.profile = "" // relance l'écran d'accueil (choix du mode)
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
        }
        findViewById<Button>(R.id.btnSave).setOnClickListener { applyAndFinish() }
    }

    private fun adapter(items: List<Int>) =
        ArrayAdapter(this, android.R.layout.simple_spinner_item, items.map { getString(it) }).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

    override fun onResume() {
        super.onResume()
        ui.post(refresher)
        // Le réveil par Volume + passe par le service d'accessibilité ; la veille garde l'application en vie.
        try { ShakeService.start(this) } catch (_: Exception) {}
    }

    override fun onPause() {
        ui.removeCallbacks(refresher)
        super.onPause()
    }

    /** État réel de la veille : de quoi savoir pourquoi la secousse ne réveille pas (ou si c'est Android qui bloque). */
    private fun refreshDiag() {
        val s = settings
        val f = java.text.SimpleDateFormat("dd/MM HH:mm:ss", java.util.Locale.getDefault())
        fun at(t: Long) = if (t == 0L) "jamais" else f.format(java.util.Date(t))
        val overlay = if (Build.VERSION.SDK_INT >= 23) AndroidSettings.canDrawOverlays(this) else true
        val battery = (getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)
        val a11y = (getSystemService(Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager)
            .getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_GENERIC)
            .any { it.resolveInfo.serviceInfo.packageName == packageName }
        diagStatus.text = buildString {
            append("Diagnostic du réveil et des performances\n")
            append("• Veille démarrée : ").append(at(s.diagServiceAt)).append('\n')
            if (s.diagServiceError.isNotEmpty()) append("• Erreur de la veille : ").append(s.diagServiceError).append('\n')
            append("• Dernier réveil : ").append(s.diagWake.ifEmpty { "aucun" }).append('\n')
            append("• Dernière analyse : ").append(if (s.diagPerf.isEmpty()) "aucune" else "\n" + s.diagPerf).append('\n')
            append("• Service d'accessibilité : ").append(if (a11y) "activé" else "désactivé").append('\n')
            append("• Affichage par-dessus les autres apps : ").append(if (overlay) "autorisé" else "NON autorisé").append('\n')
            append("• Batterie sans restriction : ").append(if (battery) "oui" else "non")
        }
    }

    private fun openAppDetails() {
        startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    /** HyperOS / MIUI : page « Autres autorisations » (fenêtres en arrière-plan). Sinon, page standard de l'application. */
    private fun openAppPermissions() {
        try {
            startActivity(Intent("miui.intent.action.APP_PERM_EDITOR")
                .setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
                .putExtra("extra_pkgname", packageName))
        } catch (e: Exception) {
            openAppDetails()
        }
    }

    // ---- Android : batterie, accessibilité ---------------------------------------------------------------------------

    private fun openBatterySettings() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        try {
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                startActivity(Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            } else {
                startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        } catch (e: Exception) {
            startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    // ---- Android : batterie, accessibilité ---------------------------------------------------------------------------


    private fun applyAndFinish() {
        val s = settings
        val oldLang = s.language
        s.language = languages[spLanguage.selectedItemPosition]
        s.accessCode = etCode.text.toString().trim()
        Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
        if (oldLang != s.language) SentiaApp.applyUiLanguage(s.language)
        finish()
    }
}
