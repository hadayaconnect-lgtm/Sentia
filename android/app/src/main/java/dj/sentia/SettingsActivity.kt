package dj.sentia

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import dj.sentia.core.HapticPattern
import dj.sentia.core.ShakeDetector

class SettingsActivity : AppCompatActivity(), SensorEventListener {
    private val languages = listOf("auto", "fr", "en", "so", "ar")
    private val profiles = listOf("blind", "deaf", "both", "other")

    private lateinit var spLanguage: Spinner
    private lateinit var spProfile: Spinner
    private lateinit var spSensitivity: Spinner
    private lateinit var swShake: SwitchCompat
    private lateinit var swVolume: SwitchCompat
    private lateinit var swVoice: SwitchCompat
    private lateinit var swSounds: SwitchCompat
    private lateinit var etCode: EditText
    private lateinit var testStatus: TextView

    private var testDetector: ShakeDetector? = null

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { applyAndFinish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        title = getString(R.string.settings_title)

        spLanguage = findViewById(R.id.spLanguage)
        spProfile = findViewById(R.id.spProfile)
        spSensitivity = findViewById(R.id.spSensitivity)
        swShake = findViewById(R.id.swShake)
        swVolume = findViewById(R.id.swVolume)
        swVoice = findViewById(R.id.swVoice)
        swSounds = findViewById(R.id.swSounds)
        etCode = findViewById(R.id.etCode)
        testStatus = findViewById(R.id.testStatus)

        spLanguage.adapter = adapter(listOf(R.string.lang_auto, R.string.lang_fr, R.string.lang_en, R.string.lang_so, R.string.lang_ar))
        spProfile.adapter = adapter(listOf(R.string.profile_blind, R.string.profile_deaf, R.string.profile_both, R.string.profile_other))
        spSensitivity.adapter = adapter(listOf(R.string.sens_low, R.string.sens_medium, R.string.sens_high))

        val s = settings
        spLanguage.setSelection(languages.indexOf(s.language).coerceAtLeast(0))
        spProfile.setSelection(profiles.indexOf(s.profile).coerceAtLeast(0))
        spSensitivity.setSelection(s.sensitivity)
        swShake.isChecked = s.shakeEnabled
        swVolume.isChecked = s.volumeEnabled
        swVoice.isChecked = s.voiceReplies
        swSounds.isChecked = s.soundsEnabled
        etCode.setText(s.accessCode)

        findViewById<Button>(R.id.btnBattery).setOnClickListener { openBatterySettings() }
        findViewById<Button>(R.id.btnA11y).setOnClickListener { startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS)) }
        findViewById<Button>(R.id.btnTest).setOnClickListener { startTest() }
        findViewById<Button>(R.id.btnSave).setOnClickListener { save() }
    }

    private fun adapter(items: List<Int>) =
        ArrayAdapter(this, android.R.layout.simple_spinner_item, items.map { getString(it) }).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

    // ---- Test de la secousse (sans service, directement ici) -------------------------------------------------------

    private fun startTest() {
        val sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        val config = when (spSensitivity.selectedItemPosition) {
            0 -> ShakeDetector.Config.LOW
            2 -> ShakeDetector.Config.HIGH
            else -> ShakeDetector.Config.MEDIUM
        }
        testDetector = ShakeDetector(config)
        sm.unregisterListener(this)
        sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
        testStatus.text = getString(R.string.test_hint)
    }

    override fun onSensorChanged(e: SensorEvent) {
        val d = testDetector ?: return
        if (d.onSample(e.timestamp / 1_000_000L, e.values[0], e.values[1], e.values[2])) {
            testStatus.text = getString(R.string.test_ok)
            Vibe.play(this, HapticPattern.AWAKE)
            (getSystemService(Context.SENSOR_SERVICE) as SensorManager).unregisterListener(this)
            testDetector = null
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onPause() {
        (getSystemService(Context.SENSOR_SERVICE) as SensorManager).unregisterListener(this)
        super.onPause()
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

    // ---- Enregistrement -------------------------------------------------------------------------------------------------

    private fun save() {
        val needed = ArrayList<String>()
        if (swShake.isChecked && Build.VERSION.SDK_INT >= 29 && !granted(Manifest.permission.ACTIVITY_RECOGNITION)) needed.add(Manifest.permission.ACTIVITY_RECOGNITION)
        if (swSounds.isChecked && !granted(Manifest.permission.RECORD_AUDIO)) needed.add(Manifest.permission.RECORD_AUDIO)
        if (needed.isEmpty()) applyAndFinish() else permissions.launch(needed.toTypedArray())
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun applyAndFinish() {
        val s = settings
        val oldLang = s.language
        s.language = languages[spLanguage.selectedItemPosition]
        s.profile = profiles[spProfile.selectedItemPosition]
        s.sensitivity = spSensitivity.selectedItemPosition
        s.shakeEnabled = swShake.isChecked
        s.volumeEnabled = swVolume.isChecked
        s.voiceReplies = swVoice.isChecked
        s.accessCode = etCode.text.toString().trim()

        var sounds = swSounds.isChecked && granted(Manifest.permission.RECORD_AUDIO)
        if (sounds && !SoundService.modelInstalled(this)) {
            Toast.makeText(this, R.string.sounds_model_missing, Toast.LENGTH_LONG).show()
            sounds = false
        }
        s.soundsEnabled = sounds

        try { if (s.shakeEnabled) ShakeService.start(this) else ShakeService.stop(this) } catch (_: Exception) {}
        try { if (sounds) SoundService.start(this) else SoundService.stop(this) } catch (_: Exception) {}

        Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
        if (oldLang != s.language) SentiaApp.applyUiLanguage(s.language)
        finish()
    }
}
