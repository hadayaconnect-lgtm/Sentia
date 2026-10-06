package dj.sentia

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * Un tour complet : demande de l'utilisateur → (outils éventuels, au plus 5 tours) → réponse finale.
 *
 * Analyse caméra : au plus [ANALYSIS_MAX_MS] (10 s) entre le début de l'analyse (début de la prise de photo) et la réponse
 * de l'IA. Au-delà, l'analyse s'arrête net (AgentException TIMEOUT) : jamais d'analyse sans fin, jamais de boucle continue.
 * 10 s est un MAXIMUM : dès que la réponse est prête, on la dit.
 */
class AssistantEngine(private val context: Context, private val client: AgentClient, private val tools: ToolExecutor) {
    val conversation = Conversation()

    /** Début de l'analyse en cours (0 = pas de photo dans ce tour). */
    private var analysisStart = 0L

    /** Question libre. Si l'IA demande la caméra, le chronomètre de 10 s démarre à ce moment-là. */
    suspend fun ask(text: String, onPreface: (String) -> Unit = {}): String {
        analysisStart = 0L
        conversation.addUserText(text)
        return run(onPreface)
    }

    /**
     * Demande de regarder (secousse, « Regarde encore », « Lis ça », « Quel est ce billet »…) : l'application prend la photo
     * elle-même, tout de suite, et l'envoie avec la demande en un seul aller-retour (plus rapide qu'un passage par l'outil).
     */
    suspend fun askLook(prompt: String, onPreface: (String) -> Unit = {}): String {
        if (!tools.ensureCamera()) return context.getString(R.string.tool_denied)
        analysisStart = System.currentTimeMillis()
        val photo = withTimeoutOrNull(ANALYSIS_MAX_MS) { tools.takePhoto() }
            ?: throw AgentException(AgentException.Kind.TIMEOUT, "capture")
        conversation.addUserBlocks(JSONArray().put(Conversation.image(photo)).put(Conversation.text(prompt)))
        return run(onPreface)
    }

    private fun remainingMs(): Long =
        if (analysisStart == 0L) 0L else ANALYSIS_MAX_MS - (System.currentTimeMillis() - analysisStart)

    private suspend fun run(onPreface: (String) -> Unit): String {
        val settings = context.settings
        try {
            var rounds = 0
            while (true) {
                conversation.compact()
                val localTime = OffsetDateTime.now().withNano(0).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                // Pendant une analyse caméra : l'appel à l'IA reçoit le temps restant comme limite stricte.
                val deadline = if (analysisStart == 0L) 0L else remainingMs().also {
                    if (it < MIN_CALL_MS) throw AgentException(AgentException.Kind.TIMEOUT, "budget")
                }
                val reply = withContext(Dispatchers.IO) {
                    client.agent(conversation.messages, settings.language, settings.profile, localTime, deadline)
                }
                when (reply) {
                    is AgentReply.Final -> {
                        conversation.addAssistantText(reply.text.ifBlank { "…" })
                        return reply.text
                    }
                    is AgentReply.ToolCalls -> {
                        if (reply.preface.isNotBlank()) onPreface(reply.preface)
                        conversation.addAssistantBlocks(reply.assistant)
                        val results = JSONArray()
                        for (call in reply.calls) {
                            val out = if (call.name == "capture_camera") captureWithinBudget(call) else tools.run(call.name, call.input)
                            results.put(Conversation.toolResult(call.id, out.content, out.isError))
                        }
                        conversation.addUserBlocks(results)
                        if (++rounds > 5) throw AgentException(AgentException.Kind.SERVER, "too_many_rounds")
                    }
                }
            }
        } catch (e: Exception) {
            repairAfterError()
            throw e
        }
    }

    /** Photo demandée par l'IA en cours de conversation : le chronomètre de 10 s démarre ici (une seule fois par demande). */
    private suspend fun captureWithinBudget(call: AgentReply.Call): ToolOutput {
        if (!tools.ensureCamera()) return ToolOutput.text(context.getString(R.string.tool_denied), true)
        if (analysisStart == 0L) analysisStart = System.currentTimeMillis()
        val left = remainingMs()
        if (left < MIN_CALL_MS) throw AgentException(AgentException.Kind.TIMEOUT, "budget")
        return withTimeoutOrNull(left) { tools.run(call.name, call.input) }
            ?: throw AgentException(AgentException.Kind.TIMEOUT, "capture")
    }

    /** Après une erreur, on retire le tour inachevé pour ne pas envoyer une conversation incohérente. */
    private fun repairAfterError() {
        val m = conversation.messages
        // Retire tout jusqu'au dernier vrai message utilisateur (sans résultat d'outil) inclus.
        while (m.length() > 0) {
            val last = m.getJSONObject(m.length() - 1)
            m.remove(m.length() - 1)
            if (last.getString("role") == "user") {
                val c = last.getJSONArray("content")
                var plain = true
                for (i in 0 until c.length()) if (c.getJSONObject(i).getString("type") == "tool_result") plain = false
                if (plain) break
            }
        }
    }

    fun reset() = conversation.clear()

    companion object {
        /** Durée MAXIMALE d'une analyse caméra, de la prise de photo à la réponse de l'IA. */
        const val ANALYSIS_MAX_MS = 10_000L
        /** Sous ce temps restant, inutile de lancer un appel à l'IA : l'analyse s'arrête. */
        private const val MIN_CALL_MS = 1_500L
    }
}
