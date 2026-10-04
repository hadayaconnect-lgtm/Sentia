package dj.sentia.core

/**
 * Vocabulaire de vibrations, simple et mémorisable (durées en ms : pause, vibration, pause, vibration…).
 *  - AWAKE     : 1 vibration courte  = « SENTIA est réveillé »
 *  - INFO      : 2 vibrations courtes = « nouvelle information »
 *  - ATTENTION : 1 vibration longue   = « attention »
 *  - DETECTED  : 3 vibrations courtes = « événement détecté »
 */
enum class HapticPattern(val key: String, val timings: LongArray) {
    AWAKE("awake", longArrayOf(0, 80)),
    INFO("info", longArrayOf(0, 80, 120, 80)),
    ATTENTION("attention", longArrayOf(0, 700)),
    DETECTED("detected", longArrayOf(0, 80, 100, 80, 100, 80));

    companion object {
        fun fromKey(key: String?): HapticPattern? = entries.firstOrNull { it.key == key }
    }
}
