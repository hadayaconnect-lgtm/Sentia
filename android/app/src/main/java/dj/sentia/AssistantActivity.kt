package dj.sentia

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import dj.sentia.core.HapticPattern
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Écran principal : volontairement très simple. Un énorme bouton « Parler », trois boutons (caméra, écrire,
 * répéter) et les réglages. Réveillé par secousse ou touches de volume, il écoute tout de suite pour les personnes aveugles.
 */
class AssistantActivity : AppCompatActivity(), ToolHost {
    private lateinit var client: AgentClient
    private lateinit var speaker: Speaker
    private lateinit var engine: AssistantEngine
    private lateinit var speech: SpeechInput

    private lateinit var status: TextView
    private lateinit var reply: TextView
    private lateinit var writePanel: LinearLayout
    private lateinit var input: EditText

    private var job: Job? = null
    private var lastReply = ""
    private var lastSpokenLang = "" // langue détectée dans la dernière phrase prononcée (mode automatique)
    private var pendingPermission: CompletableDeferred<Boolean>? = null

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        pendingPermission?.complete(granted)
        pendingPermission = null
    }

    override val context: Context get() = this
    override val lifecycleOwner: LifecycleOwner get() = this

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (settings.needsOnboarding) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }
        if (Build.VERSION.SDK_INT < 27) {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        setContentView(R.layout.activity_assistant)
        client = AgentClient(this)
        speaker = Speaker(this, client)
        speech = SpeechInput(this)
        engine = AssistantEngine(this, client, ToolExecutor(this, settings))

        status = findViewById(R.id.status)
        reply = findViewById(R.id.reply)
        writePanel = findViewById(R.id.writePanel)
        input = findViewById(R.id.input)

        findViewById<Button>(R.id.speakBtn).setOnClickListener { startListening() }
        findViewById<Button>(R.id.cameraBtn).setOnClickListener {
            send(Lang.cameraPrompt(Lang.ui(settings.language)))
        }
        findViewById<Button>(R.id.writeBtn).setOnClickListener { toggleWrite() }
        findViewById<Button>(R.id.repeatBtn).setOnClickListener { repeatReply() }
        findViewById<Button>(R.id.settingsBtn).setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        findViewById<Button>(R.id.sendBtn).setOnClickListener {
            val t = input.text.toString().trim()
            if (t.isNotEmpty()) { input.setText(""); send(t) }
        }

        status.text = getString(R.string.ready)
        if (settings.isDeafish) writePanel.visibility = View.VISIBLE
        ensureBackgroundServices()
        handleWake(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!settings.needsOnboarding) handleWake(intent)
    }

    override fun onResume() {
        super.onResume()
        visible = true
        getSystemService(NotificationManager::class.java).cancel(Notifications.ID_WAKE)
    }

    override fun onPause() {
        visible = false
        super.onPause()
    }

    override fun onStop() {
        if (::speech.isInitialized) {
            job?.cancel()
            speech.cancel()
            speaker.stop()
        }
        super.onStop()
    }

    override fun onDestroy() {
        if (::speaker.isInitialized) speaker.shutdown()
        super.onDestroy()
    }

    // ---- Réveil -------------------------------------------------------------------------------------------

    private fun handleWake(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_WAKE, false) != true) return
        intent.removeExtra(EXTRA_WAKE)
        job?.cancel()
        job = lifecycleScope.launch {
            if (settings.isDeafish) {
                // Vibration « réveillé » déjà faite par WakeController. Écran lisible, pas d'écoute automatique.
                status.text = getString(R.string.greeting)
            } else {
                status.text = getString(R.string.greeting)
                if (settings.voiceReplies || settings.isBlindish) speaker.say(getString(R.string.greeting), Lang.ui(settings.language))
                if (settings.isBlindish) listenAndRespond()
            }
        }
    }

    private fun ensureBackgroundServices() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (settings.shakeEnabled) {
            try { ShakeService.start(this) } catch (_: Exception) {}
        }
    }

    // ---- Actions --------------------------------------------------------------------------------------------

    private fun startListening() {
        job?.cancel()
        speech.cancel()
        speaker.stop()
        job = lifecycleScope.launch { listenAndRespond() }
    }

    private fun send(text: String) {
        job?.cancel()
        speech.cancel()
        speaker.stop()
        job = lifecycleScope.launch { respond(text) }
    }

    private fun toggleWrite() {
        writePanel.visibility = if (writePanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        if (writePanel.visibility == View.VISIBLE) input.requestFocus()
    }

    private fun repeatReply() {
        if (lastReply.isEmpty()) return
        job?.cancel()
        speaker.stop()
        job = lifecycleScope.launch { speakReply(lastReply) }
    }

    private suspend fun listenAndRespond() {
        if (!ensurePermission(Manifest.permission.RECORD_AUDIO)) {
            showError(getString(R.string.error_mic_permission)); return
        }
        status.text = getString(R.string.listening)
        Vibe.play(this, HapticPattern.AWAKE)
        // Laisse finir l'annonce du lecteur d'écran avant d'ouvrir le micro (sinon il s'écoute lui-même).
        kotlinx.coroutines.delay(if (isScreenReaderOn()) 900L else 150L)
        val file = speech.record()
        if (file == null) { showError(getString(R.string.error_no_speech)); return }
        status.text = getString(R.string.thinking)
        val text = try {
            val (t, lang) = withContext(Dispatchers.IO) { client.transcribe(file).also { file.delete() } }
            lastSpokenLang = lang
            t
        } catch (e: AgentException) {
            showError(messageFor(e)); return
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            showError(getString(R.string.error_generic)); return
        }
        if (text.isBlank()) { showError(getString(R.string.error_no_speech)); return }
        respond(text)
    }

    private suspend fun respond(text: String) {
        status.text = getString(R.string.thinking)
        reply.text = ""
        try {
            val answer = engine.ask(text) { preface ->
                reply.text = preface
                if (settings.voiceReplies) lifecycleScope.launch { speaker.say(preface, replyLang(preface)) }
            }
            lastReply = answer
            status.text = ""
            reply.text = answer
            if (settings.isDeafish) Vibe.play(this, HapticPattern.INFO)
            speakReply(answer)
        } catch (e: AgentException) {
            showError(messageFor(e))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            showError(getString(R.string.error_generic))
        }
    }

    private suspend fun speakReply(text: String) {
        if (settings.voiceReplies) speaker.say(text, replyLang(text))
        else reply.announceForAccessibility(text)
    }

    private fun replyLang(text: String): String = when {
        settings.language in Lang.ALL -> settings.language
        lastSpokenLang in Lang.ALL -> lastSpokenLang
        else -> Lang.guess(text)
    }

    private fun showError(message: String) {
        status.text = message
        if (settings.voiceReplies && !settings.isDeafish) lifecycleScope.launch { speaker.say(message, Lang.ui(settings.language)) }
        if (settings.isDeafish) Vibe.play(this, HapticPattern.ATTENTION)
    }

    private fun messageFor(e: AgentException): String = getString(
        when (e.kind) {
            AgentException.Kind.NETWORK -> R.string.error_network
            AgentException.Kind.CONFIG -> R.string.error_server_config
            else -> R.string.error_generic
        }
    )

    private fun isScreenReaderOn(): Boolean =
        (getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager).isTouchExplorationEnabled

    // ---- ToolHost ---------------------------------------------------------------------------------------------

    override suspend fun ensurePermission(permission: String): Boolean {
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) return true
        val d = CompletableDeferred<Boolean>()
        pendingPermission = d
        permissionLauncher.launch(permission)
        return d.await()
    }

    override suspend fun confirm(title: String, message: String): Boolean = suspendCancellableCoroutine { cont ->
        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton(R.string.yes) { _, _ -> if (cont.isActive) cont.resume(true) }
            .setNegativeButton(R.string.no) { _, _ -> if (cont.isActive) cont.resume(false) }
            .create()
        dialog.show()
        cont.invokeOnCancellation { dialog.dismiss() }
        if (settings.voiceReplies) lifecycleScope.launch { speaker.say("$title $message", replyLang(message)) }
    }

    companion object {
        const val EXTRA_WAKE = "wake"
        const val EXTRA_SOURCE = "source"
        @Volatile var visible = false
    }
}
