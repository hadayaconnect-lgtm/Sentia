import { ApiError, cleanText, finalizeText, guard, handle, jsonResponse, loadServices, parseLang, readJson } from "@/lib/api";
import { parseMessages } from "@/lib/agent-input";
import { runAgentTurn } from "@/services/agent.service";
import { PROFILES, type Profile } from "@/types";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";
export const maxDuration = 60;

/** Durée du tour côté serveur (IA comprise), pour que l'application sépare « réseau » et « IA » dans ses mesures. */
const timing = (startedAt: number) => ({ "x-agent-ms": String(Date.now() - startedAt) });

/**
 * Un tour de conversation avec SENTIA.
 * Entrée : { messages, language ("fr"|"en"|"so"|"ar"|"auto"), profile, localTime? }
 * Sortie : { type: "final", text }  ou  { type: "tool_calls", preface, assistant, calls }
 */
export const POST = handle(async (request) => {
  await guard(request, { quota: true });
  const body = await readJson(request);

  const { messages, toolRounds } = parseMessages(body.messages);
  const lang = body.language === "auto" ? null : parseLang(body.language);
  const profile: Profile = (PROFILES as unknown[]).includes(body.profile) ? (body.profile as Profile) : "other";
  const localTime = cleanText(body.localTime, 40) || undefined;

  const services = await loadServices();
  const startedAt = Date.now();
  const result = await runAgentTurn({ messages, toolRounds, lang, profile, services, localTime });

  if (result.type === "final") {
    // Langue « auto » : les libellés des fiches suivent la langue de la requête, repli sur le français.
    const labelLang = lang ?? "fr";
    return jsonResponse({ type: "final", text: finalizeText(result.text, services, labelLang) }, 200, timing(startedAt));
  }
  if (result.calls.length > 4) throw new ApiError(502, "too_many_calls");
  return jsonResponse({ type: "tool_calls", preface: finalizeText(result.preface, services, lang ?? "fr"), assistant: result.assistant, calls: result.calls }, 200, timing(startedAt));
});
