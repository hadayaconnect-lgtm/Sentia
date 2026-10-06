import { env } from "@/lib/env";
import { anthropicProvider } from "./anthropic";
import { isTransient, openaiCompatProvider } from "./openai-compat";
import type { AIProvider } from "./types";

export type { AIProvider, GenerateRequest, GenerateResult } from "./types";

/** Le reste de l'application ne connaît que cette fonction : changer d'IA = changer AI_PROVIDER. */
export function getProvider(): AIProvider {
  if (env.aiProvider === "anthropic") return anthropicProvider;
  const primary = openaiCompatProvider();
  // Dernier secours : si Gemini reste indisponible (surcharge, panne) et qu'une clé Claude est configurée, Claude répond à sa place.
  if (env.aiProvider !== "gemini" || !process.env.ANTHROPIC_API_KEY) return primary;
  return {
    label: primary.label,
    async generate(request) {
      try {
        return await primary.generate(request);
      } catch (e) {
        if (isTransient(e)) return anthropicProvider.generate(request);
        throw e;
      }
    },
  };
}

export function providerLabel(): string {
  return env.aiProvider === "anthropic" ? anthropicProvider.label : env.compat.label;
}
