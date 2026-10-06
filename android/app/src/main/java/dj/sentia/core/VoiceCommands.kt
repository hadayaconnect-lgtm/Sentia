package dj.sentia.core

import java.text.Normalizer

/** Commandes vocales courtes, reconnues localement (sans passer par l'IA) pour réagir tout de suite. */
enum class VoiceCommand { STOP, PAUSE, CONTINUE, REPEAT, LOOK_AGAIN, READ_TEXT, DESCRIBE_SCENE, BANKNOTE, CLOSE_CAMERA }

/**
 * Reconnaissance de commandes dans le texte transcrit (français, anglais, somali, arabe).
 * Une commande doit être une phrase COURTE : « Stop », « Continue », « Regarde encore »…
 * Une phrase plus longue (« arrête de me parler de ça et dis-moi l'heure ») est envoyée à l'IA.
 * Classe sans dépendance Android : testable sur ordinateur.
 */
object VoiceCommands {

    /** Minuscules, sans accents ni ponctuation (l'arabe est conservé, sans voyelles ni signes). */
    fun normalize(text: String): String {
        val decomposed = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
        val sb = StringBuilder(decomposed.length)
        for (ch in decomposed) {
            val type = Character.getType(ch)
            when {
                type == Character.NON_SPACING_MARK.toInt() -> Unit // accents et voyelles arabes
                ch.isLetterOrDigit() -> sb.append(ch)
                else -> sb.append(' ')
            }
        }
        return sb.toString().replace('ة', 'ه').replace('ى', 'ي').trim().replace(Regex("\\s+"), " ")
    }

    private fun norm(set: Set<String>): Set<String> = set.map { normalize(it) }.toSet()

    // Mots de politesse ou de remplissage autorisés autour d'une commande.
    private val FILLERS = setOf(
        "sentia", "s", "il", "te", "vous", "plait", "svp", "stp", "merci", "alors", "donc", "maintenant", "please", "now",
        "thanks", "ok", "okay", "tu", "peux", "pouvez", "pourrais", "pourriez", "can", "you", "could", "fadlan",
        "من", "فضلك", "لو", "سمحت",
    )

    private val STOP = setOf(
        "stop", "stoppe", "stoppez", "arrete", "arretez", "arrete toi", "arretez vous", "tais toi", "taisez vous", "silence", "chut",
        "ca suffit", "assez", "c est bon", "stop it", "stop talking", "be quiet", "enough", "shut up", "jooji", "joojiya", "aamus",
        "قف", "توقف", "اسكت", "كفي", "يكفي", "بس", "وقف",
    )
    private val PAUSE = setOf(
        "pause", "mets en pause", "mets pause", "mettre en pause", "attends", "attendez", "une seconde", "un instant", "un moment",
        "wait", "hold on", "hold on a second", "wait a second", "sug", "انتظر", "لحظه", "استراحه", "ايقاف موقت",
    )
    private val CONTINUE = setOf(
        "continue", "continuer", "continuez", "reprends", "reprenez", "reprendre", "vas y", "allez y", "poursuis", "poursuivez",
        "la suite", "suite", "continue de parler", "continue a parler", "go on", "go ahead", "resume", "keep going", "carry on", "continue talking",
        "sii wad", "sii wado", "sii wad hadalka", "تابع", "استمر", "كمل", "واصل", "اكمل", "تابع الكلام",
    )
    private val REPEAT = setOf(
        "repete", "repetes", "repete moi", "repetez", "repeter", "redis", "redis moi", "dis le encore", "encore une fois", "pardon",
        "repeat", "repeat that", "say again", "say that again", "again", "ku celi", "mar kale ku celi", "كرر", "اعد", "عيد", "كرر ذلك", "اعد ذلك",
    )
    private val LOOK_AGAIN = setOf(
        "regarde encore", "regarde a nouveau", "regarde de nouveau", "regarde encore une fois", "re regarde", "regarde une autre fois",
        "nouvelle analyse", "nouvelle photo", "prends une autre photo", "reprends une photo", "look again", "look once more",
        "regarde devant moi", "regarde devant", "look in front of me", "scan again", "take another photo", "take another picture", "mar kale eeg", "انظر مره اخري", "انظر مجددا", "شوف مره ثانيه", "صور مره اخري",
    )
    private val READ_TEXT = setOf(
        "lis ca", "lis moi ca", "lis ce texte", "lis le texte", "lis ce document", "lis le document", "lis ce qui est ecrit",
        "lis moi ce texte", "lis moi le texte", "lis le panneau", "lis le document", "lis l etiquette", "lis moi le panneau", "lis moi le document", "lis", "read this", "read it", "read this text", "read that", "read the text",
        "akhri kan", "akhri qoraalkan", "اقرا هذا", "اقرا النص", "اقرا لي هذا", "اقرا", "اقرا لي النص",
    )
    private val DESCRIBE_SCENE = setOf(
        "decris la scene", "decris moi la scene", "decris la scene devant moi", "decris l environnement", "decris moi l environnement",
        "decris la piece", "decris ce qui m entoure", "describe the scene", "describe the scene again", "describe the surroundings",
        "describe what is around me", "ku sifee goobta", "صف المشهد", "صف لي المشهد", "صف ما حولي",
    )
    private val BANKNOTE = setOf(
        "quel est ce billet", "quel billet est ce", "c est quel billet", "quel billet", "identifie ce billet", "identifie le billet",
        "reconnais ce billet", "combien vaut ce billet", "what banknote is this", "which banknote is this", "what bill is this",
        "identify this bill", "identify this banknote", "billet", "yaa waa xaashidan lacagta", "ما هذه الورقة النقدية", "اي ورقة نقدية هذه", "كم قيمة هذه الورقة",
    )
    private val CLOSE_CAMERA = setOf(
        "ferme la camera", "fermer la camera", "ferme camera", "quitte la camera", "arrete la camera", "desactive la camera",
        "eteins la camera", "close the camera", "close camera", "turn off the camera", "exit camera", "xidh kamarada",
        "اغلق الكاميرا", "اقفل الكاميرا", "اطفئ الكاميرا",
    )
    private val RETRY = setOf(
        "oui", "ouais", "d accord", "ok", "okay", "reessaie", "reessayer", "reessaye", "oui reessaie", "oui s il te plait", "oui s il vous plait",
        "yes", "yeah", "sure", "try again", "haa", "haye", "نعم", "اجل", "حسنا", "طيب",
    )

    private val TABLE: List<Pair<VoiceCommand, Set<String>>> = listOf(
        VoiceCommand.LOOK_AGAIN to norm(LOOK_AGAIN),
        VoiceCommand.DESCRIBE_SCENE to norm(DESCRIBE_SCENE),
        VoiceCommand.BANKNOTE to norm(BANKNOTE),
        VoiceCommand.CLOSE_CAMERA to norm(CLOSE_CAMERA),
        VoiceCommand.READ_TEXT to norm(READ_TEXT),
        VoiceCommand.REPEAT to norm(REPEAT),
        VoiceCommand.CONTINUE to norm(CONTINUE),
        VoiceCommand.PAUSE to norm(PAUSE),
        VoiceCommand.STOP to norm(STOP),
    )
    private val RETRY_N = norm(RETRY)
    private val FILLERS_N = norm(FILLERS)

    /** Retire les mots de politesse au début et à la fin (« stop s'il te plaît », « continue merci »). */
    private fun trimFillers(normalized: String): String {
        val words = normalized.split(' ').filter { it.isNotEmpty() }.toMutableList()
        while (words.size > 1 && words.first() in FILLERS_N) words.removeAt(0)
        while (words.size > 1 && words.last() in FILLERS_N) words.removeAt(words.size - 1)
        return words.joinToString(" ")
    }

    /** @return la commande si tout le message en est une, sinon null (le message part alors à l'IA). */
    fun parse(text: String): VoiceCommand? {
        val n = normalize(text)
        if (n.isEmpty() || n.split(' ').size > 6) return null
        val candidates = listOf(n, trimFillers(n)).distinct()
        for (c in candidates) for ((cmd, phrases) in TABLE) if (c in phrases) return cmd
        return null
    }

    /** « Oui », « d'accord » : réponse à « Voulez-vous réessayer ? ». */
    fun isRetry(text: String): Boolean {
        val n = normalize(text)
        if (n.isEmpty() || n.split(' ').size > 5) return false
        return n in RETRY_N || trimFillers(n) in RETRY_N
    }

    // Phrases que la reconnaissance vocale invente parfois quand elle n'entend que du silence ou du bruit.
    private val NOISE_SNIPPETS = listOf(
        "sous titrage", "sous titres", "sous titre", "merci d avoir regarde", "merci d avoir suivi", "abonnez vous", "amara org",
        "thank you for watching", "thanks for watching", "subtitles by", "subscribe to", "like and subscribe", "please subscribe",
    )

    /** Vrai si la transcription est vide ou ressemble à une phrase inventée à partir de silence. */
    fun isNoise(text: String): Boolean {
        val n = normalize(text)
        if (n.isEmpty()) return true
        return NOISE_SNIPPETS.any { n.contains(it) }
    }
}
