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
import android.view.LayoutInflater
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import android.widget.CheckBox
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
    private class Row(
        val dot: TextView, val state: TextView, val text: View, val action: Button,
        val title: Int, val on: Int, val ok: () -> Boolean, val fix: () -> Unit, val open: () -> Unit,
    )
    private val rows = mutableListOf<Row>()
    private lateinit var cfgSummary: TextView
    private val ui = Handler(Looper.getMainLooper())
    private val refresher = object : Runnable {
        override fun run() { refreshDiag(); refreshRows(); ui.postDelayed(this, 1500) }
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
        findViewById<CheckBox>(R.id.cbLive).isChecked = s.liveMode

        cfgSummary = findViewById(R.id.cfgSummary)
        // SENTIA est réservée aux personnes aveugles : un ancien profil enregistré (sourd, autre) est ramené au mode aveugle.
        if (settings.profile != "blind") settings.profile = "blind"
        buildRows()

        findViewById<Button>(R.id.btnAdvanced).setOnClickListener { startActivity(Intent(this, PermissionsActivity::class.java)) }
        findViewById<Button>(R.id.btnHyperOs).setOnClickListener { openAppPermissions() }
        findViewById<Button>(R.id.btnCheck).setOnClickListener { verify() }
        findViewById<Button>(R.id.btnSave).setOnClickListener { applyAndFinish() }
    }


    // ---- Lignes d'état des autorisations ---------------------------------------------------------------------------

    private fun a11yEnabled(): Boolean =
        (getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager)
            .getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_GENERIC)
            .any { it.resolveInfo.serviceInfo.packageName == packageName }

    private fun overlayOk(): Boolean = Build.VERSION.SDK_INT < 23 || AndroidSettings.canDrawOverlays(this)

    private fun batteryOk(): Boolean = (getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)

    private fun openOverlaySettings() {
        try { startActivity(Intent(AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
        catch (e: Exception) { openAppDetails() }
    }

    private fun openAccessibility() = startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS))

    private fun buildRows() {
        val box = findViewById<LinearLayout>(R.id.rowsContainer)
        fun add(title: Int, on: Int, ok: () -> Boolean, fix: () -> Unit, open: () -> Unit) {
            if (rows.isNotEmpty()) {
                box.addView(View(this).apply {
                    setBackgroundColor(ContextCompat.getColor(this@SettingsActivity, R.color.black))
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2))
            }
            val v = LayoutInflater.from(this).inflate(R.layout.item_perm_row, box, false)
            v.findViewById<TextView>(R.id.rowTitle).setText(title)
            box.addView(v)
            rows.add(Row(v.findViewById(R.id.rowDot), v.findViewById(R.id.rowState), v.findViewById(R.id.rowText),
                v.findViewById(R.id.rowAction), title, on, ok, fix, open))
        }
        // « Arrière-plan » : la veille de SENTIA tourne (HyperOS : réglage « fenêtres en arrière-plan » via openAppPermissions).
        add(R.string.cfg_r_bg, R.string.cfg_on_bg, { settings.diagServiceAt != 0L && settings.diagServiceError.isEmpty() },
            { try { ShakeService.start(this) } catch (_: Exception) {}; openAppPermissions() }, { openAppPermissions() })
        add(R.string.cfg_r_overlay, R.string.cfg_on_overlay, { overlayOk() }, { openOverlaySettings() }, { openOverlaySettings() })
        add(R.string.cfg_r_a11y, R.string.cfg_on_a11y, { a11yEnabled() }, { openAccessibility() }, { openAccessibility() })
        add(R.string.cfg_r_battery, R.string.cfg_on_battery, { batteryOk() }, { openBatterySettings() }, { openBatterySettings() })
        add(R.string.cfg_r_volume, R.string.cfg_on_volume, { a11yEnabled() && settings.volumeEnabled },
            { if (!settings.volumeEnabled) settings.volumeEnabled = true; if (!a11yEnabled()) openAccessibility(); refreshRows() },
            { openAccessibility() })
    }

    /** Met à jour chaque ligne (indicateur, état, bouton) et annonce l'état aux lecteurs d'écran. Retourne les lignes manquantes. */
    private fun refreshRows(): List<Row> {
        val green = ContextCompat.getColor(this, R.color.ok_green)
        val red = ContextCompat.getColor(this, R.color.todo_red)
        val missing = mutableListOf<Row>()
        for (r in rows) {
            val ok = r.ok()
            val name = getString(r.title)
            val stateText = getString(if (ok) r.on else R.string.cfg_todo)
            r.dot.setTextColor(if (ok) green else red)
            r.state.text = stateText
            r.state.setTextColor(if (ok) green else red)
            r.text.contentDescription = "$name, $stateText"
            if (ok) {
                r.action.setText(R.string.cfg_open)
                r.action.setBackgroundResource(R.drawable.btn_outline)
                r.action.setTextColor(ContextCompat.getColor(this, R.color.yellow))
                r.action.contentDescription = getString(R.string.cfg_open_desc, name)
                r.action.setOnClickListener { r.open() }
            } else {
                missing.add(r)
                r.action.setText(R.string.cfg_activate)
                r.action.setBackgroundResource(R.drawable.btn_small_fill)
                r.action.setTextColor(ContextCompat.getColor(this, R.color.black))
                r.action.contentDescription = getString(R.string.cfg_act_desc, name)
                r.action.setOnClickListener { r.fix() }
            }
        }
        cfgSummary.text = if (missing.isEmpty()) getString(R.string.cfg_sum_ok) else getString(R.string.cfg_sum_missing, missing.size)
        cfgSummary.setTextColor(if (missing.isEmpty()) green else red)
        return missing
    }

    /** « Vérifier SENTIA » : revérifie tout et confirme que SENTIA est prête (ou liste ce qui manque). */
    private fun verify() {
        try { ShakeService.start(this) } catch (_: Exception) {}
        val missing = refreshRows()
        val msg = if (missing.isEmpty()) getString(R.string.cfg_ready)
        else getString(R.string.cfg_missing, missing.joinToString(", ") { getString(it.title) })
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        cfgSummary.announceForAccessibility(msg)
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
        s.liveMode = findViewById<CheckBox>(R.id.cbLive).isChecked
        Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
        if (oldLang != s.language) SentiaApp.applyUiLanguage(s.language)
        finish()
    }
}
