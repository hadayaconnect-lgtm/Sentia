package dj.sentia

import android.content.Context
import android.util.Log
import dj.sentia.core.StageTimes

/**
 * Chronomètre de chaque étape d'une analyse : réveil → écran → caméra → photo → validation → compression → envoi →
 * serveur + IA → réception → début de la voix. Rien n'est envoyé : le résultat est gardé sur le téléphone
 * (Réglages → Diagnostic) et écrit dans le journal Android (étiquette « SentiaPerf »). La logique est dans core/StageTimes.
 */
object Perf {
    private val s = StageTimes()

    @Synchronized fun begin() = s.begin()

    /** Début d'une demande qui n'a pas été précédée d'un réveil (bouton, ouverture de l'application) : on repart de zéro. */
    @Synchronized fun beginIfStale() { if (s.isStale()) s.begin() }

    @Synchronized fun mark(k: String) = s.mark(k)
    @Synchronized fun markFirst(k: String) = s.markFirst(k)
    @Synchronized fun put(k: String, ms: Long) = s.put(k, ms)
    @Synchronized fun photoSize(bytes: Int) = s.photoSize(bytes)
    @Synchronized fun retake() = s.retake()
    @Synchronized fun has(k: String) = s.has(k)

    /** Début de la voix de la RÉPONSE (pas du salut) : l'analyse est terminée, on écrit le rapport. */
    @Synchronized fun ttsStarted(context: Context) {
        val text = s.ttsStarted() ?: return
        context.applicationContext.settings.diagPerf = text
        Log.i("SentiaPerf", text.replace('\n', ' '))
    }
}
