import { cleanText, guard, handle, jsonResponse, parseLang } from "@/lib/api";
import { createLiveSession } from "@/services/live.service";
import { PROFILES, type Profile } from "@/types";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";
export const maxDuration = 30;

/**
 * Jeton temporaire pour la conversation en direct (Gemini Live).
 * Entrée : { language ("fr"|"en"|"so"|"ar"|"auto"), profile, localTime? }
 * Sortie : { token, urls, model, instruction, tools, startWithinSec } — la clé secrète ne quitte jamais le serveur.
 */
export const POST = handle(async (request) => {
  await guard(request, { quota: true });
  const body = (await request.json().catch(() => ({}))) as Record<string, unknown>;
  const lang = body.language === "auto" || body.language === undefined ? null : parseLang(body.language);
  const profile: Profile = (PROFILES as unknown[]).includes(body.profile) ? (body.profile as Profile) : "other";
  const localTime = cleanText(body.localTime, 40) || undefined;
  return jsonResponse(await createLiveSession({ lang, profile, localTime }));
});
