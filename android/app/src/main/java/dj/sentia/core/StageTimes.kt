package dj.sentia.core

/** Chronomètre par étape d'une analyse (logique pure, horloge injectable pour les tests). */
class StageTimes(private val now: () -> Long = { System.currentTimeMillis() }) {
    private val t = HashMap<String, Long>()
    private val d = HashMap<String, Long>()
    private var photoKb = 0
    private var retakes = 0

    fun begin() { t.clear(); d.clear(); photoKb = 0; retakes = 0; t["begin"] = now() }
    fun isStale(): Boolean { val b = t["begin"] ?: 0L; return now() - b > 60_000L || t.containsKey("tts_start") }
    fun mark(k: String) { t[k] = now() }
    fun markFirst(k: String) { if (!t.containsKey(k)) t[k] = now() }
    fun put(k: String, ms: Long) { d[k] = ms }
    fun photoSize(bytes: Int) { photoKb = bytes / 1024 }
    fun retake() { retakes++ }
    fun has(k: String) = t.containsKey(k)

    /** Début de la voix de la RÉPONSE : écrit le rapport (une seule fois par analyse), sinon null. */
    fun ttsStarted(): String? {
        if (!t.containsKey("ai_done") || t.containsKey("tts_start")) return null
        t["tts_start"] = now()
        return report()
    }

    private fun sec(ms: Long?) = if (ms == null || ms < 0) "–" else String.format(java.util.Locale.FRANCE, "%.1f s", ms / 1000.0)
    private fun gap(a: String, b: String): Long? { val x = t[a]; val y = t[b]; return if (x != null && y != null) y - x else null }

    fun report(): String {
        val begin = t["begin"]
        val total = if (begin != null) t["tts_start"]?.minus(begin) else null
        return buildString {
            append("Réveil → écran : ").append(sec(gap("begin", "screen"))).append('\n')
            append("Caméra prête : ").append(sec(d["cam_open"])).append('\n')
            append("Prise de la photo : ").append(sec(d["shot"]))
            if (retakes > 0) append(" (+").append(retakes).append(" nouvelle capture)")
            append('\n')
            append("Validation de l'image : ").append(sec(d["validate"])).append('\n')
            append("Compression (").append(photoKb).append(" Ko) : ").append(sec(d["compress"])).append('\n')
            append("Envoi au serveur : ").append(sec(d["upload"])).append('\n')
            append("Attente serveur + IA : ").append(sec(d["wait"]))
            d["server"]?.let { append(" (dont agent ").append(sec(it)).append(")") }
            append('\n')
            append("Réception : ").append(sec(d["download"])).append('\n')
            append("Voix (délai avant le 1er son) : ").append(sec(gap("ai_done", "tts_start"))).append('\n')
            append("TOTAL : ").append(sec(total))
        }
    }
}
