package dj.sentia

import android.content.Context
import android.util.Log

/**
 * Chronomètre de chaque étape d'une analyse : réveil → écran → caméra → photo → compression → envoi → serveur + IA →
 * réception → début de la voix. Rien n'est envoyé : le résultat est gardé sur le téléphone (Réglages → Diagnostic)
 * et écrit dans le journal Android (étiquette « SentiaPerf »).
 */
object Perf {
    private val t = HashMap<String, Long>()
    private val d = HashMap<String, Long>()
    private var photoKb = 0

    @Synchronized fun begin() { t.clear(); d.clear(); photoKb = 0; t["begin"] = System.currentTimeMillis() }

    /** Début d'une demande qui n'a pas été précédée d'un réveil (bouton, ouverture de l'application) : on repart de zéro. */
    @Synchronized fun beginIfStale() {
        val b = t["begin"] ?: 0L
        if (System.currentTimeMillis() - b > 60_000L || t.containsKey("tts_start")) begin()
    }

    @Synchronized fun mark(k: String) { t[k] = System.currentTimeMillis() }
    @Synchronized fun markFirst(k: String) { if (!t.containsKey(k)) t[k] = System.currentTimeMillis() }
    @Synchronized fun put(k: String, ms: Long) { d[k] = ms }
    @Synchronized fun photoSize(bytes: Int) { photoKb = bytes / 1024 }
    @Synchronized fun has(k: String) = t.containsKey(k)

    /** Début de la voix de la RÉPONSE (pas du salut) : l'analyse est terminée, on écrit le rapport. */
    @Synchronized fun ttsStarted(context: Context) {
        if (!t.containsKey("ai_done") || t.containsKey("tts_start")) return
        t["tts_start"] = System.currentTimeMillis()
        val text = report()
        context.applicationContext.settings.diagPerf = text
        Log.i("SentiaPerf", text.replace('\n', ' '))
    }

    private fun sec(ms: Long?) = if (ms == null || ms < 0) "–" else String.format(java.util.Locale.FRANCE, "%.1f s", ms / 1000.0)
    private fun gap(a: String, b: String): Long? { val x = t[a]; val y = t[b]; return if (x != null && y != null) y - x else null }

    private fun report(): String {
        val begin = t["begin"]
        val total = if (begin != null) t["tts_start"]?.minus(begin) else null
        return buildString {
            append("Réveil → écran : ").append(sec(gap("begin", "screen"))).append('\n')
            append("Caméra prête : ").append(sec(d["cam_open"])).append('\n')
            append("Prise de la photo : ").append(sec(d["shot"])).append('\n')
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
