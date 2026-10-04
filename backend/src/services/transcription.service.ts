import OpenAI, { toFile } from "openai";
import { env } from "@/lib/env";

let client: OpenAI | null = null;

function openai(): OpenAI {
  if (!client) {
    client = new OpenAI({
      apiKey: env.openaiApiKey,
      baseURL: process.env.OPENAI_BASE_URL || undefined,
      timeout: 60_000,
      maxRetries: 2,
    });
  }
  return client;
}

/** Extension attendue par Whisper selon le type MIME ; null = format non pris en charge. */
export function extensionFor(contentType: string): string | null {
  const t = contentType.toLowerCase();
  if (t.includes("webm")) return "webm";
  if (t.includes("ogg") || t.includes("opus")) return "ogg";
  if (t.includes("mpeg") || t.includes("mp3")) return "mp3";
  if (t.includes("mp4") || t.includes("m4a") || t.includes("aac")) return "m4a";
  if (t.includes("wav")) return "wav";
  if (t.includes("flac")) return "flac";
  return null;
}

const LANGUAGE_CODES: Record<string, string> = {
  french: "fr", fr: "fr", english: "en", en: "en", somali: "so", so: "so", arabic: "ar", ar: "ar",
};

export interface Transcription {
  text: string;
  /** Langue détectée parmi fr/en/so/ar, ou null (autre langue ou détection indisponible). */
  language: string | null;
}

/**
 * Transcription FIDÈLE (Whisper) : le texte n'est ni corrigé ni résumé.
 * WHISPER_LANGUAGE (ex. "fr", "ar") force la langue ; sinon détection automatique.
 */
export async function transcribeDetailed(audio: Buffer, contentType: string, filename = ""): Promise<Transcription> {
  // Certains téléphones n'indiquent pas de type pour un vocal : on se rabat sur l'extension du nom.
  const ext = extensionFor(contentType) ?? extensionFor(filename.split(".").pop() ?? "");
  if (!ext) throw new Error("unsupported_audio");
  const file = await toFile(audio, `voice.${ext}`, { type: contentType.split(";")[0] || `audio/${ext}` });
  const language = env.whisperLanguage;
  const result = (await openai().audio.transcriptions.create({
    file,
    model: env.whisperModel,
    ...(language ? { language } : {}),
    response_format: "verbose_json",
  })) as unknown as { text: string; language?: string };
  const detected = language || (result.language ? result.language.toLowerCase() : "");
  return { text: (result.text ?? "").trim(), language: LANGUAGE_CODES[detected] ?? null };
}

export async function transcribe(audio: Buffer, contentType: string, filename = ""): Promise<string> {
  return (await transcribeDetailed(audio, contentType, filename)).text;
}
