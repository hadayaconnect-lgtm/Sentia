package dj.sentia

import android.content.Context
import dj.sentia.core.HapticPattern
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/** Un tour complet : texte de l'utilisateur → (outils éventuels, au plus 4 tours) → réponse finale. */
class AssistantEngine(private val context: Context, private val client: AgentClient, private val tools: ToolExecutor) {
    val conversation = Conversation()

    /** @param onPreface appelé avec le petit texte dit avant un outil (« Je regarde. »). */
    suspend fun ask(text: String, onPreface: (String) -> Unit = {}): String {
        val settings = context.settings
        conversation.addUserText(text)
        try {
            var rounds = 0
            while (true) {
                conversation.compact()
                val localTime = OffsetDateTime.now().withNano(0).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                val reply = withContext(Dispatchers.IO) {
                    client.agent(conversation.messages, settings.language, settings.profile, localTime)
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
                            val out = tools.run(call.name, call.input)
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

    /** Après une erreur, on retire le tour inachevé pour ne pas envoyer une conversation incohérente. */
    private fun repairAfterError() {
        val m = conversation.messages
        // Retire tout jusqu'au dernier vrai message utilisateur (texte simple) inclus.
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
}
