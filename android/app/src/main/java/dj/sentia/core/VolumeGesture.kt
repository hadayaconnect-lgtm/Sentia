package dj.sentia.core

/**
 * Geste « maintenir les deux touches de volume » (volume haut + bas) pendant [holdMs].
 *
 * Utilisé par le service d'accessibilité, qui reçoit les touches. Règles :
 *  - la première touche passe normalement (le volume change d'un cran, comportement habituel) ;
 *  - dès que la seconde est pressée, les deux touches sont CONSOMMÉES (le volume ne bouge plus) ;
 *  - quand les deux sont tenues assez longtemps, [tick] renvoie true UNE fois.
 * Classe sans dépendance Android : testable sur ordinateur.
 */
class VolumeGesture(private val holdMs: Long = 1000) {
    enum class Key { UP, DOWN }

    private var upDown = false
    private var downDown = false
    private var bothSince = -1L
    private var fired = false

    /** true si les deux touches sont actuellement tenues (le service doit alors appeler [tick] régulièrement). */
    val bothHeld: Boolean get() = bothSince >= 0

    /** Renvoie true si l'événement doit être CONSOMMÉ (non transmis au système). */
    fun onKey(key: Key, down: Boolean, tsMs: Long): Boolean {
        val wasBoth = bothHeld
        if (key == Key.UP) upDown = down else downDown = down

        if (upDown && downDown && !wasBoth) {
            bothSince = tsMs
            fired = false
            return true
        }
        if (wasBoth) {
            if (!upDown || !downDown) {
                // L'une des touches est relâchée : fin du geste. On consomme encore ce relâchement.
                bothSince = -1
            }
            return true
        }
        return false
    }

    /** À appeler périodiquement : true une seule fois quand le maintien est assez long. */
    fun tick(nowMs: Long): Boolean {
        if (bothSince < 0 || fired) return false
        if (nowMs - bothSince >= holdMs) {
            fired = true
            return true
        }
        return false
    }

    fun reset() {
        upDown = false; downDown = false; bothSince = -1; fired = false
    }
}
