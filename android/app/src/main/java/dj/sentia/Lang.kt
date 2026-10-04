package dj.sentia

import java.util.Locale

object Lang {
    val ALL = listOf("fr", "en", "so", "ar")

    fun locale(lang: String): Locale = when (lang) {
        "en" -> Locale.ENGLISH
        "so" -> Locale("so")
        "ar" -> Locale("ar")
        else -> Locale.FRENCH
    }

    private val FR = listOf(" le ", " la ", " les ", " des ", " une ", " est ", " vous ", " et ", " je ", " pas ", " que ", " dans ")
    private val EN = listOf(" the ", " is ", " you ", " and ", " to ", " of ", " it ", " this ", " that ", " are ", " not ", " in ")
    private val SO = listOf(" waa ", " ayaa ", " iyo ", " oo ", " ku ", " ka ", " waxaa ", " fadlan ", " aad ", " ah ", " u ", " ma ")

    /** Devine la langue d'un texte de réponse (pour choisir la voix). Repli : français. */
    fun guess(text: String): String {
        val letters = text.count { it.isLetter() }
        if (letters > 0) {
            val arabic = text.count { it in '؀'..'ۿ' }
            if (arabic * 100 / letters >= 30) return "ar"
        }
        val t = " " + text.lowercase().replace(Regex("[\\p{Punct}\\s]+"), " ") + " "
        val fr = FR.count { t.contains(it) }
        val en = EN.count { t.contains(it) }
        val so = SO.count { t.contains(it) }
        return when {
            so >= 3 && so > fr && so > en -> "so"
            en > fr -> "en"
            else -> "fr"
        }
    }

    /** Langue de l'interface : celle choisie, sinon celle du téléphone si elle est prise en charge, sinon le français. */
    fun ui(chosen: String): String {
        if (chosen in ALL) return chosen
        val sys = Locale.getDefault().language
        return if (sys in ALL) sys else "fr"
    }

    fun cameraPrompt(lang: String): String = when (lang) {
        "en" -> "Describe what you see with the camera."
        "so" -> "Ku sifee waxa kaamarada ku muuqda."
        "ar" -> "صف لي ما تراه الكاميرا."
        else -> "Décris ce que tu vois avec la caméra."
    }
}
