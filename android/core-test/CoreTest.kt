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
    check("anglais", cmd("Stop") == VoiceCommand.STOP && cmd("Go on") == VoiceCommand.CONTINUE && cmd("Look again") == VoiceCommand.LOOK_AGAIN && cmd("Close the camera") == VoiceCommand.CLOSE_CAMERA)
    check("arabe (avec voyelles et variantes d'écriture)", cmd("قِف") == VoiceCommand.STOP && cmd("تابع") == VoiceCommand.CONTINUE && cmd("أغلق الكاميرا") == VoiceCommand.CLOSE_CAMERA && cmd("انظر مرة أخرى") == VoiceCommand.LOOK_AGAIN)
    check("somali", cmd("Jooji") == VoiceCommand.STOP && cmd("Sii wad") == VoiceCommand.CONTINUE)
    check("question normale : pas une commande", cmd("Qu'est-ce qu'il y a devant moi ?") == null && cmd("Quelle est la couleur de la bouteille ?") == null)
    check("phrase longue contenant « stop » : envoyée à l'IA", cmd("Arrête de me parler de ça et dis-moi quelle heure il est") == null && cmd("Le panneau stop est rouge") == null)
    check("réponse « oui / d'accord » à « réessayer ? »", VoiceCommands.isRetry("Oui.") && VoiceCommands.isRetry("D'accord") && !VoiceCommands.isRetry("Quelle heure est-il ?"))
    check("transcription vide ou inventée à partir du silence", VoiceCommands.isNoise("") && VoiceCommands.isNoise("  .  ") &&
        VoiceCommands.isNoise("Sous-titrage ST' 501") && VoiceCommands.isNoise("Thanks for watching!") && !VoiceCommands.isNoise("Bonjour"))

    println(if (failures == 0) "\nTout est vert." else "\n$failures échec(s).")
    if (failures > 0) System.exit(1)
}
