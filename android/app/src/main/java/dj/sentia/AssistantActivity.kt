package dj.sentia

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.util.TypedValue
import android.view.Gravity
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
import androidx.core.view.ViewCompat
import androidx.core.widget.TextViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import dj.sentia.core.HapticPattern
import dj.sentia.core.VoiceCommand
import dj.sentia.core.VoiceCommands
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/**
 * Écran principal : SENTIA se présente comme un assistant IA (présent, disponible) et propose quatre possibilités :
 * voir, parler, écrire, comprendre les sons. L'ordre et la taille des boutons dépendent du profil choisi.
 *
 * Conversation : question → analyse → réponse (à voix haute selon le profil) → SENTIA attend la question suivante.
 * La personne peut couper la parole à SENTIA (« Stop ») ; les commandes courtes sont reconnues localement.
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
    private lateinit var actions: LinearLayout
    private lateinit var identSubtitle: TextView
    private lateinit var hintShake: TextView
    private lateinit var hintVolume: TextView
    private lateinit var orb: OrbView

    private var job: Job? = null
    private var lastReply = ""
    private var lastSpokenLang = "" // langue détectée dans la dernière phrase prononcée (mode automatique)
    private var pendingPermission: CompletableDeferred<Boolean>? = null
    private var builtForProfile: String? = null
    private var keyboardOpen = false
    /** Les personnes aveugles / malvoyantes entendent toujours les réponses, quel que soit le réglage « réponses vocales ». */
    private val voiceOn: Boolean get() = settings.voiceReplies || settings.isBlindish

    private var greetJob: Job? = null // salut en cours de lecture pendant l'analyse de la première photo

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
        actions = findViewById(R.id.actions)
        identSubtitle = findViewById(R.id.identSubtitle)
        hintShake = findViewById(R.id.hintShake)
        hintVolume = findViewById(R.id.hintVolume)
        orb = findViewById(R.id.orb)

        val title = findViewById<TextView>(R.id.identTitle)
        title.text = "🧠 SENTIA"
        title.contentDescription = "SENTIA"

        val repeatBtn = findViewById<Button>(R.id.repeatBtn)
        val stopBtn = findViewById<Button>(R.id.stopBtn)
        val settingsBtn = findViewById<Button>(R.id.settingsBtn)
        for ((b, icon) in listOf(repeatBtn to "🔁", stopBtn to "⏹", settingsBtn to "⚙️")) {
            val label = b.text.toString()
            b.contentDescription = label
            b.text = "$icon\n$label" // icône au-dessus du texte : rien n'est coupé, même sur un petit écran
            fitText(b, 11, 16)
        }
        repeatBtn.setOnClickListener { onRepeatButton() }
        stopBtn.setOnClickListener { onStopButton() }
        settingsBtn.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        findViewById<Button>(R.id.sendBtn).setOnClickListener {
            val t = input.text.toString().trim()
            if (t.isNotEmpty()) { input.setText(""); onTyped(t) }
        }

        // Quand le clavier est ouvert, on libère de la place (titre et rappels masqués).
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { v, insets ->
            val ime = insets.isVisible(WindowInsetsCompat.Type.ime())
            if (ime != keyboardOpen) { keyboardOpen = ime; refreshHints() }
            insets
        }

        status.text = getString(R.string.ready)
        ensureBackgroundServices()
        if (savedInstanceState == null) markLauncherOpenAsWake(intent)
        handleWake(intent)
    }

    /** Profil aveugle : ouvrir l'application depuis l'icône = réveil (SENTIA regarde et décrit tout de suite). */
    private fun markLauncherOpenAsWake(i: Intent?) {
        if (i == null || !settings.isBlindish) return
        if (i.action == Intent.ACTION_MAIN && i.hasCategory(Intent.CATEGORY_LAUNCHER) && !i.hasExtra(EXTRA_SOURCE)) {
            i.putExtra(EXTRA_WAKE, true).putExtra(EXTRA_SOURCE, "open")
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        markLauncherOpenAsWake(intent)
        if (::speaker.isInitialized && !settings.needsOnboarding) handleWake(intent)
    }

    override fun onResume() {
        super.onResume()
        if (!::speech.isInitialized) return
        visible = true
        getSystemService(NotificationManager::class.java).cancel(Notifications.ID_WAKE)
        // La veille de la secousse peut avoir été arrêtée par le téléphone : on la relance à chaque ouverture.
        try { ShakeService.start(this) } catch (_: Exception) {}
        // Le profil ou les gestes ont pu changer dans les réglages.
        applyOrbLayout()
        buildActions()
        refreshHints()
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

    // ---- Interface adaptée au profil ----------------------------------------------------------------------------

    private fun refreshHints() {
        val s = settings
        hintShake.text = "🫨 " + getString(R.string.hint_shake)
        hintVolume.text = "🔊 + 🔉 " + getString(R.string.hint_volume)
        val show = !keyboardOpen
        // Profil aveugle : l'écran d'accueil est la boule bleue seule (pas de boutons ni de rappels écrits).
        val blind = s.profile == "blind"
        hintShake.visibility = if (show && !blind && s.shakeEnabled) View.VISIBLE else View.GONE
        hintVolume.visibility = if (show && !blind && s.volumeEnabled) View.VISIBLE else View.GONE
        identSubtitle.visibility = if (show && !blind) View.VISIBLE else View.GONE
        orb.visibility = if (show) View.VISIBLE else View.GONE
        actions.visibility = if (show && !blind) View.VISIBLE else View.GONE
        // Les personnes sourdes ont le champ d'écriture toujours sous les yeux.
        if (s.isDeafish) writePanel.visibility = View.VISIBLE
        else if (s.profile == "blind") writePanel.visibility = View.GONE // pas de clavier dans le parcours aveugle
    }

    /** Profil aveugle : la boule occupe l'écran, touchable (deux fois avec TalkBack) pour que SENTIA regarde. */
    private fun applyOrbLayout() {
        val blind = settings.profile == "blind"
        orb.layoutParams = if (blind) LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 3f)
        else LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(150))
        if (blind) {
            orb.contentDescription = getString(R.string.orb_desc)
            orb.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            orb.setOnClickListener { onSeeButton() }
        } else {
            orb.contentDescription = null
            orb.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            orb.setOnClickListener(null)
            orb.isClickable = false
        }
    }

    private fun bigButton(label: String, description: String, weight: Float, onClick: () -> Unit): Button {
        val b = Button(this, null, 0, R.style.Sentia_BigButton)
        b.text = label
        b.contentDescription = description
        b.setOnClickListener { onClick() }
        b.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, weight).apply { topMargin = dp(8) }
        return b
    }

    private fun rowOf(first: Button, second: Button, weight: Float): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, weight).apply { topMargin = dp(8) }
        for ((i, b) in listOf(first, second).withIndex()) {
            (b.parent as? LinearLayout)?.removeView(b)
            b.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply { if (i == 1) marginStart = dp(8) }
            fitText(b, 12, 18)
            row.addView(b)
        }
        return row
    }

    /** Le texte se réduit tout seul pour tenir dans le bouton (jamais coupé), sans devenir illisible. */
    private fun fitText(b: Button, minSp: Int, maxSp: Int) {
        TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(b, minSp, maxSp, 1, TypedValue.COMPLEX_UNIT_SP)
        b.gravity = Gravity.CENTER
        b.setPadding(dp(6), dp(6), dp(6), dp(6))
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /**
     * Aveugle / malvoyant : Parler et Voir en grand. Sourd : Écrire en grand, Parler passe au second plan.
     * Aveugle + sourd : Écrire et Voir en grand (toucher, texte, vibration). Autre : Parler et Voir.
     */
    private fun buildActions() {
        val profile = settings.profile
        val key = profile + "/" + settings.soundsEnabled
        if (builtForProfile == key && actions.childCount > 0) return
        builtForProfile = key
        actions.removeAllViews()

        val speak = bigButton("🗣️ " + getString(R.string.btn_speak), getString(R.string.btn_speak), 2f) { onSpeakButton() }
        val write = bigButton("✍️ " + getString(R.string.btn_write), getString(R.string.btn_write), 2f) { toggleWrite() }
        val sounds = bigButton("🔊 " + getString(R.string.btn_sounds), getString(R.string.btn_sounds), 1f) { onSoundsButton() }
        val seeLabel = SpannableString("📷 " + getString(R.string.btn_see) + "\n" + getString(R.string.see_hint)).also {
            val start = it.indexOf('\n') + 1
            it.setSpan(RelativeSizeSpan(0.65f), start, it.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val see = bigButton("", getString(R.string.btn_see) + ". " + getString(R.string.see_hint), 2f) { onSeeButton() }
        see.text = seeLabel

        when (profile) {
            "blind" -> {
                // Écran minimal : deux grands boutons, ni clavier ni champ d'écriture. L'action principale est la secousse.
                (see.layoutParams as LinearLayout.LayoutParams).weight = 3f
                actions.addView(see); actions.addView(speak)
                if (settings.soundsEnabled) {
                    sounds.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(8) }
                    actions.addView(sounds)
                }
            }
            "deaf" -> {
                write.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 2f).apply { topMargin = dp(8) }
                (see.layoutParams as LinearLayout.LayoutParams).weight = 1.5f
                actions.addView(write); actions.addView(see)
                actions.addView(rowOf(speak, sounds, 1f))
            }
            "both" -> {
                actions.addView(write); actions.addView(see)
                actions.addView(rowOf(speak, sounds, 1f))
            }
            else -> {
                (see.layoutParams as LinearLayout.LayoutParams).weight = 1.5f
                actions.addView(speak); actions.addView(see)
                actions.addView(rowOf(write, sounds, 1f))
            }
        }
    }

    // ---- Réveil -------------------------------------------------------------------------------------------

    private fun handleWake(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_WAKE, false) != true) return
        intent?.removeExtra(EXTRA_WAKE)
        val voice = !settings.isDeafish
        orb.wake() // la boule bat plus vivement ~2 s (la vibration de réveil est déjà faite par WakeController)

        // Nouvelle secousse pendant que SENTIA analyse ou parle : on arrête tout et SENTIA écoute la personne.
        if (job?.isActive == true) {
            if (orb.mode == OrbView.Mode.LISTENING) return // elle écoute déjà
            val listenMsg = getString(R.string.wake_listen)
            status.text = listenMsg
            launchNew {
                if (voice && !ensurePermission(Manifest.permission.RECORD_AUDIO)) { showError(getString(R.string.error_mic_permission)); return@launchNew }
                conversation(greet = listenMsg, voice = voice)
            }
            return
        }

        // Première secousse : « Sentia, regarde devant moi et dis-moi ce que tu vois. »
        val inSession = engine.conversation.messages.length() > 0
        val hello = getString(if (inSession) R.string.wake_here else R.string.wake_hello)
        val spokenIntro = hello + " " + getString(R.string.wait_moment)
        status.text = spokenIntro
        launchNew {
            if (voice && !ensurePermission(Manifest.permission.RECORD_AUDIO)) { showError(getString(R.string.error_mic_permission)); return@launchNew }
            // Le salut et « Attendez un instant » sont dits pendant que la photo est prise et analysée : pas de silence d'attente.
            if (voice || voiceOn) {
                greetJob = lifecycleScope.launch(start = CoroutineStart.UNDISPATCHED) { sayChecked(spokenIntro, replyLang(spokenIntro)) }
            }
            conversation(first = Lang.cameraPrompt(Lang.ui(settings.language)), voice = voice)
        }
    }

    private fun ensureBackgroundServices() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        try { ShakeService.start(this) } catch (_: Exception) {}
    }

    // ---- Boutons --------------------------------------------------------------------------------------------

    /** Coupe tout ce qui est en cours (voix, micro, requête) puis lance le nouveau travail. */
    private fun launchNew(block: suspend () -> Unit) {
        orb.mode = OrbView.Mode.IDLE // la boule arrête son animation de parole tout de suite (Stop, nouveau bouton…)
        speaker.interrupt() // d'abord : retient ce qu'il restait à dire pour « Continue »
        speech.cancel()
        job?.cancel()
        job = lifecycleScope.launch { try { block() } finally { orb.mode = OrbView.Mode.IDLE } }
    }

    private fun onSpeakButton() = launchNew {
        if (!ensurePermission(Manifest.permission.RECORD_AUDIO)) { showError(getString(R.string.error_mic_permission)); return@launchNew }
        conversation(voice = true, firstListenDelay = true)
    }

    /** « Voir avec SENTIA » : comme la secousse, SENTIA regarde tout de suite, décrit à voix haute, puis écoute. */
    private fun onSeeButton() = launchNew {
        status.text = getString(R.string.see_hint)
        val voice = !settings.isDeafish
        if (voice && !ensurePermission(Manifest.permission.RECORD_AUDIO)) { showError(getString(R.string.error_mic_permission)); return@launchNew }
        if (voice || voiceOn) {
            val wait = getString(R.string.wait_moment)
            greetJob = lifecycleScope.launch(start = CoroutineStart.UNDISPATCHED) { sayChecked(wait, replyLang(wait)) }
        }
        conversation(first = Lang.cameraPrompt(Lang.ui(settings.language)), voice = voice)
    }

    private fun onSoundsButton() {
        if (!settings.soundsEnabled) {
            AlertDialog.Builder(this)
                .setTitle(R.string.sounds_off_title)
                .setMessage(R.string.sounds_off_msg)
                .setPositiveButton(R.string.settings_button) { _, _ -> startActivity(Intent(this, SettingsActivity::class.java)) }
                .setNegativeButton(R.string.cancel_button, null)
                .show()
            return
        }
        launchNew { conversation(first = Lang.soundsPrompt(Lang.ui(settings.language)), voice = !settings.isDeafish) }
    }

    private fun toggleWrite() {
        if (settings.isDeafish) { input.requestFocus(); return }
        writePanel.visibility = if (writePanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        if (writePanel.visibility == View.VISIBLE) input.requestFocus()
    }

    private fun onTyped(text: String) = launchNew { conversation(first = text, voice = false) }

    private fun onStopButton() = launchNew { conversation(command = VoiceCommand.STOP, voice = false) } // Stop = on coupe tout, le micro ne se rouvre pas

    private fun onRepeatButton() = launchNew { conversation(command = VoiceCommand.REPEAT, voice = false) }

    // ---- Conversation -------------------------------------------------------------------------------------------

    /**
     * Boucle : (réponse → écoute → réponse …) tant que `voice` est vrai. Sans voix, un seul tour puis SENTIA attend.
     * @param first texte à traiter tout de suite (clavier, bouton)
     * @param command commande déjà reconnue (boutons Stop, Répète)
     * @param heard enregistrement déjà capté (la personne a parlé pendant que SENTIA parlait)
     * @param greet phrase d'accueil dite d'abord (réveil)
     */
    private suspend fun conversation(
        first: String? = null,
        command: VoiceCommand? = null,
        heard: File? = null,
        greet: String? = null,
        voice: Boolean,
        firstListenDelay: Boolean = false,
    ) {
        var text = first
        var cmd = command
        var file = heard
        var listened = heard != null // vrai quand l'écoute de ce tour a déjà eu lieu (file vide = silence)
        var settle = firstListenDelay
        var failures = 0
        var retryAsked = false
        var turns = 0
        val lang = Lang.ui(settings.language)

        if (greet != null) {
            status.text = greet
            file = speakWithListening(greet, speak = true, listen = voice, timeoutMs = NO_SPEECH_MS)
            listened = voice
            if (!voice) return
        }

        while (true) {
            // 1. Obtenir la prochaine demande : déjà là, ou à écouter.
            if (text == null && cmd == null) {
                if (!voice) return
                var f = file
                file = null
                if (f == null && !listened) {
                    f = listenOnce(settle)
                    settle = false
                }
                listened = false
                val heardText = if (f == null) "" else (transcribe(f) ?: return)
                if (VoiceCommands.isNoise(heardText)) {
                    // Silence après une réponse (ou après l'accueil) : SENTIA attend tranquillement, sans message d'erreur.
                    if (turns > 0 || retryAsked || greet != null) {
                        status.text = getString(R.string.stay_available)
                        return
                    }
                    if (++failures >= 2) { showNotUnderstood(askRetry = false); return }
                    showNotUnderstood(askRetry = true)
                    retryAsked = true
                    // La question est dite (ou affichée) ; on écoute la réponse tout de suite.
                    file = speakWithListening(
                        getString(R.string.error_no_speech),
                        speak = voiceOn && !settings.isDeafish, listen = true, timeoutMs = NO_SPEECH_MS,
                    )
                    listened = true
                    continue
                }
                if (retryAsked && VoiceCommands.isRetry(heardText)) { retryAsked = false; continue }
                retryAsked = false
                failures = 0
                cmd = VoiceCommands.parse(heardText)
                if (cmd == null) text = heardText
            }

            // 2. Traiter. Chaque cas renvoie ce que la personne a dit pendant ou après (null = silence), ou ENDED.
            val c = cmd
            val t = text
            cmd = null
            text = null
            file = if (c != null) runCommand(c, lang, voice) else answer(t ?: "", voice)
            if (file === ENDED) return
            turns++
            listened = true
        }
    }

    /** Pose une question à l'IA, affiche et dit la réponse. Renvoie ce que la personne a dit pendant ce temps, ou ENDED en cas d'erreur. */
    private suspend fun answer(prompt: String, voice: Boolean): File? {
        speaker.takeRemainder() // une nouvelle question remplace ce qu'il restait à dire
        status.text = getString(R.string.thinking)
        reply.text = ""
        // Demande de regarder : photo + analyse en 10 secondes MAXIMUM, puis réponse dite à voix haute.
        val look = isLookPrompt(prompt)
        orb.mode = if (look) OrbView.Mode.ANALYZING_CAMERA else OrbView.Mode.THINKING
        val onPreface: (String) -> Unit = { preface ->
            reply.text = preface
            if (voiceOn && greetJob?.isActive != true) lifecycleScope.launch { speaker.say(preface, replyLang(preface)) }
        }
        val answer = try {
            if (look) engine.askLook(prompt, onPreface) else engine.ask(prompt, onPreface)
        } catch (e: AgentException) {
            if (e.kind == AgentException.Kind.TIMEOUT) getString(R.string.analysis_failed) // 10 s écoulées : message dit à voix haute
            else { showError(messageFor(e), detailFor(e)); return ENDED }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            showError(getString(R.string.error_generic)); return ENDED
        }
        greetJob?.join() // le salut finit avant la description
        greetJob = null
        lastReply = answer
        status.text = ""
        reply.text = answer
        if (settings.isDeafish) Vibe.play(this, HapticPattern.INFO)
        return deliver(answer, voice)
    }

    private fun isLookPrompt(p: String): Boolean {
        val l = Lang.ui(settings.language)
        return p == Lang.cameraPrompt(l) || p == Lang.lookAgainPrompt(l) || p == Lang.readTextPrompt(l) ||
            p == Lang.describeScenePrompt(l) || p == Lang.banknotePrompt(l)
    }

    /** Dit la réponse (voix prioritaire pour les profils aveugle / malvoyant) et, en mode voix, écoute la suite. */
    private suspend fun deliver(text: String, voice: Boolean): File? {
        if (voiceOn) return speakWithListening(text, speak = true, listen = voice, timeoutMs = NO_SPEECH_MS)
        reply.announceForAccessibility(text)
        return if (voice) listenOnce(false) else null
    }

    /** @return l'enregistrement suivant, null s'il n'y en a pas, ENDED pour terminer la conversation. */
    private suspend fun runCommand(c: VoiceCommand, lang: String, voice: Boolean): File? {
        when (c) {
            VoiceCommand.STOP -> {
                speaker.interrupt()
                status.text = getString(R.string.ok_stopped)
                return if (voice) listenOnce(false) else ENDED
            }
            VoiceCommand.PAUSE -> {
                speaker.interrupt()
                status.text = getString(R.string.paused)
                return if (voice) (listenOnce(false, PAUSE_WAIT_MS) ?: ENDED) else ENDED
            }
            VoiceCommand.CONTINUE -> {
                val rest = speaker.takeRemainder()
                if (rest != null) return speakWithListening(rest, speak = voiceOn, listen = voice, timeoutMs = NO_SPEECH_MS)
                return answer(Lang.continuePrompt(lang), voice)
            }
            VoiceCommand.REPEAT -> {
                speaker.takeRemainder()
                if (lastReply.isEmpty()) return if (voice) listenOnce(false) else ENDED
                reply.text = lastReply
                if (!voiceOn) { reply.announceForAccessibility(lastReply); return if (voice) listenOnce(false) else ENDED }
                return speakWithListening(lastReply, speak = true, listen = voice, timeoutMs = NO_SPEECH_MS)
            }
            VoiceCommand.LOOK_AGAIN -> return answer(Lang.lookAgainPrompt(lang), voice)
            VoiceCommand.READ_TEXT -> return answer(Lang.readTextPrompt(lang), voice)
            VoiceCommand.DESCRIBE_SCENE -> return answer(Lang.describeScenePrompt(lang), voice)
            VoiceCommand.BANKNOTE -> return answer(Lang.banknotePrompt(lang), voice)
            VoiceCommand.CLOSE_CAMERA -> {
                engine.conversation.dropImages()
                val msg = getString(R.string.camera_closed)
                status.text = msg
                return speakWithListening(msg, speak = voiceOn, listen = voice, timeoutMs = NO_SPEECH_MS)
            }
        }
        @Suppress("UNREACHABLE_CODE")
        return null
    }

    // ---- Voix et micro --------------------------------------------------------------------------------------------

    /**
     * Dit le texte. Avec la coupure de parole (« barge-in »), le micro écoute pendant que SENTIA parle : si la personne
     * parle, la voix s'arrête tout de suite et on renvoie ce qu'elle dit. Sinon, une fois la phrase finie, on écoute
     * normalement (si `listen`). Renvoie l'enregistrement ou null.
     */
    private suspend fun speakWithListening(text: String, speak: Boolean, listen: Boolean, timeoutMs: Long): File? {
        val lang = replyLang(text)
        if (!speak) return if (listen) listenOnce(false, timeoutMs) else null
        if (!listen) { sayChecked(text, lang); return null }
        if (!settings.bargeIn) {
            sayChecked(text, lang)
            return listenOnce(true, timeoutMs, afterSpeech = true)
        }
        return coroutineScope {
            // UNDISPATCHED : la voix est marquée « en cours » avant que le micro n'ouvre.
            val talking = launch(start = CoroutineStart.UNDISPATCHED) { sayChecked(text, lang); orb.mode = OrbView.Mode.LISTENING }
            status.text = getString(R.string.listening)
            val f = speech.record(gate = { speaker.speaking }, onSpeechStart = { speaker.interrupt() }, noSpeechTimeoutMs = timeoutMs)
            talking.cancel()
            f
        }
    }

    private suspend fun sayChecked(text: String, lang: String) {
        orb.mode = OrbView.Mode.SPEAKING
        val ok = try { speaker.say(text, lang) } finally { orb.mode = OrbView.Mode.IDLE }
        if (!ok) status.text = getString(R.string.error_no_voice) + (speaker.lastError?.let { " ($it)" } ?: "")
    }

    /** Écoute normale (micro ouvert, on attend la parole). */
    private suspend fun listenOnce(settle: Boolean, timeoutMs: Long = NO_SPEECH_MS, afterSpeech: Boolean = false): File? {
        status.text = getString(R.string.listening)
        orb.mode = OrbView.Mode.LISTENING
        if (!afterSpeech) Vibe.play(this, HapticPattern.AWAKE)
        // Laisse finir l'annonce du lecteur d'écran / la fin de la voix avant d'ouvrir le micro (sinon il s'écoute lui-même).
        delay(if (settle && isScreenReaderOn()) 900L else if (afterSpeech) 350L else 150L)
        val f = speech.record(noSpeechTimeoutMs = timeoutMs)
        if (f == null) {
            lastDiagnostic = speech.startError?.let { "micro : $it" } ?: "niveau sonore ${speech.lastPeak}"
            // Micro refusé, occupé ou muet : on le dit tout de suite à l'écran (la cause n'est pas « je n'ai pas compris »).
            speech.startError?.let { status.text = "🎤 $it" }
        }
        return f
    }

    private var lastDiagnostic = ""

    /** @return le texte transcrit ; null si une erreur a été affichée (la conversation s'arrête). */
    private suspend fun transcribe(file: File): String? {
        status.text = getString(R.string.thinking)
        orb.mode = OrbView.Mode.THINKING
        return try {
            val (t, lang) = try { client.transcribe(file) } finally { file.delete() }
            lastSpokenLang = lang
            t
        } catch (e: AgentException) {
            showError(messageFor(e), detailFor(e)); null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            showError(getString(R.string.error_generic)); null
        }
    }

    // ---- Messages -----------------------------------------------------------------------------------------------

    /** « Je n'ai pas compris votre message. Voulez-vous réessayer ? » : toujours en texte, vibration pour les sourds. */
    private fun showNotUnderstood(askRetry: Boolean) {
        val base = getString(R.string.error_no_speech)
        status.text = if (askRetry) base else base + "\n" + getString(R.string.stay_available)
        if (settings.isDeafish) Vibe.play(this, HapticPattern.ATTENTION)
        if (lastDiagnostic.isNotEmpty()) reply.text = "(" + lastDiagnostic + ")" // aide au test ; à retirer pour la version finale
    }

    private fun showError(message: String, detail: String = "") {
        status.text = message + detail // le détail technique est affiché, jamais lu à voix haute
        if (voiceOn && !settings.isDeafish) lifecycleScope.launch { speaker.say(message, Lang.ui(settings.language)) }
        if (settings.isDeafish) Vibe.play(this, HapticPattern.ATTENTION)
    }

    private fun replyLang(text: String): String = when {
        settings.language in Lang.ALL -> settings.language
        lastSpokenLang in Lang.ALL -> lastSpokenLang
        else -> Lang.guess(text)
    }

    private fun messageFor(e: AgentException): String = getString(
        when (e.kind) {
            AgentException.Kind.NETWORK -> R.string.error_network
            AgentException.Kind.CONFIG -> R.string.error_server_config
            else -> R.string.error_generic
        }
    )

    /** Cause technique affichée sous le message (code HTTP, etc.) : indispensable pour trouver pourquoi « je n'ai pas pu répondre ». */
    private fun detailFor(e: AgentException): String = when (e.kind) {
        AgentException.Kind.NETWORK, AgentException.Kind.CONFIG -> ""
        else -> "\n[" + e.kind.name + (e.message?.let { " – $it" } ?: "") + "]"
    }

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
        if (voiceOn) lifecycleScope.launch { speaker.say("$title $message", replyLang(message)) }
    }

    companion object {
        const val EXTRA_WAKE = "wake"
        const val EXTRA_SOURCE = "source"
        @Volatile var visible = false

        private const val NO_SPEECH_MS = SpeechInput.NO_SPEECH_TIMEOUT_MS
        private const val SEE_WAIT_MS = 6000L
        private const val PAUSE_WAIT_MS = 60000L

        /** Marqueur : la conversation est terminée (erreur affichée, ou rien d'autre à faire). */
        private val ENDED = File("/ended")
    }
}
