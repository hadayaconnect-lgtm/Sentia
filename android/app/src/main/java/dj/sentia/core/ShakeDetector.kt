package dj.sentia.core

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Détecteur de secousse VOLONTAIRE, 100 % local (aucune donnée de capteur n'est envoyée à l'IA).
 *
 * Principe :
 *  1. On retire la gravité (filtre passe-bas) pour ne garder que l'accélération du mouvement.
 *  2. Une « secousse élémentaire » (jolt) est un pic net : l'intensité dépasse le seuil, puis retombe.
 *  3. Une vraie secousse = plusieurs pics RAPIDES (70-450 ms d'écart) qui ALTERNENT de direction
 *     (aller, retour, aller…), dans une fenêtre courte.
 *  4. Rejetés : marche et course (pics de même direction, espacés), transport et cahots (écarts irréguliers,
 *     directions quelconques), chute ou choc (un seul pic), vibrations de moteur (trop faibles / trop rapides).
 *  5. Après détection : délai de blocage (anti-répétition).
 *
 * Classe sans dépendance Android : testable sur ordinateur.
 */
class ShakeDetector(var config: Config = Config.MEDIUM) {

    data class Config(
        /** Intensité minimale d'un pic (m/s², gravité retirée). */
        val threshold: Float,
        /** Nombre de pics alternés nécessaires (≈ 2 allers-retours pour 4). */
        val minJolts: Int,
        /** Durée maximale de toute la séquence. */
        val windowMs: Long = 1600,
        val minIntervalMs: Long = 70,
        val maxIntervalMs: Long = 450,
        /** Part minimale de changements de direction entre pics consécutifs. */
        val minAlternation: Float = 0.7f,
        /** Blocage après une détection. */
        val cooldownMs: Long = 3000,
    ) {
        companion object {
            /** Peu sensible : il faut secouer franchement et longtemps (3 allers-retours). */
            val LOW = Config(threshold = 16f, minJolts = 6)
            val MEDIUM = Config(threshold = 13f, minJolts = 4)
            /** Très sensible : 3 pics (risque de fausses détections plus élevé). */
            val HIGH = Config(threshold = 8f, minJolts = 3)
        }
    }

    private class Jolt(val timeMs: Long, val x: Float, val y: Float, val z: Float, val durationMs: Long) {
        val mag: Float = sqrt(x * x + y * y + z * z)
    }

    private val gravity = FloatArray(3)
    private var gravityReady = false
    private var lastTsMs = -1L

    private var inSegment = false
    private var peakMag = 0f
    private var peakTs = 0L
    private var segmentStart = 0L
    private var px = 0f
    private var py = 0f
    private var pz = 0f

    private val jolts = ArrayList<Jolt>()
    private var cooldownUntil = 0L

    /** Renseigné par le capteur de pas : tant qu'on marche, on exige une secousse plus forte. */
    @Volatile var walkingUntilMs: Long = 0

    /** Dernière intensité mesurée (gravité retirée), pour le diagnostic. */
    @Volatile var lastMag: Float = 0f

    fun reset() {
        gravityReady = false
        lastTsMs = -1
        inSegment = false
        peakMag = 0f
        jolts.clear()
    }

    /** Un échantillon d'accéléromètre (m/s², gravité comprise). Renvoie true quand une secousse est détectée. */
    fun onSample(tsMs: Long, x: Float, y: Float, z: Float): Boolean {
        // Trou dans les mesures (veille, capteur mis en pause) : on repart de zéro.
        if (lastTsMs >= 0 && (tsMs - lastTsMs > 1000 || tsMs < lastTsMs)) reset()
        val dt = if (lastTsMs < 0) 0L else tsMs - lastTsMs
        lastTsMs = tsMs

        if (!gravityReady) {
            gravity[0] = x; gravity[1] = y; gravity[2] = z
            gravityReady = true
            return false
        }
        // Filtre passe-bas à constante de temps 0,8 s (indépendant de la fréquence d'échantillonnage).
        val alpha = TAU_MS.toFloat() / (TAU_MS + dt.coerceAtLeast(1)).toFloat()
        gravity[0] = alpha * gravity[0] + (1 - alpha) * x
        gravity[1] = alpha * gravity[1] + (1 - alpha) * y
        gravity[2] = alpha * gravity[2] + (1 - alpha) * z
        val lx = x - gravity[0]
        val ly = y - gravity[1]
        val lz = z - gravity[2]
        val mag = sqrt(lx * lx + ly * ly + lz * lz)
        lastMag = mag

        val walking = tsMs < walkingUntilMs
        val threshold = config.threshold * (if (walking) 1.25f else 1f)

        var detected = false
        if (inSegment) {
            // Le pic se termine quand l'intensité retombe OU quand la direction s'inverse
            // (une secousse passe par zéro entre deux échantillons : on ne peut pas attendre un creux).
            val sameDirection = lx * px + ly * py + lz * pz >= 0f
            val tooLong = tsMs - segmentStart > MAX_JOLT_MS
            if (!sameDirection || mag < threshold * 0.5f || tooLong) {
                inSegment = false
                // Un « pic » trop long (poussée lente, vol d'un pas de course) n'est pas une secousse.
                if (!tooLong) detected = addJolt(Jolt(peakTs, px, py, pz, tsMs - segmentStart), tsMs)
                else jolts.clear()
            }
        }
        if (mag >= threshold) {
            if (!inSegment) {
                inSegment = true
                segmentStart = tsMs
                peakMag = 0f
            }
            if (mag > peakMag) {
                peakMag = mag; peakTs = tsMs; px = lx; py = ly; pz = lz
            }
        }
        return detected
    }

    private fun addJolt(jolt: Jolt, nowMs: Long): Boolean {
        if (nowMs < cooldownUntil) return false
        // Séquence interrompue (écart trop long) : on repart de ce pic.
        if (jolts.isNotEmpty()) {
            val gap = jolt.timeMs - jolts.last().timeMs
            if (gap > config.maxIntervalMs || gap < config.minIntervalMs) jolts.clear()
        }
        jolts.add(jolt)
        while (jolts.isNotEmpty() && jolt.timeMs - jolts.first().timeMs > config.windowMs) jolts.removeAt(0)

        // En marchant/courant (capteur de pas), on exige un pic de plus.
        val n = config.minJolts + (if (nowMs < walkingUntilMs) 1 else 0)
        if (jolts.size < n) return false

        // On évalue les n derniers pics.
        val recent = jolts.subList(jolts.size - n, jolts.size)
        var alternations = 0
        for (i in 1 until recent.size) {
            val a = recent[i - 1]
            val b = recent[i]
            val dot = a.x * b.x + a.y * b.y + a.z * b.z
            if (dot < 0f) alternations++
            // Une secousse à la main a des aller-retours de force et de durée comparables.
            // La course, elle, alterne un gros choc bref (appui) et un plateau long et faible (vol).
            val ampRatio = minOf(a.mag, b.mag) / maxOf(a.mag, b.mag)
            val durRatio = maxOf(a.durationMs, b.durationMs).toFloat() / maxOf(1L, minOf(a.durationMs, b.durationMs)).toFloat()
            if (ampRatio < MIN_AMPLITUDE_RATIO || durRatio > MAX_DURATION_RATIO) return false
        }
        val ratio = alternations.toFloat() / (recent.size - 1)
        if (ratio < config.minAlternation) return false

        cooldownUntil = nowMs + config.cooldownMs
        jolts.clear()
        return true
    }

    companion object {
        private const val TAU_MS = 800L
        private const val MAX_JOLT_MS = 220L
        private const val MIN_AMPLITUDE_RATIO = 0.6f
        private const val MAX_DURATION_RATIO = 1.8f
    }
}

/** Utilitaire de test : amplitude d'un échantillon. */
internal fun magnitude(x: Float, y: Float, z: Float): Float = sqrt(x * x + y * y + z * z).let { if (abs(it) < 1e-6) 0f else it }
