import { env } from "@/lib/env";
import type { Block, Message } from "@/types";
import type { AIProvider, GenerateRequest, GenerateResult } from "./types";

/**
 * Fournisseurs « compatibles OpenAI » : OpenAI, Gemini (point d'accès compatible), Llama API de Meta.
 * Un seul adaptateur pour les trois ; seuls l'adresse, la clé et le modèle changent.
 */
type OMessage = Record<string, unknown>;

const imageUrl = (data: string) => ({ type: "image_url", image_url: { url: `data:image/jpeg;base64,${data}` } });

export function toOpenAIMessages(system: string, messages: Message[]): OMessage[] {
  const out: OMessage[] = [{ role: "system", content: system }];
  for (const m of messages) {
    if (m.role === "assistant") {
      const text = m.content.filter((b) => b.type === "text").map((b) => (b as { text: string }).text).join("\n");
      const calls = m.content
        .filter((b) => b.type === "tool_use")
        .map((b) => {
          const u = b as { id: string; name: string; input: unknown };
          return { id: u.id, type: "function", function: { name: u.name, arguments: JSON.stringify(u.input ?? {}) } };
        });
      out.push({ role: "assistant", content: text || null, ...(calls.length ? { tool_calls: calls } : {}) });
      continue;
    }
    // Message utilisateur : les résultats d'outils d'abord (rôle « tool »), puis le reste.
    const extra: unknown[] = [];
    for (const b of m.content) {
      if (b.type !== "tool_result") continue;
      const text = b.content.filter((c) => c.type === "text").map((c) => (c as { text: string }).text).join("\n");
      const images = b.content.filter((c) => c.type === "image");
      out.push({ role: "tool", tool_call_id: b.tool_use_id, content: text || (images.length ? "(image jointe ci-dessous)" : "(aucun résultat)") });
      for (const i of images) {
        extra.push({ type: "text", text: "Image renvoyée par l'outil :" }, imageUrl((i as { data: string }).data));
      }
    }
    const parts: unknown[] = [...extra];
    for (const b of m.content) {
      if (b.type === "text") parts.push({ type: "text", text: b.text });
      else if (b.type === "image") parts.push(imageUrl(b.data));
    }
    if (parts.length === 1 && (parts[0] as { type: string }).type === "text") {
      out.push({ role: "user", content: (parts[0] as { text: string }).text });
    } else if (parts.length > 0) {
      out.push({ role: "user", content: parts });
    }
  }
  return out;
}

function parseArgs(raw: unknown): Record<string, unknown> {
  if (raw && typeof raw === "object") return raw as Record<string, unknown>;
  try {
    const parsed = JSON.parse(String(raw ?? "{}"));
    return parsed && typeof parsed === "object" && !Array.isArray(parsed) ? parsed : {};
  } catch {
    return {};
  }
}

/** Réponse compatible OpenAI (ou format natif Llama « completion_message ») → blocs neutres. */
export function fromOpenAIResponse(payload: unknown): Block[] {
  const p = payload as {
    choices?: { message?: Record<string, unknown> }[];
    completion_message?: Record<string, unknown>;
  };
  const message = p?.choices?.[0]?.message ?? p?.completion_message;
  if (!message) return [];
  const out: Block[] = [];

  const content = message.content as unknown;
  let text = "";
  if (typeof content === "string") text = content;
  else if (Array.isArray(content)) text = content.map((c) => (typeof c === "string" ? c : typeof (c as { text?: unknown })?.text === "string" ? (c as { text: string }).text : "")).join("\n");
  else if (content && typeof content === "object" && typeof (content as { text?: unknown }).text === "string") text = (content as { text: string }).text;
  if (text.trim()) out.push({ type: "text", text: text.trim() });

  const calls = (message.tool_calls as { id?: unknown; function?: { name?: unknown; arguments?: unknown } }[] | undefined) ?? [];
  calls.forEach((c, i) => {
    if (!c?.function?.name) return;
    const id = typeof c.id === "string" && /^[A-Za-z0-9_-]{1,64}$/.test(c.id) ? c.id : `call_${i}`;
    out.push({ type: "tool_use", id, name: String(c.function.name), input: parseArgs(c.function.arguments) });
  });
  return out;
}

export function openaiCompatProvider(): AIProvider {
  const cfg = env.compat;
  return {
    label: cfg.label,
    async generate(request: GenerateRequest): Promise<GenerateResult> {
      const response = await fetch(`${cfg.baseUrl}/chat/completions`, {
        method: "POST",
        headers: { Authorization: `Bearer ${cfg.apiKey}`, "Content-Type": "application/json" },
        body: JSON.stringify({
          model: cfg.model,
          max_tokens: request.maxTokens ?? 700,
          messages: toOpenAIMessages(request.system, request.messages),
          ...(request.tools?.length
            ? {
                tools: request.tools.map((t) => ({
                  type: "function",
                  function: { name: t.name, description: t.description, parameters: t.input_schema },
                })),
              }
            : {}),
        }),
        signal: AbortSignal.timeout(60_000),
      });
      if (!response.ok) throw new Error(`${cfg.label} a répondu HTTP ${response.status}`);
      return { content: fromOpenAIResponse(await response.json()) };
    },
  };
}
