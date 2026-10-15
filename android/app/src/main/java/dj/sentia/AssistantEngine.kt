package dj.sentia

import android.content.Context
import dj.sentia.core.AnalysisPolicy
import dj.sentia.core.PhotoBudget
import dj.sentia.core.RetryPolicy
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * Un tour complet : demande de l'utilisateur → (outils éventuels, au plus 5 tours) → réponse finale.
 *
 * Analyse caméra : RÉVEIL → UNE photo (vérifiée, caméra libérée aussitôt) → envoi à l'IA → réponse → voix.
 *  - UNE seule photo par demande ([PhotoBudget]) : celle que l'application joint, ou celle que l'IA demande par l'outil, jamais les deux ;
 *  - prise de photo (caméra + capture + vérification, 1 nouvelle capture au plus si invalide) : [AnalysisPolicy.CAPTURE_MAX_MS] ;
 *  - envoi + serveur + IA, compté à partir de l'envoi : [AnalysisPolicy.AI_MAX_MS] (au-delà : arrêt net, jamais de boucle sans fin).
 * Ce sont des MAXIMUMS par étape, pas des attentes : dès que la réponse est prête, on la dit. Un échec RÉSEAU (pas un délai) est retenté une fois.
 */
class AssistantEngine(private val context: Context, private val client: AgentClient, private val tools: ToolExecutor) {
    val conversation = Conversation()

    /** Début du délai « envoi + IA » de l'analyse en cours : l'instant où la photo part (0 = pas de photo dans ce tour). */
    private var analysisStart = 0L

    /** Photos prises pour la demande en cours (au plus une). */
    private var photos = PhotoBudget()

    /** Identifiant de la demande en cours : toute réponse portant un autre identifiant est ignorée. */
    @Volatile private var currentId = 0

    /** Question libre (suivi) : réutilise la photo déjà analysée. Si l'IA demande la caméra, c'est UNE photo au plus. */
    suspend fun ask(text: String, onPreface: (String) -> Unit = {}): String {
        Perf.beginIfStale()
        analysisStart = 0L
        photos = PhotoBudget()
        currentId++
        conversation.addUserText(text)
        return run(onPreface)
    }

    /**
     * Demande de regarder (secousse, « Regarde encore », « Lis ça », « Quel est ce billet »…) : l'application prend la photo
     * elle-même, tout de suite, et l'envoie avec la demande en un seul aller-retour (plus rapide qu'un passage par l'outil).
     */
    suspend fun askLook(prompt: String, onPreface: (String) -> Unit = {}): String {
        Perf.beginIfStale()
        if (!tools.ensureCamera()) return context.getString(R.string.tool_denied)
        currentId++
        // Nouvelle analyse : on oublie les anciennes photos, seule la photo de CETTE analyse est envoyée à l'IA.
        conversation.dropImages()
        photos = PhotoBudget().also { it.tryTake() } // la photo de cette demande : l'IA ne pourra pas en redemander une
        analysisStart = 0L
        Perf.mark("cam_start")
        // UNE photo, vérifiée ; caméra libérée dès la prise. Échec ou délai : on le dit, on n'envoie RIEN à l'IA.
        val photo = withTimeoutOrNull(AnalysisPolicy.CAPTURE_MAX_MS) { tools.takePhoto() }
            ?: throw AgentException(AgentException.Kind.IMAGE, "capture")
        Perf.mark("cam_done")
        analysisStart = System.currentTimeMillis() // le délai « envoi + IA » démarre à l'envoi de la photo
        conversation.addUserBlocks(JSONArray().put(Conversation.image(photo)).put(Conversation.text(prompt)))
        return run(onPreface)
    }

    private fun remainingMs(): Long =
        if (analysisStart == 0L) 0L else AnalysisPolicy.AI_MAX_MS - (System.currentTimeMillis() - analysisStart)

    private suspend fun run(onPreface: (String) -> Unit): String {
        val settings = context.settings
        val id = currentId
        try {
            var rounds = 0
            var retried = false
            while (true) {
                conversation.compact()
                val localTime = OffsetDateTime.now().withNano(0).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                // Pendant une analyse caméra : l'appel à l'IA reçoit le temps restant comme limite stricte.
                val deadline = if (analysisStart == 0L) 0L else remainingMs().also {
                    if (it < AnalysisPolicy.MIN_CALL_MS) throw AgentException(AgentException.Kind.TIMEOUT, "budget")
                }
                val reply = try {
                    client.agent(conversation.messages, settings.language, settings.profile, localTime, deadline)
                } catch (e: AgentException) {
                    // Coupure réseau brève (signal faible, changement d'antenne) : une seule nouvelle tentative, s'il reste du temps.
                    if (RetryPolicy.shouldRetryNetwork(e.kind == AgentException.Kind.NETWORK, retried, if (analysisStart == 0L) null else remainingMs())) {
                        retried = true
                        delay(400)
                        continue
                    }
                    throw e
                }
                // Réponse d'une analyse devenue obsolète (une nouvelle demande a démarré entre-temps) : on l'ignore.
                if (id != currentId) throw kotlinx.coroutines.CancellationException("analyse obsolète")
                when (reply) {
                    is AgentReply.Final -> {
                        Perf.mark("ai_done")
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

    /**
     * Photo demandée par l'IA (question libre sans photo jointe). Une seule par demande : si une photo a déjà été prise
     * (jointe par l'application, ou déjà demandée), l'outil refuse au lieu d'en prendre une deuxième.
     */
    private suspend fun captureWithinBudget(call: AgentReply.Call): ToolOutput {
        if (!photos.tryTake()) return ToolOutput.text("Une photo a déjà été prise pour cette demande. Utilise-la. Si elle est inexploitable, dis-le à la personne et demande-lui de bouger le téléphone puis de redemander.", true)
        if (!tools.ensureCamera()) return ToolOutput.text(context.getString(R.string.tool_denied), true)
        val out = withTimeoutOrNull(AnalysisPolicy.CAPTURE_MAX_MS) { tools.run(call.name, call.input) }
            ?: throw AgentException(AgentException.Kind.IMAGE, "capture")
        analysisStart = System.currentTimeMillis() // le délai « envoi + IA » démarre une fois la photo prise
        return out
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
}
