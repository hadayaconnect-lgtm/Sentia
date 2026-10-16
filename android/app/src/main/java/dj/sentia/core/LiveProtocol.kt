package dj.sentia.core

/**
 * Conversation en direct (Gemini Live) : messages envoyés et reçus par le WebSocket. Logique pure, testée sans Android.
 * Les noms de champs suivent la référence officielle de la Live API (BidiGenerateContent).
 */
sealed class LiveEvent {
    object SetupComplete : LiveEvent()
    /** Voix de SENTIA (PCM 16 bits, 24 kHz) en base64. */
    class Audio(val b64: String) : LiveEvent()
    /** La personne a pris la parole : il faut vider ce qui restait à jouer. */
    object Interrupted : LiveEvent()
    object TurnComplete : LiveEvent()
    /** `complete` : le fournisseur donne la phrase entière d'un coup (OpenAI), sinon ce sont des morceaux à cumuler (Gemini). */
    class InputText(val text: String, val complete: Boolean = false) : LiveEvent()
    class OutputText(val text: String) : LiveEvent()
    class ToolCall(val calls: List<Call>) : LiveEvent()
    class ToolCancel(val ids: List<String>) : LiveEvent()
    class GoAway(val timeLeftMs: Long) : LiveEvent()
    class Resumption(val handle: String, val resumable: Boolean) : LiveEvent()
    class Failure(val message: String) : LiveEvent()

    class Call(val id: String, val name: String, val args: Map<String, Any?>)
}

object LiveProtocol {
    const val MIME_AUDIO_IN = "audio/pcm;rate=16000"
    const val SAMPLE_RATE_IN = 16_000
    const val SAMPLE_RATE_OUT = 24_000

    /** Premier message. `toolsJson` : le tableau « tools » reçu du serveur, tel quel. `resumeHandle` : reprise après coupure. */
    fun setup(model: String, instruction: String, toolsJson: String, resumeHandle: String? = null): String {
        val resumption = if (resumeHandle.isNullOrEmpty()) "{}" else "{\"handle\":${MiniJson.quote(resumeHandle)}}"
        return "{\"setup\":{" +
            "\"model\":${MiniJson.quote(model)}," +
            "\"generationConfig\":{\"responseModalities\":[\"AUDIO\"]}," +
            "\"systemInstruction\":{\"parts\":[{\"text\":${MiniJson.quote(instruction)}}]}," +
            "\"tools\":$toolsJson," +
            "\"inputAudioTranscription\":{}," +
            "\"outputAudioTranscription\":{}," +
            "\"sessionResumption\":$resumption," +
            "\"contextWindowCompression\":{\"triggerTokens\":80000,\"slidingWindow\":{\"targetTokens\":40000}}" +
            "}}"
    }

    fun audio(b64: String) = "{\"realtimeInput\":{\"audio\":{\"mimeType\":\"$MIME_AUDIO_IN\",\"data\":\"$b64\"}}}"

    /** Une photo (JPEG). Une seule par demande : jamais de flux d'images. */
    fun photo(b64: String) = "{\"realtimeInput\":{\"video\":{\"mimeType\":\"image/jpeg\",\"data\":\"$b64\"}}}"

    fun text(t: String) = "{\"realtimeInput\":{\"text\":${MiniJson.quote(t)}}}"

    fun audioStreamEnd() = "{\"realtimeInput\":{\"audioStreamEnd\":true}}"

    fun toolResponse(id: String, name: String, message: String, isError: Boolean = false): String {
        val key = if (isError) "error" else "result"
        return "{\"toolResponse\":{\"functionResponses\":[{\"id\":${MiniJson.quote(id)},\"name\":${MiniJson.quote(name)}," +
            "\"response\":{\"$key\":${MiniJson.quote(message)}}}]}}"
    }

    /** Décode un message du serveur. Un même message peut contenir plusieurs événements (audio + fin de tour…). */
    fun parse(json: String): List<LiveEvent> {
        val m = MiniJson.parseObject(json) ?: return listOf(LiveEvent.Failure("message illisible"))
        val out = ArrayList<LiveEvent>()
        if (m.containsKey("setupComplete")) out.add(LiveEvent.SetupComplete)

        (m["serverContent"] as? Map<*, *>)?.let { sc ->
            val parts = ((sc["modelTurn"] as? Map<*, *>)?.get("parts") as? List<*>).orEmpty()
            for (p in parts) {
                val inline = (p as? Map<*, *>)?.get("inlineData") as? Map<*, *> ?: continue
                val mime = inline["mimeType"] as? String ?: ""
                val data = inline["data"] as? String
                if (data != null && mime.startsWith("audio/")) out.add(LiveEvent.Audio(data))
            }
            (sc["inputTranscription"] as? Map<*, *>)?.get("text")?.let { if (it is String && it.isNotEmpty()) out.add(LiveEvent.InputText(it)) }
            (sc["outputTranscription"] as? Map<*, *>)?.get("text")?.let { if (it is String && it.isNotEmpty()) out.add(LiveEvent.OutputText(it)) }
            if (sc["interrupted"] == true) out.add(LiveEvent.Interrupted)
            if (sc["turnComplete"] == true) out.add(LiveEvent.TurnComplete)
        }

        (m["toolCall"] as? Map<*, *>)?.let { tc ->
            val calls = (tc["functionCalls"] as? List<*>).orEmpty().mapNotNull { c ->
                val cm = c as? Map<*, *> ?: return@mapNotNull null
                val name = cm["name"] as? String ?: return@mapNotNull null
                @Suppress("UNCHECKED_CAST")
                LiveEvent.Call((cm["id"] as? String).orEmpty(), name, (cm["args"] as? Map<String, Any?>).orEmpty())
            }
            if (calls.isNotEmpty()) out.add(LiveEvent.ToolCall(calls))
        }
        (m["toolCallCancellation"] as? Map<*, *>)?.let { tc ->
            out.add(LiveEvent.ToolCancel((tc["ids"] as? List<*>).orEmpty().filterIsInstance<String>()))
        }
        (m["goAway"] as? Map<*, *>)?.let { out.add(LiveEvent.GoAway(durationMs(it["timeLeft"]))) }
        (m["sessionResumptionUpdate"] as? Map<*, *>)?.let {
            out.add(LiveEvent.Resumption((it["newHandle"] as? String).orEmpty(), it["resumable"] == true))
        }
        (m["error"] as? Map<*, *>)?.let { out.add(LiveEvent.Failure((it["message"] as? String) ?: "erreur du serveur")) }
        return out
    }

    /** « 30s », « 1.5s » → millisecondes (30 s par défaut si illisible). */
    fun durationMs(v: Any?): Long {
        val s = (v as? String)?.trim() ?: return 30_000L
        return s.removeSuffix("s").toDoubleOrNull()?.let { (it * 1000).toLong() } ?: 30_000L
    }
}

/**
 * Une photo par demande dans la conversation en direct : l'IA ne peut pas en prendre une deuxième tant que la demande en cours
 * n'est pas terminée (fin de tour ou interruption).
 */
class LivePhotoGate {
    private var used = false
    fun tryTake(): Boolean { if (used) return false; used = true; return true }
    fun turnEnded() { used = false }
}

/** Texte de la personne cumulé pendant son tour de parole, pour reconnaître « Stop » tout de suite. */
class SpokenText {
    private val sb = StringBuilder()
    fun add(t: String): String { sb.append(t); return sb.toString().trim() }
    fun reset() { sb.setLength(0) }
}

/** Veille automatique : arrête la session après un long silence, pour ne pas consommer la batterie ni le crédit. */
class IdleWatch(private val limitMs: Long) {
    private var last = 0L
    fun activity(nowMs: Long) { last = nowMs }
    fun expired(nowMs: Long) = last != 0L && nowMs - last > limitMs
}

object LiveReconnect {
    const val MAX_ATTEMPTS = 3
    /** Attente avant la tentative n (1, 2, 3…) : 0,5 s, 1 s, 2 s. */
    fun delayMs(attempt: Int): Long = 500L shl (attempt - 1).coerceIn(0, 4)
    fun canRetry(attempt: Int) = attempt <= MAX_ATTEMPTS
}

/** Fin de la conversation demandée à voix haute (« Au revoir », « Termine la conversation »). Distinct de « Stop », qui coupe seulement la parole. */
object LiveEnd {
    private val RX = Regex("^\\W*(sentia[ ,]+)?(au revoir|à bientôt|a bientot|bonne journée|termine( la conversation)?|quitte|ferme sentia|goodbye|bye|see you|مع السلامة)([ ,]+sentia)?\\W*$", RegexOption.IGNORE_CASE)
    fun isGoodbye(text: String) = RX.containsMatchIn(text.trim())
}
