package dj.sentia

import org.json.JSONArray
import org.json.JSONObject

/**
 * Conversation au format neutre attendu par le serveur (voir backend/src/types).
 * Le serveur ne garde rien : c'est l'application qui conserve la conversation et la renvoie à chaque tour.
 */
class Conversation {
    val messages = JSONArray()

    fun addUserText(text: String) {
        messages.put(msg("user", JSONArray().put(text(text))))
    }

    fun addAssistantText(text: String) {
        messages.put(msg("assistant", JSONArray().put(text(text))))
    }

    fun addAssistantBlocks(blocks: JSONArray) {
        messages.put(msg("assistant", blocks))
    }

    fun addUserBlocks(blocks: JSONArray) {
        messages.put(msg("user", blocks))
    }

    fun clear() {
        while (messages.length() > 0) messages.remove(0)
    }

    /** « Ferme la caméra » : on oublie les photos de la conversation (le texte des réponses reste). */
    fun dropImages() {
        for (i in 0 until messages.length()) stripAllImages(messages.getJSONObject(i).getJSONArray("content"))
    }

    private fun stripAllImages(blocks: JSONArray) {
        for (i in 0 until blocks.length()) {
            val b = blocks.getJSONObject(i)
            when (b.getString("type")) {
                "image" -> blocks.put(i, text("(image retirée)"))
                "tool_result" -> stripAllImages(b.getJSONArray("content"))
            }
        }
    }

    /** Garde la conversation dans les limites du serveur (16 messages, 3 images) sans casser les paires outil/résultat. */
    fun compact() {
        val list = ArrayList<JSONObject>()
        for (i in 0 until messages.length()) list.add(messages.getJSONObject(i))
        while (list.size > MAX_KEPT || (list.isNotEmpty() && !isPlainUser(list[0]))) {
            if (list.isEmpty()) break
            list.removeAt(0)
        }
        // Images : on ne garde que les 2 plus récentes.
        var images = 0
        for (idx in list.indices.reversed()) images = stripOldImages(list[idx].getJSONArray("content"), images)
        while (messages.length() > 0) messages.remove(0)
        list.forEach { messages.put(it) }
    }

    private fun stripOldImages(blocks: JSONArray, seenNewer: Int): Int {
        var seen = seenNewer
        for (i in blocks.length() - 1 downTo 0) {
            val b = blocks.getJSONObject(i)
            when (b.getString("type")) {
                "image" -> {
                    seen++
                    if (seen > MAX_IMAGES) blocks.put(i, text("(ancienne image retirée)"))
                }
                "tool_result" -> seen = stripOldImages(b.getJSONArray("content"), seen)
            }
        }
        return seen
    }

    private fun isPlainUser(m: JSONObject): Boolean {
        if (m.getString("role") != "user") return false
        val c = m.getJSONArray("content")
        for (i in 0 until c.length()) if (c.getJSONObject(i).getString("type") == "tool_result") return false
        return true
    }

    companion object {
        const val MAX_KEPT = 12
        const val MAX_IMAGES = 2

        fun text(t: String): JSONObject = JSONObject().put("type", "text").put("text", t)
        fun image(base64: String): JSONObject = JSONObject().put("type", "image").put("data", base64)
        fun msg(role: String, content: JSONArray): JSONObject = JSONObject().put("role", role).put("content", content)

        fun toolResult(id: String, content: JSONArray, isError: Boolean = false): JSONObject {
            val o = JSONObject().put("type", "tool_result").put("tool_use_id", id).put("content", content)
            if (isError) o.put("is_error", true)
            return o
        }
    }
}

sealed class AgentReply {
    data class Final(val text: String) : AgentReply()
    data class ToolCalls(val preface: String, val assistant: JSONArray, val calls: List<Call>) : AgentReply()
    data class Call(val id: String, val name: String, val input: JSONObject, val sensitive: Boolean)
}

class AgentException(val kind: Kind, message: String? = null) : Exception(message) {
    enum class Kind { NETWORK, CONFIG, SERVER, DENIED, TIMEOUT }
}
