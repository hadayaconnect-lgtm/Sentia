package dj.sentia

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Client du serveur SENTIA. Aucune clé d'IA ici : seulement l'adresse du serveur et un code d'accès éventuel. */
class AgentClient(context: Context) {
    private val settings = context.settings
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(70, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        // Mesure des étapes réseau de l'appel à l'IA (envoi / attente serveur + IA / réception), gardée sur le téléphone.
        .eventListenerFactory(object : okhttp3.EventListener.Factory {
            override fun create(call: okhttp3.Call): okhttp3.EventListener =
                if (call.request().url.encodedPath.endsWith("/api/agent")) NetPerf() else okhttp3.EventListener.NONE
        })
        .build()

    private class NetPerf : okhttp3.EventListener() {
        private var reqStart = 0L
        private var reqEnd = 0L
        private var respStart = 0L
        override fun requestBodyStart(call: okhttp3.Call) { reqStart = System.nanoTime() }
        override fun requestBodyEnd(call: okhttp3.Call, byteCount: Long) { reqEnd = System.nanoTime() }
        override fun responseHeadersStart(call: okhttp3.Call) { respStart = System.nanoTime() }
        override fun responseBodyEnd(call: okhttp3.Call, byteCount: Long) {
            if (reqStart == 0L || reqEnd == 0L || respStart == 0L) return
            val now = System.nanoTime()
            Perf.put("upload", (reqEnd - reqStart) / 1_000_000)
            Perf.put("wait", (respStart - reqEnd) / 1_000_000)
            Perf.put("download", (now - respStart) / 1_000_000)
        }
    }

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

    /**
     * Appel HTTP ANNULABLE : si la coroutine est annulée (nouvelle secousse, Stop, nouvelle analyse), la requête est coupée
     * tout de suite. Une ancienne réponse ne peut donc jamais revenir après une nouvelle demande.
     * @param deadlineMs durée totale maximale de la requête (0 = pas de limite particulière).
     */
    private suspend fun <T> execute(request: Request, deadlineMs: Long = 0, parse: (okhttp3.Response) -> T): T =
        suspendCancellableCoroutine { cont ->
            val call = http.newCall(request)
            if (deadlineMs > 0) call.timeout().timeout(deadlineMs, TimeUnit.MILLISECONDS)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: IOException) {
                    if (!cont.isActive) return
                    // Délai d'analyse dépassé (call.timeout) — ou délai réseau ordinaire si aucune limite n'était demandée.
                    val kind = if (e is java.io.InterruptedIOException && deadlineMs > 0) AgentException.Kind.TIMEOUT else AgentException.Kind.NETWORK
                    cont.resumeWithException(AgentException(kind, e.message))
                }

                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    response.use { r ->
                        val result: Result<T> = try {
                            if (r.code == 401 || r.code == 403) throw AgentException(AgentException.Kind.DENIED, "HTTP ${r.code}")
                            if (!r.isSuccessful) {
                                val detail = try { r.body?.string()?.take(120) ?: "" } catch (_: Exception) { "" }
                                throw AgentException(AgentException.Kind.SERVER, "HTTP ${r.code} $detail".trim())
                            }
                            Result.success(parse(r))
                        } catch (e: AgentException) {
                            Result.failure(e)
                        } catch (e: IOException) {
                            Result.failure(AgentException(AgentException.Kind.NETWORK, e.message))
                        } catch (e: Exception) {
                            Result.failure(AgentException(AgentException.Kind.SERVER, "réponse illisible : " + e.javaClass.simpleName))
                        }
                        if (cont.isActive) result.fold({ cont.resume(it) }, { cont.resumeWithException(it) })
                    }
                }
            })
        }

    /** Un tour de conversation (annulable). */
    suspend fun agent(messages: JSONArray, language: String, profile: String, localTime: String, deadlineMs: Long = 0): AgentReply {
        val body = JSONObject()
            .put("messages", messages)
            .put("language", language)
            .put("profile", profile.ifEmpty { "other" })
            .put("localTime", localTime)
            .toString().toRequestBody("application/json".toMediaType())
        return execute(builder("/api/agent").post(body).build(), deadlineMs) { r ->
            r.header("x-agent-ms")?.toLongOrNull()?.let { Perf.put("server", it) }
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
    suspend fun transcribe(audio: File): Pair<String, String> {
        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("audio", audio.name, audio.asRequestBody((if (audio.name.endsWith(".wav")) "audio/wav" else "audio/mp4").toMediaType()))
            .build()
        return execute(builder("/api/transcribe").post(form).build()) { r ->
            val j = JSONObject(r.body!!.string())
            Pair(j.optString("text"), if (j.isNull("language")) "" else j.optString("language"))
        }
    }

    /** Voix du serveur (repli quand la voix du téléphone n'existe pas dans la langue). Renvoie un fichier MP3. */
    suspend fun speak(text: String, out: File): File {
        val body = JSONObject().put("text", text).toString().toRequestBody("application/json".toMediaType())
        return execute(builder("/api/speak").post(body).build()) { r ->
            out.outputStream().use { o -> r.body!!.byteStream().copyTo(o) }
            out
        }
    }
}
