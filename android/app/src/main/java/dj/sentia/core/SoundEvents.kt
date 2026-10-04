package dj.sentia.core

/** Catégories de sons que SENTIA signale. Détection locale (modèle YAMNet), expérimentale. */
enum class SoundCategory(val key: String) {
    DOORBELL("doorbell"),
    CAR_HORN("car_horn"),
    ALARM("alarm"),
    RINGTONE("ringtone"),
    BABY_CRY("baby_cry"),
}

/** Niveau de confiance : détermine la formulation prudente (« ressemblant à » / « pouvant ressembler à »). */
enum class Confidence { HIGH, LOW }

data class SoundEvent(val category: SoundCategory, val confidence: Confidence, val score: Float, val timeMs: Long)

object SoundLabels {
    private val map: Map<String, SoundCategory> = mapOf(
        "doorbell" to SoundCategory.DOORBELL,
        "ding-dong" to SoundCategory.DOORBELL,
        "vehicle horn, car horn, honking" to SoundCategory.CAR_HORN,
        "car horn" to SoundCategory.CAR_HORN,
        "honk" to SoundCategory.CAR_HORN,
        "alarm" to SoundCategory.ALARM,
        "smoke detector, smoke alarm" to SoundCategory.ALARM,
        "fire alarm" to SoundCategory.ALARM,
        "siren" to SoundCategory.ALARM,
        "buzzer" to SoundCategory.ALARM,
        "telephone bell ringing" to SoundCategory.RINGTONE,
        "ringtone" to SoundCategory.RINGTONE,
        "telephone" to SoundCategory.RINGTONE,
        "baby cry, infant cry" to SoundCategory.BABY_CRY,
        "crying, sobbing" to SoundCategory.BABY_CRY,
    )

    fun categoryFor(label: String): SoundCategory? = map[label.trim().lowercase()]
}

/**
 * Filtre : seuil de score, niveau de confiance, et anti-répétition par catégorie
 * (un même son prolongé ne déclenche pas dix notifications).
 */
class SoundEventFilter(
    private val minScore: Float = 0.5f,
    private val highScore: Float = 0.75f,
    private val debounceMs: Long = 15_000,
) {
    private val lastByCategory = HashMap<SoundCategory, Long>()

    fun accept(label: String, score: Float, nowMs: Long): SoundEvent? {
        val category = SoundLabels.categoryFor(label) ?: return null
        if (score < minScore) return null
        val last = lastByCategory[category]
        if (last != null && nowMs - last < debounceMs) return null
        lastByCategory[category] = nowMs
        return SoundEvent(category, if (score >= highScore) Confidence.HIGH else Confidence.LOW, score, nowMs)
    }
}
