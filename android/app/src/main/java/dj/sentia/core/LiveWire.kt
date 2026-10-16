package dj.sentia.core

import java.net.URLEncoder

/**
 * Langage d'un fournisseur de conversation en direct (messages envoyés et reçus). Logique pure, testée sans Android.
 * Deux fournisseurs : OpenAI (Realtime) et Gemini (Live). Tout le reste (micro, voix, photo, reconnexion) est commun.
 */
interface LiveWire {
    val name: String
    /** Fréquence du micro envoyé au serveur. */
    val sampleRateIn: Int

    /** Adresse complète du WebSocket (le jeton y est ajouté si le fournisseur le demande ainsi). */
    fun url(base: String, token: String): String

    /** En-têtes HTTP à envoyer à l'ouverture (jeton dans l'en-tête si le fournisseur le demande ainsi). */
    fun headers(token: String): Map<String, String>

    /** Messages à envoyer dès que le WebSocket est ouvert (configuration de la session). */
    fun open(model: String, instruction: String, toolsJson: String, voice: String, resumeHandle: String?): List<String>

    fun audio(b64: String): String

    /** Un texte écrit par l'application, auquel l'IA répond. */
    fun text(t: String): List<String>

    /** Résultat d'un outil + la photo (UNE image JPEG) que l'IA doit regarder. */
    fun photoResult(id: String, name: String, b64: String, message: String): List<String>

    fun toolResult(id: String, name: String, message: String, isError: Boolean = false): List<String>

    fun parse(json: String): List<LiveEvent>

    companion object {
        fun forProvider(provider: String): LiveWire = if (provider.equals("gemini", ignoreCase = true)) GeminiWire else OpenAiWire
    }
}

object GeminiWire : LiveWire {
    override val name = "gemini"
    override val sampleRateIn = LiveProtocol.SAMPLE_RATE_IN

    override fun url(base: String, token: String): String {
        val sep = if (base.contains('?')) "&" else "?"
        return base + sep + "access_token=" + URLEncoder.encode(token, "UTF-8")
    }

    override fun headers(token: String): Map<String, String> = emptyMap()

    override fun open(model: String, instruction: String, toolsJson: String, voice: String, resumeHandle: String?) =
        listOf(LiveProtocol.setup(model, instruction, toolsJson, resumeHandle))

    override fun audio(b64: String) = LiveProtocol.audio(b64)
    override fun text(t: String) = listOf(LiveProtocol.text(t))
    override fun photoResult(id: String, name: String, b64: String, message: String) =
        listOf(LiveProtocol.photo(b64), LiveProtocol.toolResponse(id, name, message))
    override fun toolResult(id: String, name: String, message: String, isError: Boolean) =
        listOf(LiveProtocol.toolResponse(id, name, message, isError))
    override fun parse(json: String) = LiveProtocol.parse(json)
}

/**
 * OpenAI Realtime (API générale) : le jeton temporaire « ek_… » passe dans l'en-tête Authorization.
 * La voix part en PCM 16 bits 24 kHz dans les deux sens.
 */
object OpenAiWire : LiveWire {
    override val name = "openai"
    const val RATE = 24_000
    override val sampleRateIn = RATE

    override fun url(base: String, token: String) = base

    override fun headers(token: String) = mapOf("Authorization" to "Bearer $token")

    override fun open(model: String, instruction: String, toolsJson: String, voice: String, resumeHandle: String?): List<String> {
        val v = voice.ifEmpty { "marin" }
        return listOf(
            "{\"type\":\"session.update\",\"session\":{" +
                "\"type\":\"realtime\"," +
                "\"output_modalities\":[\"audio\"]," +
                "\"instructions\":${MiniJson.quote(instruction)}," +
                "\"tools\":$toolsJson," +
                "\"tool_choice\":\"auto\"," +
                "\"audio\":{" +
                "\"input\":{\"format\":{\"type\":\"audio/pcm\",\"rate\":$RATE}," +
                "\"transcription\":{\"model\":\"gpt-4o-mini-transcribe\"}," +
                "\"turn_detection\":{\"type\":\"semantic_vad\"}}," +
                "\"output\":{\"format\":{\"type\":\"audio/pcm\",\"rate\":$RATE},\"voice\":${MiniJson.quote(v)}}" +
                "}}}"
        )
    }

    override fun audio(b64: String) = "{\"type\":\"input_audio_buffer.append\",\"audio\":\"$b64\"}"

    private const val RESPOND = "{\"type\":\"response.create\"}"

    override fun text(t: String) = listOf(
        "{\"type\":\"conversation.item.create\",\"item\":{\"type\":\"message\",\"role\":\"user\",\"content\":[{\"type\":\"input_text\",\"text\":${MiniJson.quote(t)}}]}}",
        RESPOND,
    )

    private fun output(id: String, message: String) =
        "{\"type\":\"conversation.item.create\",\"item\":{\"type\":\"function_call_output\",\"call_id\":${MiniJson.quote(id)},\"output\":${MiniJson.quote(message)}}}"

    override fun photoResult(id: String, name: String, b64: String, message: String) = listOf(
        output(id, message),
        "{\"type\":\"conversation.item.create\",\"item\":{\"type\":\"message\",\"role\":\"user\",\"content\":[{\"type\":\"input_image\",\"image_url\":\"data:image/jpeg;base64,$b64\"}]}}",
        RESPOND,
    )

    override fun toolResult(id: String, name: String, message: String, isError: Boolean) =
        listOf(output(id, if (isError) "Erreur : $message" else message), RESPOND)

    override fun parse(json: String): List<LiveEvent> {
        val m = MiniJson.parseObject(json) ?: return listOf(LiveEvent.Failure("message illisible"))
        return when (m["type"] as? String) {
            "session.updated" -> listOf(LiveEvent.SetupComplete)
            "response.output_audio.delta", "response.audio.delta" ->
                (m["delta"] as? String)?.takeIf { it.isNotEmpty() }?.let { listOf(LiveEvent.Audio(it)) } ?: emptyList()
            "response.output_audio_transcript.delta", "response.audio_transcript.delta" ->
                (m["delta"] as? String)?.takeIf { it.isNotEmpty() }?.let { listOf(LiveEvent.OutputText(it)) } ?: emptyList()
            "conversation.item.input_audio_transcription.completed" ->
                (m["transcript"] as? String)?.takeIf { it.isNotBlank() }?.let { listOf(LiveEvent.InputText(it, complete = true)) } ?: emptyList()
            // La personne prend la parole : le serveur coupe sa réponse ; l'application vide ce qui restait à jouer.
            "input_audio_buffer.speech_started" -> listOf(LiveEvent.Interrupted)
            "response.output_item.done" -> {
                val item = m["item"] as? Map<*, *>
                if (item?.get("type") == "function_call") {
                    val name = item["name"] as? String
                    if (name == null) emptyList() else {
                        val args = (item["arguments"] as? String)?.let { MiniJson.parseObject(it) }.orEmpty()
                        listOf(LiveEvent.ToolCall(listOf(LiveEvent.Call((item["call_id"] as? String).orEmpty(), name, args))))
                    }
                } else emptyList()
            }
            // Une réponse qui contient un appel d'outil n'est PAS la fin du tour : la vraie réponse suit après l'outil.
            "response.done" -> {
                val out = ((m["response"] as? Map<*, *>)?.get("output") as? List<*>).orEmpty()
                if (out.any { (it as? Map<*, *>)?.get("type") == "function_call" }) emptyList() else listOf(LiveEvent.TurnComplete)
            }
            "error" -> listOf(LiveEvent.Failure(((m["error"] as? Map<*, *>)?.get("message") as? String) ?: "erreur du serveur"))
            else -> emptyList()
        }
    }
}
