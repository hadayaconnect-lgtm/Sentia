package dj.sentia.core

/**
 * Geste « trois appuis rapides sur Volume + » : trois pressions de la touche Volume haut en moins de [windowMs].
 * Les deux premières passent normalement au système ; la troisième est CONSOMMÉE (le volume ne monte pas d'un cran de plus).
 * Classe sans dépendance Android : testable sur ordinateur.
 */
class VolumeTriple(private val windowMs: Long = 1800, private val presses: Int = 3) {
    private val times = ArrayList<Long>()

    /** À appeler à chaque appui (ACTION_DOWN, sans répétition) sur Volume +. Renvoie true quand le geste est reconnu. */
    fun onUpPress(tsMs: Long): Boolean {
        times.removeAll { tsMs - it > windowMs }
        times.add(tsMs)
        if (times.size >= presses) { times.clear(); return true }
        return false
    }

    fun reset() = times.clear()
}
