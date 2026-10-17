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
    private lateinit var diagState: TextView
    private lateinit var diagStateSub: TextView
    private lateinit var diagPerfBody: TextView
    private lateinit var diagSysBody: TextView
    private lateinit var diagTech: TextView
    private lateinit var btnTech: Button
    private var techOpen = false
    private class Row(
        val dot: TextView, val state: TextView, val text: View, val action: Button,
        val title: Int, val on: Int, val ok: () -> Boolean, val fix: () -> Unit, val open: () -> Unit,
    )
    private val rows = mutableListOf<Row>()
    private lateinit var cfgSummary: TextView
    private val ui = Handler(Looper.getMainLooper())
    private val refresher = object : Runnable {
        override fun run() { refreshDiag(refreshRows()); ui.postDelayed(this, 1500) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        title = getString(R.string.settings_title)

        spLanguage = findViewById(R.id.spLanguage)
        etCode = findViewById(R.id.etCode)
        diagState = findViewById(R.id.diagState)
        diagStateSub = findViewById(R.id.diagStateSub)
        diagPerfBody = findViewById(R.id.diagPerfBody)
        diagSysBody = findViewById(R.id.diagSysBody)
        diagTech = findViewById(R.id.diagTech)
        btnTech = findViewById(R.id.btnTech)
        btnTech.setOnClickListener { techOpen = !techOpen; applyTech() }
        applyTech()

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
        refreshDiag(missing)
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

    private fun applyTech() {
        val label = getString(R.string.dg_tech)
        btnTech.text = label + if (techOpen) "  ▾" else "  ▸"
        btnTech.contentDescription = label + ", " + if (techOpen) "déplié" else "replié"
        diagTech.visibility = if (techOpen) View.VISIBLE else View.GONE
    }

    /**
     * Diagnostic : état global (prêt / configuration nécessaire / erreur), puis performances et système lisibles.
     * Les valeurs viennent des mêmes données qu'avant (rapport enregistré par la conversation, états Android) ;
     * les libellés très techniques restent intégralement dans « Détails techniques ».
     */
    private fun refreshDiag(missing: List<Row>) {
        val s = settings
        val f = java.text.SimpleDateFormat("dd/MM HH:mm:ss", java.util.Locale.getDefault())
        fun at(t: Long) = if (t == 0L) "jamais" else f.format(java.util.Date(t))
        val overlay = overlayOk()
        val battery = batteryOk()
        val a11y = a11yEnabled()
        val perf = s.diagPerf
        val lines = perf.lines().map { it.trim() }.filter { it.isNotEmpty() }
        fun value(match: (String) -> Boolean) =
            lines.firstOrNull(match)?.substringAfterLast(" : ", "")?.trim()?.takeIf { it.isNotEmpty() }
        val connexion = value { it.startsWith("Connexion") }
        val photo = value { it.contains("→ photo envoyée") }
        val voix = value { it.contains("début de la voix") }
        val echo = value { it.startsWith("Annulation d'écho") }
        val liveError = perf.startsWith("Conversation en direct indisponible")
        val hasError = s.diagServiceError.isNotEmpty() || liveError

        // 1) État global
        val green = ContextCompat.getColor(this, R.color.ok_green)
        val red = ContextCompat.getColor(this, R.color.todo_red)
        val orange = ContextCompat.getColor(this, R.color.warn_orange)
        val (dot, color, title, sub) = when {
            hasError -> Quad("🔴", red, "Une erreur empêche SENTIA de fonctionner",
                if (s.diagServiceError.isNotEmpty()) "Erreur de la veille : " + s.diagServiceError else lines.firstOrNull().orEmpty())
            missing.isNotEmpty() -> Quad("🟠", orange, "Configuration nécessaire",
                "Il manque : " + missing.joinToString(", ") { getString(it.title) } + ".")
            else -> Quad("🟢", green, "SENTIA est prêt", "Toutes les autorisations nécessaires sont activées.")
        }
        diagState.text = "$dot $title"
        diagState.setTextColor(color)
        diagState.contentDescription = "État : $title"
        diagStateSub.text = sub

        // 2) Performances (seulement ce qui a réellement été mesuré)
        val perfLines = listOfNotNull(
            connexion?.let { "• Connexion : $it" },
            photo?.let { "• Photo envoyée : $it" },
            voix?.let { "• Début de la voix : $it" },
        )
        diagPerfBody.text = if (perfLines.isEmpty()) "Aucune mesure pour le moment." else perfLines.joinToString("\n")

        // 3) Système
        diagSysBody.text = listOfNotNull(
            "• Accessibilité : " + if (a11y) "Activée" else "Désactivée",
            "• Affichage au-dessus des apps : " + if (overlay) "Autorisé" else "Non autorisé",
            "• Batterie : " + if (battery) "Sans restriction" else "Restreinte",
            echo?.let { "• Annulation d'écho : " + if (it.startsWith("oui")) "Activée" else "Désactivée" },
        ).joinToString("\n")

        // 4) Détails techniques (données brutes d'origine, rien n'est supprimé)
        diagTech.text = buildString {
            append("• Veille démarrée : ").append(at(s.diagServiceAt)).append('\n')
            if (s.diagServiceError.isNotEmpty()) append("• Erreur de la veille : ").append(s.diagServiceError).append('\n')
            append("• Dernier réveil : ").append(s.diagWake.ifEmpty { "aucun" }).append('\n')
            append("• Dernière analyse : ").append(if (perf.isEmpty()) "aucune" else "\n" + perf)
        }
    }

    private data class Quad(val a: String, val b: Int, val c: String, val d: String)

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
