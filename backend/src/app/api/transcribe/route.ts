import { ApiError, guard, handle, jsonResponse } from "@/lib/api";
import { transcribeDetailed } from "@/services/transcription.service";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";
export const maxDuration = 60;

const MAX_AUDIO_BYTES = 4_000_000;

/** Transcription FIDÈLE d'un audio (conversation ou vocal reçu) : aucune correction, aucun résumé. */
export const POST = handle(async (request) => {
  await guard(request, { quota: true });
  const form = await request.formData();
  const audio = form.get("audio");
  if (!(audio instanceof File) || audio.size === 0 || audio.size > MAX_AUDIO_BYTES) throw new ApiError(400, "audio");
  try {
    const { text, language } = await transcribeDetailed(Buffer.from(await audio.arrayBuffer()), audio.type, audio.name);
    return jsonResponse({ text, language });
  } catch (error) {
    if ((error as Error).message === "unsupported_audio") throw new ApiError(415, "audio_format");
    throw error;
  }
});
