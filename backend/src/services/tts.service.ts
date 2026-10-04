import { env } from "@/lib/env";

/** Synthèse vocale ElevenLabs (MP3). Isolée ici pour pouvoir être remplacée. */
export async function synthesize(text: string): Promise<Buffer> {
  const url = `${env.elevenlabsBaseUrl}/v1/text-to-speech/${encodeURIComponent(
    env.elevenlabsVoiceId
  )}?output_format=mp3_44100_64`;

  const response = await fetch(url, {
    method: "POST",
    headers: { "xi-api-key": env.elevenlabsApiKey, "Content-Type": "application/json", Accept: "audio/mpeg" },
    body: JSON.stringify({ text, model_id: env.elevenlabsModelId }),
    signal: AbortSignal.timeout(45_000),
  });
  // Le corps de la réponse n'est jamais recopié dans l'erreur (il peut contenir du texte de l'utilisateur).
  if (!response.ok) throw new Error(`ElevenLabs a répondu HTTP ${response.status}`);
  return Buffer.from(await response.arrayBuffer());
}
