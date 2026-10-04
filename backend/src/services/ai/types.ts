import type { Block, Message } from "@/types";
import type { ToolDef } from "@/lib/tools";

export interface GenerateRequest {
  system: string;
  messages: Message[];
  /** Outils proposés à l'IA (absents = réponse finale uniquement). */
  tools?: ToolDef[];
  maxTokens?: number;
}

export interface GenerateResult {
  /** Blocs de texte et appels d'outils, dans l'ordre. */
  content: Block[];
}

/** Interface commune : Claude, OpenAI, Gemini, Llama… se branchent derrière sans changer le reste. */
export interface AIProvider {
  readonly label: string;
  generate(request: GenerateRequest): Promise<GenerateResult>;
}
