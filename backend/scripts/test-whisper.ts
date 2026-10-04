/**
 * TESTS 2 et 3 — qualité de Whisper sur le français et l'arabe de Djibouti.
 *
 * Préparez 5 à 10 vocaux réels (reçus par messagerie, enregistrés par des personnes différentes) avec
 * le texte attendu à côté, puis :
 *
 *   npm run test:whisper -- test-audio/vocal1.ogg test-audio/vocal2.ogg
 *   npm run test:whisper -- --lang=fr test-audio/vocal1.ogg
 *   npm run test:whisper -- --lang=ar test-audio/vocal-arabe.ogg
 *
 * Sans --lang, Whisper détecte la langue tout seul. Comparez les deux modes : si la détection
 * automatique se trompe, fixez WHISPER_LANGUAGE dans l'environnement.
 * Les fichiers OGG/Opus de WhatsApp sont envoyés TELS QUELS (aucune conversion) : si Whisper
 * les refuse, ajouter une conversion ffmpeg dans transcription.service.ts.
 */

import { readFileSync } from "node:fs";
import path from "node:path";
import { transcribe } from "../src/services/transcription.service";

const MIME_BY_EXT: Record<string, string> = {
  ".ogg": "audio/ogg",
  ".opus": "audio/ogg",
  ".mp3": "audio/mpeg",
  ".m4a": "audio/mp4",
  ".wav": "audio/wav",
  ".webm": "audio/webm",
  
};

async function main() {
  const args = process.argv.slice(2);
  const langArg = args.find((a) => a.startsWith("--lang="));
  if (langArg) process.env.WHISPER_LANGUAGE = langArg.split("=")[1];
  const files = args.filter((a) => !a.startsWith("--"));

  if (files.length === 0) {
    console.error("Usage : npm run test:whisper -- [--lang=fr|ar] fichier1.ogg [fichier2.ogg ...]");
    process.exit(1);
  }

  console.log(`Langue forcée : ${process.env.WHISPER_LANGUAGE ?? "aucune (détection automatique)"}\n`);

  for (const file of files) {
    const ext = path.extname(file).toLowerCase();
    const contentType = MIME_BY_EXT[ext] ?? "audio/ogg";
    const started = Date.now();
    try {
      const text = await transcribe(readFileSync(file), contentType);
      console.log(`--- ${file} (${Date.now() - started} ms)`);
      console.log(text || "(transcription vide)");
    } catch (error) {
      console.log(`--- ${file} : ÉCHEC — ${(error as Error).message}`);
    }
    console.log();
  }
}

main();
