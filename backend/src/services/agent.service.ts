import { buildAgentPrompt } from "@/lib/prompts";
import { MAX_TOOL_ROUNDS } from "@/lib/agent-input";
import { TOOLS, TOOL_BY_NAME } from "@/lib/tools";
import { getProvider } from "./ai";
import type { Block, Language, Message, Profile, Service, ToolUseBlock } from "@/types";

export interface AgentParams {
  messages: Message[];
  toolRounds: number;
  lang: Language | null;
  profile: Profile;
  services: Service[];
  localTime?: string;
}

export type AgentResult =
  | { type: "final"; text: string }
  | {
      type: "tool_calls";
      /** Texte éventuel dit avant l'appel (« Je regarde. »), à afficher/lire pendant l'attente. */
      preface: string;
      /** Message de l'assistant à RENVOYER tel quel dans la liste des messages au tour suivant. */
      assistant: Block[];
      calls: { id: string; name: string; input: Record<string, unknown>; sensitive: boolean }[];
    };

/**
 * Un tour de l'agent. Le serveur est sans état : l'application garde la conversation, exécute les
 * outils demandés (avec ses permissions) et rappelle avec les résultats. Au plus MAX_TOOL_ROUNDS tours d'outils.
 */
export async function runAgentTurn(params: AgentParams): Promise<AgentResult> {
  const toolsAvailable = params.toolRounds < MAX_TOOL_ROUNDS;
  const result = await getProvider().generate({
    system: buildAgentPrompt({
      lang: params.lang,
      profile: params.profile,
      services: params.services,
      localTime: params.localTime,
      toolsAvailable,
    }),
    messages: params.messages,
    tools: toolsAvailable ? TOOLS : undefined,
    maxTokens: 700,
  });

  const text = result.content
    .filter((b) => b.type === "text")
    .map((b) => (b as { text: string }).text)
    .join("\n")
    .trim();

  // Seuls les outils du catalogue sont acceptés, avec leurs entrées nettoyées ; un appel inconnu est ignoré.
  const uses: ToolUseBlock[] = [];
  if (toolsAvailable) {
    for (const b of result.content) {
      if (b.type !== "tool_use") continue;
      const tool = TOOL_BY_NAME.get(b.name);
      const input = tool?.sanitize(b.input);
      if (tool && input) uses.push({ type: "tool_use", id: b.id, name: tool.name, input });
    }
  }

  if (uses.length === 0) {
    if (!text) throw new Error("réponse vide de l'IA");
    return { type: "final", text };
  }
  const assistant: Block[] = [...(text ? [{ type: "text" as const, text }] : []), ...uses];
  return {
    type: "tool_calls",
    preface: text,
    assistant,
    calls: uses.map((u) => ({ id: u.id, name: u.name, input: u.input, sensitive: Boolean(TOOL_BY_NAME.get(u.name)?.sensitive) })),
  };
}
