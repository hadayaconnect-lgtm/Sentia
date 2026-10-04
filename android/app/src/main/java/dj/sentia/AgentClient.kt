package dj.sentia

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Client du serveur SENTIA. Aucune clé d'IA ici : seulement l'adresse du serveur et un code d'accès éventuel. */
class AgentClient(context: Context) {
    private val settings = context.settings
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(70, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun base(): String {
        val url = settings.serverUrl
        if (!url.startsWith("https://") || url.contains("VOTRE-SERVEUR")) throw AgentException(AgentException.Kind.CONFIG)
        return url
    }

    private fun builder(path: String): Request.Builder {
        val b = Request.Builder().url(base() + path)
            .header("x-client", "sentia-android")
            .header("x-device-id", settings.deviceId)
        if (settings.accessCode.isNotEmpty()) b.header("x-access-code", settings.accessCode)
        return b
    }

    private fun <T> execute(request: Request, parse: (okhttp3.Response) -> T): T {
        try {
            http.newCall(request).execute().use { r ->
                if (r.code == 401 || r.code == 403) throw AgentException(AgentException.Kind.DENIED)
                if (!r.isSuccessful) throw AgentException(AgentException.Kind.SERVER, "HTTP ${r.code}")
                return parse(r)
            }
        } catch (e: IOException) {
            throw AgentException(AgentException.Kind.NETWORK, e.message)
        }
    }

    /** Un tour de conversation. Appel bloquant : à lancer sur Dispatchers.IO. */
    fun agent(messages: JSONArray, language: String, profile: String, localTime: String): AgentReply {
        val body = JSONObject()
            .put("messages", messages)
            .put("language", language)
            .put("profile", profile.ifEmpty { "other" })
            .put("localTime", localTime)
            .toString().toRequestBody("application/json".toMediaType())
        return execute(builder("/api/agent").post(body).build()) { r ->
            val json = JSONObject(r.body!!.string())
            if (json.optString("type") == "final") {
                AgentReply.Final(json.optString("text"))
            } else {
                val calls = json.getJSONArray("calls")
                val list = (0 until calls.length()).map {
                    val c = calls.getJSONObject(it)
                    AgentReply.Call(c.getString("id"), c.getString("name"), c.optJSONObject("input") ?: JSONObject(), c.optBoolean("sensitive"))
                }
                AgentReply.ToolCalls(json.optString("preface"), json.getJSONArray("assistant"), list)
            }
        }
    }

    /** Transcription : renvoie (texte, langue détectée ou ""). */
    fun transcribe(audio: File): Pair<String, String> {
        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("audio", audio.name, audio.asRequestBody("audio/mp4".toMediaType()))
            .build()
        return execute(builder("/api/transcribe").post(form).build()) { r ->
            val j = JSONObject(r.body!!.string())
            Pair(j.optString("text"), if (j.isNull("language")) "" else j.optString("language"))
        }
    }

    /** Voix du serveur (repli quand la voix du téléphone n'existe pas dans la langue). Renvoie un fichier MP3. */
    fun speak(text: String, out: File): File {
        val body = JSONObject().put("text", text).toString().toRequestBody("application/json".toMediaType())
        return execute(builder("/api/speak").post(body).build()) { r ->
            out.outputStream().use { o -> r.body!!.byteStream().copyTo(o) }
            out
        }
    }
}
