package dj.sentia.core

import kotlin.coroutines.cancellation.CancellationException

/**
 * Pipeline photo : RÉVEIL → UNE photo → vérification → IA → voix. Logique pure (sans Android), testée dans core-test.
 *
 * Aucun de ces délais n'est une « attente avant d'analyser » : la photo est prise tout de suite ; ce sont des MAXIMUMS par étape.
 */
object AnalysisPolicy {
    /** Caméra + exposition + prise + vérification + éventuelle 2e capture. Au-delà : « Je n'arrive pas à obtenir une image claire ». */
    const val CAPTURE_MAX_MS = 8_000L
    /** Envoi + serveur + IA, compté À PARTIR de l'envoi de la photo (le temps de capture n'est pas retiré à l'IA). */
    const val AI_MAX_MS = 25_000L
    /** Phrase de patience dite UNE fois si la réponse tarde (jamais un délai avant d'analyser, jamais un arrêt). */
    const val REASSURE_AT_MS = 12_000L
    /** Au plus une nouvelle capture automatique, et seulement si la première est réellement invalide. */
    const val MAX_ATTEMPTS = 2
    /** Pause avant la 2e capture (l'exposition se stabilise). */
    const val RETRY_PAUSE_MS = 500L
    /** Le serveur refuse au-delà de ~1,3 Mo. */
    const val MAX_BYTES = 1_200_000
    /** Taille maximale du grand côté : lisible pour un billet ou un petit texte, sans envoyer 12 Mpx. */
    const val MAX_SIDE = 1600
    const val MAX_SIDE_COLOR = 1280
    /** Temps minimal restant pour retenter après un échec réseau. */
    const val RETRY_MIN_LEFT_MS = 6_000L
    /** Sous ce temps restant, inutile de lancer un appel à l'IA. */
    const val MIN_CALL_MS = 1_500L
}

/** Une photo prête à être envoyée (JPEG en base64) avec ce qu'il faut pour la vérifier. */
class Shot(val b64: String, val brightness: Int, val bytes: Int)

object PhotoCheck {
    const val MIN_BRIGHTNESS = 8
    /** Un JPEG de quelques centaines d'octets n'est pas une photo (capture vide ou corrompue). */
    const val MIN_BYTES = 2_000

    /** Image noire (objectif couvert, caméra pas prête), vide ou manifestement inutilisable → faux. */
    fun isUsable(s: Shot) = s.b64.isNotEmpty() && s.bytes >= MIN_BYTES && s.brightness >= MIN_BRIGHTNESS
}

/**
 * Prend la photo, la vérifie, et au plus UNE fois en reprend une si la première est invalide (ou si la capture échoue).
 * `release` (fermeture de la caméra) est TOUJOURS appelé en sortant : succès, échec, annulation ou délai.
 * @return la première photo valide, ou null si les deux tentatives ont échoué : on n'envoie alors RIEN à l'IA.
 */
inline fun <T : Any> captureValid(shoot: (attempt: Int) -> T?, usable: (T) -> Boolean, release: () -> Unit): T? {
    try {
        for (attempt in 1..AnalysisPolicy.MAX_ATTEMPTS) {
            val photo = try {
                shoot(attempt)
            } catch (e: CancellationException) {
                throw e // l'annulation (nouvelle demande, délai) ne doit jamais être avalée
            } catch (e: Exception) {
                null // échec de capture : compte comme une photo invalide
            }
            if (photo != null && usable(photo)) return photo
        }
        return null
    } finally {
        release()
    }
}

/** Une seule photo par demande : la photo jointe par l'application OU celle que l'IA demande par l'outil, jamais les deux. */
class PhotoBudget {
    private var taken = 0
    val count get() = taken
    fun tryTake(): Boolean { if (taken >= 1) return false; taken++; return true }
}

/** Décision de retenter après un échec : réseau seulement (jamais un délai), une seule fois, s'il reste assez de temps. */
object RetryPolicy {
    fun shouldRetryNetwork(isNetwork: Boolean, alreadyRetried: Boolean, remainingMs: Long?): Boolean =
        isNetwork && !alreadyRetried && (remainingMs == null || remainingMs > AnalysisPolicy.RETRY_MIN_LEFT_MS)
}

/**
 * Questions sur l'instant présent ou la sécurité : la scène a peut-être changé, on prend TOUJOURS une nouvelle photo.
 * Les autres questions de suivi (« Qu'est-ce que c'est ? », « Et la personne à droite ? ») réutilisent la photo déjà analysée.
 */
object FreshPhoto {
    private val RX = Regex(
        "(avanc|march|travers|je peux passer|peut-on passer|libre|dégag|danger|obstacle|devant moi|droit devant|maintenant|en ce moment|" +
            "c'est sûr|sécuris|can i (walk|go|move|cross|pass)|is it (clear|safe)|is there an obstacle|in front of me|right now|" +
            "أتقدم|هل الطريق|عائق|خطر|آمن|hor u socod|caqabad|khatar)",
        RegexOption.IGNORE_CASE,
    )
    fun needed(prompt: String) = RX.containsMatchIn(prompt)
}
