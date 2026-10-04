import { env } from "@/lib/env";
import { handle, jsonResponse } from "@/lib/api";
import { providerLabel } from "@/services/ai";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

/** Informations publiques de configuration (aucun secret). Sert à l'écran de consentement. */
export const GET = handle(async () =>
  jsonResponse({
    aiProvider: providerLabel(),
    codeRequired: Boolean(env.appAccessCode),
    languages: ["fr", "en", "so", "ar"],
    speech: "OpenAI (transcription) et ElevenLabs (voix)",
  })
);
