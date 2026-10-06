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

    /**
     * Première analyse (secousse) et bouton « Voir » : description utile à une personne aveugle, texte et billets compris.
     * L'IA décide des photos supplémentaires seulement si nécessaire.
     */
    fun cameraPrompt(lang: String): String = when (lang) {
        "en" -> "Take a photo with the camera now and tell me what is in front of me, for a blind person: people, obstacles, doors, stairs, vehicles, approximate directions (ahead, left, right), useful colours, possible dangers. Also read any visible text and identify any banknote. Three or four short natural sentences, no list."
        "so" -> "Hadda sawir ku qaad kamarada oo ii sheeg waxa hortayda ah, qof indho la' u habboon: dad, caqabado, albaabbo, jaranjaro, gawaarida, jihooyin (hortaada, bidix, midig), midabada muhiimka ah, khatar suurtagal ah. Sidoo kale akhri qoraalka muuqda oo aqoonsi xaashida lacagta haddii ay jirto. Saddex ama afar weedh oo gaaban."
        "ar" -> "التقط الآن صورة بالكاميرا وأخبرني بما أمامي، لشخص كفيف: الأشخاص والعوائق والأبواب والسلالم والمركبات والاتجاهات التقريبية (أمامك، يسارك، يمينك) والألوان المفيدة وأي خطر محتمل. اقرأ أيضا أي نص ظاهر وتعرف على أي ورقة نقدية. ثلاث أو أربع جمل قصيرة وطبيعية دون قوائم."
        else -> "Prends une photo avec la caméra maintenant et dis-moi ce qu'il y a devant moi, pour une personne aveugle : personnes, obstacles, portes, escaliers, véhicules, directions approximatives (devant, à gauche, à droite), couleurs utiles, dangers éventuels. Lis aussi les textes visibles et identifie les billets de banque s'il y en a. Trois ou quatre phrases courtes et naturelles, sans liste."
    }

    /** « Quel est ce billet ? » : nouvelle photo, identification prudente. */
    fun banknotePrompt(lang: String): String = when (lang) {
        "en" -> "Take a photo now of the banknote in front of the camera and identify its currency and value, only if it is clearly visible. If not sure, say so and ask me to bring it closer."
        "so" -> "Hadda sawir ku qaad xaashida lacagta ee kamarada horteeda oo sheeg lacagta iyo qiimaha, kaliya haddii si cad loo arko. Haddii aadan hubin, sidaas sheeg oo iga codso inaan u soo dhaweeyo kamarada."
        "ar" -> "التقط الآن صورة للورقة النقدية أمام الكاميرا وحدد عملتها وقيمتها فقط إذا كانت واضحة. إذا لم تكن متأكدا فقل ذلك واطلب مني تقريبها من الكاميرا."
        else -> "Prends une photo maintenant du billet devant la caméra et identifie sa monnaie et sa valeur, seulement si c'est bien visible. Si tu n'es pas sûre, dis-le et demande-moi de le rapprocher de la caméra."
    }

    /** « Regarde encore » : on exige une NOUVELLE photo. */
    fun lookAgainPrompt(lang: String): String = when (lang) {
        "en" -> "Look again: take a new photo with the camera now and describe what you see."
        "so" -> "Mar kale eeg: hadda sawir cusub ku qaad kamarada oo ku sifee waxa kaa muuqda."
        "ar" -> "انظر مرة أخرى: التقط الآن صورة جديدة بالكاميرا وصف لي ما تراه."
        else -> "Regarde encore : prends une nouvelle photo avec la caméra maintenant et décris ce que tu vois."
    }

    /** « Lis ça » : photo d'un texte, lu en entier. */
    fun readTextPrompt(lang: String): String = when (lang) {
        "en" -> "Take a photo with the camera now and read aloud all the text you can see."
        "so" -> "Hadda sawir ku qaad kamarada oo akhri qoraalka oo dhan ee muuqda."
        "ar" -> "التقط الآن صورة بالكاميرا واقرأ لي كل النص الظاهر."
        else -> "Prends une photo avec la caméra maintenant et lis-moi tout le texte visible."
    }

    /** « Décris la scène » : nouvelle photo, description de l'environnement. */
    fun describeScenePrompt(lang: String): String = when (lang) {
        "en" -> "Take a new photo with the camera now and describe the scene around me."
        "so" -> "Hadda sawir cusub ku qaad kamarada oo ku sifee goobta igu wareegsan."
        "ar" -> "التقط الآن صورة جديدة بالكاميرا وصف لي المشهد من حولي."
        else -> "Prends une nouvelle photo avec la caméra maintenant et décris-moi la scène autour de moi."
    }

    /** « Continue » alors qu'il n'y a rien à reprendre : l'IA poursuit sa réponse. */
    fun continuePrompt(lang: String): String = when (lang) {
        "en" -> "Please continue."
        "so" -> "Fadlan sii wad."
        "ar" -> "من فضلك تابع."
        else -> "Continue, s'il te plaît."
    }

    /** Bouton « Comprendre les sons ». */
    fun soundsPrompt(lang: String): String = when (lang) {
        "en" -> "What sounds did you detect around me recently?"
        "so" -> "Codad noocee ah ayaad dhawaan ku dareentay agtayda?"
        "ar" -> "ما الأصوات التي رصدتها حولي مؤخرا؟"
        else -> "Quels sons as-tu détectés récemment autour de moi ?"
    }
}
