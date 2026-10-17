package dj.sentia

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/** Liste écrite des autorisations : état de chacune (✅ / ❌) et un bouton qui ouvre le bon réglage. Sans voix. */
class PermissionsActivity : AppCompatActivity() {
    private class Row(val text: TextView, val btn: Button, val name: Int, val ok: () -> Boolean, val fix: () -> Unit)

    private val rows = mutableListOf<Row>()
    private val runtime = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_permissions)
        fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
        fun add(textId: Int, btnId: Int, name: Int, ok: () -> Boolean, fix: () -> Unit) =
            rows.add(Row(findViewById(textId), findViewById(btnId), name, ok, fix))

        add(R.id.pgMicText, R.id.pgMicBtn, R.string.pg_mic, { granted(Manifest.permission.RECORD_AUDIO) }) { runtime.launch(Manifest.permission.RECORD_AUDIO) }
        add(R.id.pgCamText, R.id.pgCamBtn, R.string.pg_cam, { granted(Manifest.permission.CAMERA) }) { runtime.launch(Manifest.permission.CAMERA) }
        add(R.id.pgNotifText, R.id.pgNotifBtn, R.string.pg_notif,
            { Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS) }) {
            if (Build.VERSION.SDK_INT >= 33) runtime.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        add(R.id.pgA11yText, R.id.pgA11yBtn, R.string.pg_a11y, {
            (getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager)
                .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_GENERIC)
                .any { it.resolveInfo.serviceInfo.packageName == packageName }
        }) { startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS)) }
        add(R.id.pgOverlayText, R.id.pgOverlayBtn, R.string.pg_overlay,
            { Build.VERSION.SDK_INT < 23 || AndroidSettings.canDrawOverlays(this) }) {
            try { startActivity(Intent(AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
            catch (_: Exception) { startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
        }
        add(R.id.pgBatteryText, R.id.pgBatteryBtn, R.string.pg_battery,
            { (getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName) }) {
            try { startActivity(Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) }
            catch (_: Exception) { startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
        rows.forEach { r -> r.btn.setOnClickListener { r.fix() } }
        findViewById<Button>(R.id.pgDone).setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        for (r in rows) {
            val ok = r.ok()
            r.text.text = (if (ok) "✅ " else "❌ ") + getString(r.name) + " : " + getString(if (ok) R.string.pg_ok else R.string.pg_todo)
            r.btn.visibility = if (ok) View.GONE else View.VISIBLE
        }
    }
}
