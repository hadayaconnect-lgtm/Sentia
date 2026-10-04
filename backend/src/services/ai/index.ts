import { env } from "@/lib/env";
import { anthropicProvider } from "./anthropic";
import { openaiCompatProvider } from "./openai-compat";
import type { AIProvider } from "./types";

export type { AIProvider, GenerateRequest, GenerateResult } from "./types";

/** Le reste de l'application ne connaît que cette fonction : changer d'IA = changer AI_PROVIDER. */
export function getProvider(): AIProvider {
  return env.aiProvider === "anthropic" ? anthropicProvider : openaiCompatProvider();
}

export function providerLabel(): string {
  return env.aiProvider === "anthropic" ? anthropicProvider.label : env.compat.label;
}
