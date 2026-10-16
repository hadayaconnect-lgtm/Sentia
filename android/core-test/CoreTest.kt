import dj.sentia.core.*
import java.util.Random
import kotlin.math.PI
import kotlin.math.sin

var failures = 0
fun check(name: String, ok: Boolean, detail: String = "") {
    println((if (ok) "  OK   " else "  ÉCHEC") + " " + name + (if (!ok && detail.isNotEmpty()) " — $detail" else ""))
    if (!ok) failures++
}

const val G = 9.81f
const val HZ = 50 // échantillons par seconde (SENSOR_DELAY_GAME ≈ 50 Hz)

/** Applique un signal (fonction du temps en secondes → accélération x,y,z SANS gravité) et compte les détections. */
fun run(cfg: ShakeDetector.Config, seconds: Double, startMs: Long = 100_000, walking: Boolean = false,
        signal: (Double) -> FloatArray): Pair<Int, List<Long>> {
    val d = ShakeDetector(cfg)
    if (walking) d.walkingUntilMs = Long.MAX_VALUE
    var hits = 0
    val times = ArrayList<Long>()
    val n = (seconds * HZ).toInt()
    for (i in 0 until n) {
        val t = i.toDouble() / HZ
        val s = signal(t)
        val ts = startMs + (t * 1000).toLong()
        if (d.onSample(ts, s[0], s[1], G + s[2])) { hits++; times.add(ts - startMs) }
    }
    return hits to times
}

fun main() {
    val rnd = Random(42)
    fun noise(a: Float) = (rnd.nextGaussian() * a).toFloat()

    println("\n== Secousse volontaire")
    // 1,4 s de secousse à 4,5 Hz, amplitude 28 m/s², direction (0.8, 0.5, 0.3) normalisée, précédée de 1 s d'immobilité
    fun shake(f: Double, amp: Float, dur: Double, dir0: FloatArray = floatArrayOf(0.8f, 0.5f, 0.3f)) = { t: Double ->
        val norm = kotlin.math.sqrt(dir0[0] * dir0[0] + dir0[1] * dir0[1] + dir0[2] * dir0[2])
        val dir = floatArrayOf(dir0[0] / norm, dir0[1] / norm, dir0[2] / norm)
        if (t < 1.0 || t > 1.0 + dur) floatArrayOf(noise(0.15f), noise(0.15f), noise(0.15f))
        else {
            // modulation lente d'amplitude (±20 %) et de fréquence (±10 %) : une main n'est pas un métronome
            val a = amp * (1f + 0.2f * sin(2 * PI * 1.3 * t).toFloat())
            val ph = 2 * PI * f * (t - 1.0) + 0.5 * sin(2 * PI * 0.9 * t)
            val s = sin(ph).toFloat() * a
            floatArrayOf(dir[0] * s + noise(0.3f), dir[1] * s + noise(0.3f), dir[2] * s + noise(0.3f))
        }
    }
    for ((name, cfg) in listOf("moyenne" to ShakeDetector.Config.MEDIUM, "haute" to ShakeDetector.Config.HIGH)) {
        val (h, _) = run(cfg, 4.0, signal = shake(4.5, 28f, 1.4))
        check("secousse franche détectée une seule fois (sensibilité $name)", h == 1, "détections=$h")
    }
    val (hLow, _) = run(ShakeDetector.Config.LOW, 4.0, signal = shake(4.5, 28f, 1.5))
    check("secousse franche détectée en sensibilité basse", hLow == 1, "détections=$hLow")

    var ok = 0; val trials = 200
    for (k in 0 until trials) {
        val f = 3.0 + rnd.nextDouble() * 3.0           // 3 à 6 Hz
        val amp = 20f + rnd.nextFloat() * 25f           // 20 à 45 m/s²
        val dir = floatArrayOf(rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f)
        val (h, _) = run(ShakeDetector.Config.MEDIUM, 4.0, signal = shake(f, amp, 1.3, dir))
        if (h >= 1) ok++
    }
    check("taux de détection ≥ 95 % sur 200 secousses variées (3-6 Hz, 20-45 m/s²)", ok >= trials * 0.95, "$ok/$trials")

    val (hWeak, _) = run(ShakeDetector.Config.MEDIUM, 4.0, signal = shake(4.5, 7f, 1.5))
    check("mouvement faible (7 m/s²) ignoré", hWeak == 0, "détections=$hWeak")

    println("\n== Faux positifs")
    // Marche : pas à 1,8 Hz, pics verticaux de même sens (6-10 m/s²), balancement latéral
    fun walk(freq: Double, peak: Float) = { t: Double ->
        val ph = (t * freq) % 1.0
        val impact = if (ph < 0.18) sin(PI * ph / 0.18).toFloat() * (peak * (0.85f + noise(0.1f))) else 0f
        floatArrayOf(noise(0.8f) + 1.5f * sin(2 * PI * freq / 2 * t).toFloat(), noise(0.8f), impact - 0.35f * peak * sin(2 * PI * freq * t).toFloat() * 0f + noise(0.5f))
    }
    var fp = 0
    for (k in 0 until 20) fp += run(ShakeDetector.Config.MEDIUM, 120.0) { walk(1.6 + rnd.nextDouble() * 0.7, 6f + rnd.nextFloat() * 5f)(it) }.first
    check("marche (40 minutes simulées, 6-11 m/s²) : 0 fausse détection", fp == 0, "faux=$fp")

    // Course réaliste : contact (35 % du cycle, demi-sinusoïde positive) puis vol (-c, moyenne nulle)
    fun runStride(freq: Double, peak: Float) = { t: Double ->
        val ph = (t * freq) % 1.0
        val c = 0.343f * peak
        val z = if (ph < 0.35) sin(PI * ph / 0.35).toFloat() * peak else -c
        floatArrayOf(noise(1.0f), noise(1.0f), z + noise(0.8f))
    }
    var fpRun = 0
    for (k in 0 until 20) fpRun += run(ShakeDetector.Config.MEDIUM, 120.0) { walk(2.6 + rnd.nextDouble() * 0.5, 16f + rnd.nextFloat() * 12f)(it) }.first
    check("course (40 minutes simulées, 16-28 m/s²) : 0 fausse détection", fpRun == 0, "faux=$fpRun")

    var fpRun2 = 0
    for (k in 0 until 20) fpRun2 += run(ShakeDetector.Config.MEDIUM, 120.0) { runStride(2.6 + rnd.nextDouble() * 0.5, 18f + rnd.nextFloat() * 14f)(it) }.first
    check("course avec phase de vol (40 minutes simulées) : 0 fausse détection", fpRun2 == 0, "faux=$fpRun2")

    var fpRunHigh = 0
    for (k in 0 until 20) fpRunHigh += run(ShakeDetector.Config.HIGH, 120.0, walking = true) { runStride(2.6 + rnd.nextDouble() * 0.5, 18f + rnd.nextFloat() * 14f)(it) }.first
    check("course, sensibilité haute AVEC capteur de pas : 0 fausse détection", fpRunHigh == 0, "faux=$fpRunHigh")

    var fpRunHighRaw = 0
    for (k in 0 until 20) fpRunHighRaw += run(ShakeDetector.Config.HIGH, 120.0) { runStride(2.6 + rnd.nextDouble() * 0.5, 18f + rnd.nextFloat() * 14f)(it) }.first
    println("  INFO  course, sensibilité haute SANS capteur de pas : $fpRunHighRaw fausse(s) détection(s) en 40 min")

    // Transport : cahots aléatoires (impulsions de direction quelconque, intervalles irréguliers) + vibration moteur
    var fpBus = 0
    for (k in 0 until 20) {
        val impulses = ArrayList<Pair<Double, FloatArray>>()
        var t = 0.0
        while (t < 120.0) { t += 0.3 + rnd.nextDouble() * 2.0
            impulses.add(t to floatArrayOf((rnd.nextFloat() - .5f) * 40, (rnd.nextFloat() - .5f) * 40, (rnd.nextFloat() - .5f) * 40)) }
        fpBus += run(ShakeDetector.Config.MEDIUM, 120.0) { time ->
            var x = noise(1.2f); var y = noise(1.2f); var z = noise(1.2f) + 1.5f * sin(2 * PI * 28 * time).toFloat()
            for ((ti, v) in impulses) { val dtt = time - ti; if (dtt in 0.0..0.12) { val w = sin(PI * dtt / 0.12).toFloat(); x += v[0] * w; y += v[1] * w; z += v[2] * w } }
            floatArrayOf(x, y, z)
        }.first
    }
    check("transport cahoteux (40 minutes simulées) : 0 fausse détection", fpBus == 0, "faux=$fpBus")

    // Choc / chute : une impulsion unique très forte, puis immobilité
    val (hDrop, _) = run(ShakeDetector.Config.HIGH, 5.0) { t -> if (t in 1.0..1.06) floatArrayOf(0f, 0f, 60f) else floatArrayOf(noise(.1f), noise(.1f), noise(.1f)) }
    check("chute / choc unique ignoré", hDrop == 0, "détections=$hDrop")

    // Téléphone posé : bruit seul
    val (hRest, _) = run(ShakeDetector.Config.HIGH, 300.0) { floatArrayOf(noise(.12f), noise(.12f), noise(.12f)) }
    check("téléphone posé (5 minutes) : rien", hRest == 0, "détections=$hRest")

    // Mouvement lent et ample (ramasser le téléphone)
    val (hSlow, _) = run(ShakeDetector.Config.HIGH, 5.0) { t -> floatArrayOf((6 * sin(2 * PI * 0.8 * t)).toFloat(), 0f, 0f) }
    check("mouvement lent et ample ignoré", hSlow == 0, "détections=$hSlow")

    println("\n== Anti-répétition et reprise")
    val d = ShakeDetector(ShakeDetector.Config.MEDIUM)
    var hits = 0
    val s = shake(4.5, 28f, 6.0)   // secousse continue de 6 s
    for (i in 0 until 8 * HZ) { val t = i.toDouble() / HZ; val v = s(t); if (d.onSample(100_000 + (t * 1000).toLong(), v[0], v[1], G + v[2])) hits++ }
    check("secousse prolongée 6 s : 2 détections au plus (blocage de 3 s)", hits in 1..2, "détections=$hits")

    val d2 = ShakeDetector(ShakeDetector.Config.MEDIUM)
    var h2 = 0
    val s2 = shake(4.5, 28f, 1.4)
    for (i in 0 until 3 * HZ) { val t = i.toDouble() / HZ; val v = s2(t); if (d2.onSample(100_000 + (t * 1000).toLong(), v[0], v[1], G + v[2])) h2++ }
    // trou de 2 s (capteur en pause), puis une nouvelle secousse : doit être détectée
    for (i in 0 until 3 * HZ) { val t = i.toDouble() / HZ; val v = s2(t); if (d2.onSample(105_000 + (t * 1000).toLong(), v[0], v[1], G + v[2])) h2++ }
    check("après une pause du capteur, la détection reprend", h2 == 2, "détections=$h2")

    // En marchant, le seuil est relevé : une secousse moyenne est ignorée, une forte reste détectée
    val (hw1, _) = run(ShakeDetector.Config.MEDIUM, 4.0, walking = true, signal = shake(4.5, 15f, 1.4))
    val (hw2, _) = run(ShakeDetector.Config.MEDIUM, 4.0, walking = true, signal = shake(4.5, 35f, 1.4))
    check("en marchant : secousse faible ignorée, secousse forte détectée", hw1 == 0 && hw2 == 1, "faible=$hw1 forte=$hw2")

    println("\n== Trois appuis sur Volume +")
    val t3 = VolumeTriple()
    check("2 appuis : rien", !t3.onUpPress(0) && !t3.onUpPress(400))
    check("3e appui rapide : déclenché", t3.onUpPress(800))
    val t4 = VolumeTriple()
    t4.onUpPress(0); t4.onUpPress(900)
    check("3 appuis trop espacés : rien", !t4.onUpPress(2100))
    check("puis 3 rapides : déclenché", !t4.onUpPress(4000) && !t4.onUpPress(4200) && t4.onUpPress(4400))

    println("\n== Geste volume (deux touches maintenues)")
    val v = VolumeGesture(1000)
    check("première touche : transmise au système", !v.onKey(VolumeGesture.Key.UP, true, 0))
    check("seconde touche : consommée", v.onKey(VolumeGesture.Key.DOWN, true, 200))
    check("pas de déclenchement avant 1 s", !v.tick(900) && !v.tick(1100))
    check("déclenchement après 1 s de maintien", v.tick(1250))
    check("déclenchement unique", !v.tick(1500) && !v.tick(2500))
    check("relâchement consommé, geste terminé", v.onKey(VolumeGesture.Key.UP, false, 2600) && !v.bothHeld)
    check("relâchement de la seconde touche transmis", !v.onKey(VolumeGesture.Key.DOWN, false, 2650))
    val v2 = VolumeGesture(1000)
    v2.onKey(VolumeGesture.Key.UP, true, 0); v2.onKey(VolumeGesture.Key.DOWN, true, 100)
    v2.onKey(VolumeGesture.Key.DOWN, false, 400)
    check("relâché trop tôt : aucun déclenchement", !v2.tick(1500))
    val v3 = VolumeGesture(1000)
    v3.onKey(VolumeGesture.Key.UP, true, 0); v3.onKey(VolumeGesture.Key.UP, false, 90)
    v3.onKey(VolumeGesture.Key.DOWN, true, 200); v3.onKey(VolumeGesture.Key.DOWN, false, 300)
    check("réglage normal du volume (touches une à une) : jamais de déclenchement ni de blocage", !v3.tick(5000) && !v3.bothHeld)

    println("\n== Sons")
    val f = SoundEventFilter()
    check("étiquette inconnue ignorée", f.accept("Speech", 0.9f, 0) == null)
    check("score trop faible ignoré", f.accept("Doorbell", 0.3f, 0) == null)
    val e = f.accept("Doorbell", 0.6f, 1000)
    check("sonnette détectée avec confiance faible", e?.category == SoundCategory.DOORBELL && e.confidence == Confidence.LOW)
    check("même son répété : ignoré (anti-répétition)", f.accept("Doorbell", 0.9f, 5000) == null)
    check("après 15 s : de nouveau signalé, confiance haute", f.accept("Ding-dong", 0.9f, 17000)?.confidence == Confidence.HIGH)
    check("klaxon et bébé reconnus", f.accept("Vehicle horn, car horn, honking", 0.8f, 0)?.category == SoundCategory.CAR_HORN && f.accept("Baby cry, infant cry", 0.8f, 0)?.category == SoundCategory.BABY_CRY)

    println("\n== Vibrations")
    check("4 motifs simples, tous reconnus par leur clé", listOf("awake", "info", "attention", "detected").all { HapticPattern.fromKey(it) != null } && HapticPattern.fromKey("x") == null)
    check("motifs distincts et courts", HapticPattern.entries.map { it.timings.toList() }.toSet().size == 4 && HapticPattern.entries.all { it.timings.sum() < 1000 })

    println("\n== Commandes vocales")
    fun cmd(t: String) = VoiceCommands.parse(t)
    check("« Stop. » reconnu", cmd("Stop.") == VoiceCommand.STOP && cmd("Arrête !") == VoiceCommand.STOP && cmd("Tais-toi") == VoiceCommand.STOP)
    check("« Stop, s'il te plaît » reconnu", cmd("Stop, s'il te plaît.") == VoiceCommand.STOP && cmd("SENTIA stop") == VoiceCommand.STOP)
    check("pause / continue / répète", cmd("Pause") == VoiceCommand.PAUSE && cmd("Continue.") == VoiceCommand.CONTINUE && cmd("Répète") == VoiceCommand.REPEAT && cmd("Vas-y") == VoiceCommand.CONTINUE)
    check("regarde encore / lis ça / décris la scène / ferme la caméra",
        cmd("Regarde encore") == VoiceCommand.LOOK_AGAIN && cmd("Lis ça") == VoiceCommand.READ_TEXT &&
        cmd("Décris la scène") == VoiceCommand.DESCRIBE_SCENE && cmd("Ferme la caméra.") == VoiceCommand.CLOSE_CAMERA)
    check("billet / lis le panneau / regarde devant moi", cmd("Quel est ce billet ?") == VoiceCommand.BANKNOTE && cmd("Lis le panneau.") == VoiceCommand.READ_TEXT &&
        cmd("Regarde devant moi") == VoiceCommand.LOOK_AGAIN && cmd("Lis le document") == VoiceCommand.READ_TEXT)
    check("anglais", cmd("Stop") == VoiceCommand.STOP && cmd("Go on") == VoiceCommand.CONTINUE && cmd("Look again") == VoiceCommand.LOOK_AGAIN && cmd("Close the camera") == VoiceCommand.CLOSE_CAMERA)
    check("arabe (avec voyelles et variantes d'écriture)", cmd("قِف") == VoiceCommand.STOP && cmd("تابع") == VoiceCommand.CONTINUE && cmd("أغلق الكاميرا") == VoiceCommand.CLOSE_CAMERA && cmd("انظر مرة أخرى") == VoiceCommand.LOOK_AGAIN)
    check("somali", cmd("Jooji") == VoiceCommand.STOP && cmd("Sii wad") == VoiceCommand.CONTINUE)
    check("question normale : pas une commande", cmd("Qu'est-ce qu'il y a devant moi ?") == null && cmd("Quelle est la couleur de la bouteille ?") == null)
    check("phrase longue contenant « stop » : envoyée à l'IA", cmd("Arrête de me parler de ça et dis-moi quelle heure il est") == null && cmd("Le panneau stop est rouge") == null)
    check("réponse « oui / d'accord » à « réessayer ? »", VoiceCommands.isRetry("Oui.") && VoiceCommands.isRetry("D'accord") && !VoiceCommands.isRetry("Quelle heure est-il ?"))
    check("transcription vide ou inventée à partir du silence", VoiceCommands.isNoise("") && VoiceCommands.isNoise("  .  ") &&
        VoiceCommands.isNoise("Sous-titrage ST' 501") && VoiceCommands.isNoise("Thanks for watching!") && !VoiceCommands.isNoise("Bonjour"))

    println("\n== Pipeline photo unique")
    class Cam { var shots = 0; var released = 0 }
    fun flow(cam: Cam, seq: List<Shot?>, boom: Int = -1): Shot? = captureValid(
        shoot = { a -> cam.shots++; if (a == boom) throw IllegalStateException("capture"); seq.getOrNull(a - 1) },
        usable = { PhotoCheck.isUsable(it) },
        release = { cam.released++ },
    )
    val good = Shot("QUJD", 90, 200_000)
    val black = Shot("QUJD", 2, 30_000)
    val c1 = Cam(); val r1 = flow(c1, listOf(good))
    check("photo valide : UNE seule capture, envoyée", r1 === good && c1.shots == 1)
    check("caméra libérée après la capture (succès)", c1.released == 1)
    val c2 = Cam(); val r2 = flow(c2, listOf(black, good))
    check("photo noire : une nouvelle capture, la bonne est gardée", r2 === good && c2.shots == 2 && c2.released == 1)
    val c3 = Cam(); val r3 = flow(c3, listOf(black, black, good))
    check("2 photos invalides : STOP (2 captures max), RIEN d'envoyé", r3 == null && c3.shots == 2 && c3.released == 1)
    val c4 = Cam(); val r4 = flow(c4, listOf(null, good), boom = 1)
    check("échec de capture : compte comme invalide, une reprise", r4 === good && c4.shots == 2)
    val c5 = Cam(); val r5 = try { captureValid<Shot>(shoot = { throw kotlin.coroutines.cancellation.CancellationException("x") }, usable = { true }, release = { c5.released++ }) } catch (e: kotlin.coroutines.cancellation.CancellationException) { null }
    check("annulation / délai : jamais avalée, caméra quand même libérée", r5 == null && c5.released == 1)
    check("image vide ou minuscule : invalide", !PhotoCheck.isUsable(Shot("", 90, 0)) && !PhotoCheck.isUsable(Shot("QQ", 90, 300)) && !PhotoCheck.isUsable(black) && PhotoCheck.isUsable(good))
    check("mur blanc uniforme (lumineux) : valide, l'IA dira qu'il n'y a rien", PhotoCheck.isUsable(Shot("QUJD", 230, 15_000)))
    val b = PhotoBudget()
    check("une seule photo par demande (budget)", b.tryTake() && !b.tryTake() && !b.tryTake() && b.count == 1)
    check("taille : 1,2 Mo max, 1600 px (billet / petit texte lisibles)", AnalysisPolicy.MAX_BYTES <= 1_300_000 && AnalysisPolicy.MAX_SIDE >= 1280)

    println("\n== Nouvelle photo ou photo précédente ?")
    fun fresh(t: String) = FreshPhoto.needed(t)
    check("sécurité : nouvelle photo", listOf("Est-ce que je peux avancer ?", "C'est libre devant ?", "Y a-t-il un obstacle ?", "Est-ce dangereux ?",
        "Je peux traverser ?", "Is it safe?", "Can I walk?", "Is there an obstacle?").all { fresh(it) })
    check("suivi sur l'élément déjà analysé : photo précédente conservée", listOf("Qu'est-ce que c'est ?", "Et la personne à droite ?",
        "De quelle couleur est la bouteille ?", "Lis le texte du panneau").none { fresh(it) })

    println("\n== Délais")
    check("plus de délai global de 10 s", AnalysisPolicy.CAPTURE_MAX_MS != 10_000L && AnalysisPolicy.AI_MAX_MS != 10_000L && AnalysisPolicy.REASSURE_AT_MS != 10_000L)
    check("la phrase de patience n'arrête rien (avant le maximum IA)", AnalysisPolicy.REASSURE_AT_MS < AnalysisPolicy.AI_MAX_MS)
    check("réseau : une seule reprise, jamais sur un délai", RetryPolicy.shouldRetryNetwork(true, false, null) && RetryPolicy.shouldRetryNetwork(true, false, 20_000) &&
        !RetryPolicy.shouldRetryNetwork(true, true, null) && !RetryPolicy.shouldRetryNetwork(false, false, null) && !RetryPolicy.shouldRetryNetwork(true, false, 3_000))

    println("\n== Chronomètre par étape")
    var clock = 1_000L
    val st = StageTimes { clock }
    st.begin(); clock += 300; st.mark("screen")
    st.put("cam_open", 650); st.put("shot", 400); st.retake(); st.put("validate", 20); st.put("compress", 180); st.photoSize(350_000)
    st.put("upload", 900); st.put("wait", 4_200); st.put("server", 3_800); st.put("download", 100)
    clock += 6_000; st.mark("ai_done"); clock += 250
    val rep = st.ttsStarted()
    check("rapport complet, chaque étape", rep != null && listOf("Réveil → écran : 0,3 s", "Caméra prête : 0,7 s", "Prise de la photo : 0,4 s (+1 nouvelle capture)", "Validation de l'image : 0,0 s",
        "Compression (341 Ko) : 0,2 s", "Envoi au serveur : 0,9 s", "Attente serveur + IA : 4,2 s (dont agent 3,8 s)", "Réception : 0,1 s", "Voix (délai avant le 1er son) : 0,3 s", "TOTAL : 6,6 s").all { rep!!.contains(it) }, rep ?: "null")
    check("rapport écrit une seule fois par analyse", st.ttsStarted() == null)
    val st2 = StageTimes { clock }; st2.begin()
    check("pas de rapport tant que l'IA n'a pas répondu (le salut ne compte pas)", st2.ttsStarted() == null)

    println("\n== Conversation en direct (protocole)")
    val rawSetup = LiveProtocol.setup("models/gemini-3.8-live", "Tu es \"SENTIA\".\nBref.", "[{\"functionDeclarations\":[]}]")
    val sm = MiniJson.parseObject(rawSetup)
    val su = sm?.get("setup") as? Map<*, *>
    check("setup : JSON valide, modèle, audio, transcriptions, reprise", su != null && su["model"] == "models/gemini-3.8-live" &&
        ((su["generationConfig"] as Map<*, *>)["responseModalities"] as List<*>) == listOf("AUDIO") &&
        su["inputAudioTranscription"] is Map<*, *> && su["outputAudioTranscription"] is Map<*, *> && su["sessionResumption"] is Map<*, *>)
    check("setup : consignes avec guillemets et retour à la ligne conservées", (((su!!["systemInstruction"] as Map<*, *>)["parts"] as List<*>)[0] as Map<*, *>)["text"] == "Tu es \"SENTIA\".\nBref.")
    check("setup : reprise avec identifiant", ((MiniJson.parseObject(LiveProtocol.setup("m", "i", "[]", "H1"))!!["setup"] as Map<*, *>)["sessionResumption"] as Map<*, *>)["handle"] == "H1")
    val au = MiniJson.parseObject(LiveProtocol.audio("QUJD"))!!["realtimeInput"] as Map<*, *>
    check("audio du micro : PCM 16 kHz", (au["audio"] as Map<*, *>)["mimeType"] == "audio/pcm;rate=16000" && (au["audio"] as Map<*, *>)["data"] == "QUJD")
    check("photo : UNE image JPEG", ((MiniJson.parseObject(LiveProtocol.photo("QQ=="))!!["realtimeInput"] as Map<*, *>)["video"] as Map<*, *>)["mimeType"] == "image/jpeg")
    val tr = MiniJson.parseObject(LiveProtocol.toolResponse("c1", "capture_camera", "ok \"photo\""))!!["toolResponse"] as Map<*, *>
    check("réponse d'outil : identifiant et nom repris", (((tr["functionResponses"] as List<*>)[0]) as Map<*, *>).let { it["id"] == "c1" && it["name"] == "capture_camera" && (it["response"] as Map<*, *>)["result"] == "ok \"photo\"" })
    check("réponse d'outil en erreur", (((MiniJson.parseObject(LiveProtocol.toolResponse("c1", "x", "non", true))!!["toolResponse"] as Map<*, *>)["functionResponses"] as List<*>)[0] as Map<*, *>).let { (it["response"] as Map<*, *>).containsKey("error") })
    check("fin de flux audio / texte", LiveProtocol.audioStreamEnd().contains("audioStreamEnd") && MiniJson.parseObject(LiveProtocol.text("Bonjour \"toi\""))!!.containsKey("realtimeInput"))

    val evs = LiveProtocol.parse("{\"serverContent\":{\"modelTurn\":{\"parts\":[{\"inlineData\":{\"mimeType\":\"audio/pcm;rate=24000\",\"data\":\"AAA=\"}}]},\"outputTranscription\":{\"text\":\"Bonjour\"},\"turnComplete\":true}}")
    check("serveur : audio + texte + fin de tour dans un même message", evs.size == 3 && evs[0] is LiveEvent.Audio && (evs[0] as LiveEvent.Audio).b64 == "AAA=" && evs[1] is LiveEvent.OutputText && evs[2] is LiveEvent.TurnComplete)
    check("serveur : setupComplete", LiveProtocol.parse("{\"setupComplete\":{}}").single() is LiveEvent.SetupComplete)
    check("serveur : interruption (vider la lecture)", LiveProtocol.parse("{\"serverContent\":{\"interrupted\":true}}").single() is LiveEvent.Interrupted)
    check("serveur : ce que dit la personne", (LiveProtocol.parse("{\"serverContent\":{\"inputTranscription\":{\"text\":\"Stop\",\"languageCode\":\"fr\"}}}").single() as LiveEvent.InputText).text == "Stop")
    val tc = LiveProtocol.parse("{\"toolCall\":{\"functionCalls\":[{\"id\":\"call_1\",\"name\":\"capture_camera\",\"args\":{\"purpose\":\"money\"}}]}}").single() as LiveEvent.ToolCall
    check("serveur : demande de photo avec son objectif", tc.calls.single().let { it.id == "call_1" && it.name == "capture_camera" && it.args["purpose"] == "money" })
    check("serveur : annulation d'un appel d'outil", (LiveProtocol.parse("{\"toolCallCancellation\":{\"ids\":[\"a\",\"b\"]}}").single() as LiveEvent.ToolCancel).ids == listOf("a", "b"))
    check("serveur : goAway et reprise", (LiveProtocol.parse("{\"goAway\":{\"timeLeft\":\"30s\"}}").single() as LiveEvent.GoAway).timeLeftMs == 30_000L &&
        (LiveProtocol.parse("{\"sessionResumptionUpdate\":{\"newHandle\":\"H2\",\"resumable\":true}}").single() as LiveEvent.Resumption).handle == "H2")
    check("serveur : message illisible = échec propre, jamais de plantage", LiveProtocol.parse("pas du json").single() is LiveEvent.Failure && LiveProtocol.parse("[1,2]").single() is LiveEvent.Failure)
    check("serveur : message inconnu ignoré", LiveProtocol.parse("{\"autre\":1}").isEmpty())
    check("JSON : unicode, échappements, imbrications", (MiniJson.parse("{\"a\":[1,{\"b\":\"\\u00e9\\n\"}],\"c\":null,\"d\":true}") as Map<*, *>).let { ((it["a"] as List<*>)[1] as Map<*, *>)["b"] == "é\n" && it["c"] == null && it["d"] == true })

    println("\n== Conversation en direct : OpenAI Realtime")
    fun obj(j: String) = MiniJson.parseObject(j)!!
    fun mp(v: Any?) = v as Map<*, *>
    val ow = OpenAiWire
    val openMsg = obj(ow.open("gpt-realtime-2", "Tu es \"SENTIA\".\nBref.", "[{\"type\":\"function\",\"name\":\"get_time\"}]", "marin", null).single())
    val sess = mp(openMsg["session"])
    check("OpenAI : session.update « realtime », consignes et outils tels quels", openMsg["type"] == "session.update" && sess["type"] == "realtime" && sess["instructions"] == "Tu es \"SENTIA\".\nBref." && (sess["tools"] as List<*>).size == 1)
    check("OpenAI : voix PCM 24 kHz dans les deux sens, voix choisie", mp(mp(mp(sess["audio"])["input"])["format"])["rate"] == 24000.0 && mp(mp(mp(sess["audio"])["output"])["format"])["rate"] == 24000.0 && mp(mp(sess["audio"])["output"])["voice"] == "marin")
    check("OpenAI : réponse en audio seulement", (sess["output_modalities"] as List<*>) == listOf("audio"))
    check("OpenAI : micro à 24 kHz", ow.sampleRateIn == 24_000)
    check("OpenAI : jeton dans l'en-tête, adresse inchangée", ow.headers("ek_1")["Authorization"] == "Bearer ek_1" && ow.url("wss://x/v1/realtime?model=m", "ek_1") == "wss://x/v1/realtime?model=m")
    val au2 = obj(ow.audio("QUJD"))
    check("OpenAI : audio du micro", au2["type"] == "input_audio_buffer.append" && au2["audio"] == "QUJD")
    val tx = ow.text("Regarde \"ça\"").map { obj(it) }
    check("OpenAI : texte = message utilisateur puis demande de réponse", tx.size == 2 && tx[0]["type"] == "conversation.item.create" && tx[1]["type"] == "response.create" &&
        (((mp(tx[0]["item"])["content"] as List<*>)[0]) as Map<*, *>)["text"] == "Regarde \"ça\"")
    val pr = ow.photoResult("call_9", "capture_camera", "QQ==", "Photo reçue.").map { obj(it) }
    check("OpenAI : résultat d'outil, UNE photo JPEG, puis réponse (dans cet ordre)", pr.size == 3 &&
        mp(pr[0]["item"])["type"] == "function_call_output" && mp(pr[0]["item"])["call_id"] == "call_9" && mp(pr[0]["item"])["output"] == "Photo reçue." &&
        (((mp(pr[1]["item"])["content"] as List<*>)[0]) as Map<*, *>).let { it["type"] == "input_image" && it["image_url"] == "data:image/jpeg;base64,QQ==" } &&
        pr[2]["type"] == "response.create")
    val te = ow.toolResult("c2", "capture_camera", "non", true).map { obj(it) }
    check("OpenAI : outil en erreur = résultat marqué « Erreur » puis réponse", te.size == 2 && (mp(te[0]["item"])["output"] as String).startsWith("Erreur") && te[1]["type"] == "response.create")
    check("OpenAI : session prête (session.updated)", ow.parse("{\"type\":\"session.updated\",\"session\":{}}").single() is LiveEvent.SetupComplete)
    check("OpenAI : voix reçue", (ow.parse("{\"type\":\"response.output_audio.delta\",\"delta\":\"AAA=\"}").single() as LiveEvent.Audio).b64 == "AAA=")
    check("OpenAI : texte de la voix de SENTIA", (ow.parse("{\"type\":\"response.output_audio_transcript.delta\",\"delta\":\"Bon\"}").single() as LiveEvent.OutputText).text == "Bon")
    val it2 = ow.parse("{\"type\":\"conversation.item.input_audio_transcription.completed\",\"transcript\":\"Stop\"}").single() as LiveEvent.InputText
    check("OpenAI : phrase de la personne, entière", it2.text == "Stop" && it2.complete)
    check("OpenAI : la personne reprend la parole = interruption", ow.parse("{\"type\":\"input_audio_buffer.speech_started\"}").single() is LiveEvent.Interrupted)
    val fc = ow.parse("{\"type\":\"response.output_item.done\",\"item\":{\"type\":\"function_call\",\"name\":\"capture_camera\",\"call_id\":\"call_7\",\"arguments\":\"{\\\"purpose\\\":\\\"money\\\"}\"}}").single() as LiveEvent.ToolCall
    check("OpenAI : appel d'outil avec ses arguments", fc.calls.single().id == "call_7" && fc.calls.single().name == "capture_camera" && fc.calls.single().args["purpose"] == "money")
    check("OpenAI : un message d'assistant n'est pas un appel d'outil", ow.parse("{\"type\":\"response.output_item.done\",\"item\":{\"type\":\"message\"}}").isEmpty())
    check("OpenAI : réponse terminée = fin du tour", ow.parse("{\"type\":\"response.done\",\"response\":{\"output\":[{\"type\":\"message\"}]}}").single() is LiveEvent.TurnComplete)
    check("OpenAI : réponse qui ne fait qu'appeler un outil = PAS la fin du tour (une seule photo par demande)", ow.parse("{\"type\":\"response.done\",\"response\":{\"output\":[{\"type\":\"function_call\",\"name\":\"capture_camera\"}]}}").isEmpty())
    check("OpenAI : erreur du serveur", (ow.parse("{\"type\":\"error\",\"error\":{\"message\":\"bad key\"}}").single() as LiveEvent.Failure).message == "bad key")
    check("OpenAI : message inconnu ignoré, message illisible = échec propre", ow.parse("{\"type\":\"rate_limits.updated\"}").isEmpty() && ow.parse("pas du json").single() is LiveEvent.Failure)
    check("choix du fournisseur : OpenAI par défaut, Gemini sur demande", LiveWire.forProvider("openai") === OpenAiWire && LiveWire.forProvider("") === OpenAiWire && LiveWire.forProvider("gemini") === GeminiWire)
    check("Gemini : jeton dans l'adresse, encodé ; micro 16 kHz", GeminiWire.url("wss://g/ws?x=1", "auth_tokens/a b").endsWith("&access_token=auth_tokens%2Fa+b") && GeminiWire.sampleRateIn == 16_000 && GeminiWire.headers("t").isEmpty())
    check("Gemini : photo puis résultat d'outil", GeminiWire.photoResult("c", "capture_camera", "QQ==", "ok").size == 2)

    println("\n== Conversation en direct (règles)")
    val gate = LivePhotoGate()
    check("une seule photo par demande, de nouveau possible au tour suivant", gate.tryTake() && !gate.tryTake() && !gate.tryTake().also { gate.turnEnded() } && gate.tryTake())
    val spoken = SpokenText()
    check("« Stop » reconnu même dit en plusieurs morceaux", spoken.add("Sto").let { VoiceCommands.parse(it) } == null && VoiceCommands.parse(spoken.add("p")) == VoiceCommand.STOP)
    spoken.reset()
    check("phrase normale : pas une commande", VoiceCommands.parse(spoken.add("Qu'est-ce qu'il y a sur la table ?")) == null)
    val idle = IdleWatch(180_000)
    idle.activity(1_000)
    check("veille après un long silence seulement", !idle.expired(100_000) && idle.expired(200_000) && !IdleWatch(1000).expired(999_999))
    check("fin de conversation demandée à voix haute (pas « Stop »)", listOf("Au revoir", "Au revoir Sentia.", "À bientôt !", "Termine la conversation", "Goodbye").all { LiveEnd.isGoodbye(it) } &&
        !LiveEnd.isGoodbye("Stop") && !LiveEnd.isGoodbye("Dis au revoir à mon ami en arabe") && !LiveEnd.isGoodbye("Quelle heure est-il ?"))
    check("reconnexion : 3 essais au plus, attente croissante", LiveReconnect.canRetry(3) && !LiveReconnect.canRetry(4) && LiveReconnect.delayMs(1) == 500L && LiveReconnect.delayMs(3) == 2000L)

    println(if (failures == 0) "\nTout est vert." else "\n$failures échec(s).")
    if (failures > 0) System.exit(1)
}
