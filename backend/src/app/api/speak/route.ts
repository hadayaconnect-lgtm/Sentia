import { ApiError, cleanText, guard, handle, readJson } from "@/lib/api";
import { textForSpeech } from "@/lib/messages";
import { synthesize } from "@/services/tts.service";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";
export const maxDuration = 60;

export const POST = handle(async (request) => {
  await guard(request, { quota: true });
  const body = await readJson(request);
  const text = textForSpeech(cleanText(body.text, 2500));
  if (!text) throw new ApiError(400, "text");
  const audio = await synthesize(text);
  return new Response(new Uint8Array(audio), {
    headers: { "Content-Type": "audio/mpeg", "Cache-Control": "no-store" },
  });
});
