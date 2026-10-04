import { ApiError, cleanText } from "./api";
import { TOOL_BY_NAME } from "./tools";
import type { Block, ImageBlock, Message, TextBlock } from "@/types";

export const MAX_MESSAGES = 16;
export const MAX_TOOL_ROUNDS = 4;
const MAX_TEXT = 2000;
const MAX_TOOL_TEXT = 4000;
const MAX_IMAGE_CHARS = 1_800_000;
const MAX_IMAGES = 3;
const ID = /^[A-Za-z0-9_-]{1,64}$/;
const B64 = /^[A-Za-z0-9+/]+={0,2}$/;

function bad(): never {
  throw new ApiError(400, "messages");
}

function parseImage(data: unknown): string {
  if (typeof data !== "string") return bad();
  const clean = data.startsWith("data:image/jpeg;base64,") ? data.slice("data:image/jpeg;base64,".length) : data;
  if (clean.length < 100 || clean.length > MAX_IMAGE_CHARS || !B64.test(clean)) return bad();
  return clean;
}

/**
 * Valide strictement la conversation envoyée par l'application : rôles, blocs, outils autorisés,
 * entrées d'outils nettoyées, limites de taille. Rien n'est transmis à l'IA sans être passé ici.
 */
export function parseMessages(value: unknown): { messages: Message[]; toolRounds: number } {
  if (!Array.isArray(value) || value.length === 0 || value.length > MAX_MESSAGES) bad();
  const out: Message[] = [];
  let images = 0;
  let rounds = 0;

  for (const raw of value as unknown[]) {
    if (!raw || typeof raw !== "object") bad();
    const { role, content } = raw as { role?: unknown; content?: unknown };
    if (role !== "user" && role !== "assistant") bad();

    const rawBlocks: unknown[] = typeof content === "string" ? [{ type: "text", text: content }] : Array.isArray(content) ? content : bad();
    const blocks: Block[] = [];

    for (const b of rawBlocks) {
      if (!b || typeof b !== "object") bad();
      const block = b as Record<string, unknown>;
      switch (block.type) {
        case "text": {
          const text = cleanText(block.text, MAX_TEXT);
          if (text) blocks.push({ type: "text", text });
          break;
        }
        case "image": {
          if (role !== "user" || ++images > MAX_IMAGES) bad();
          blocks.push({ type: "image", data: parseImage(block.data) });
          break;
        }
        case "tool_use": {
          if (role !== "assistant" || typeof block.id !== "string" || !ID.test(block.id)) bad();
          const tool = TOOL_BY_NAME.get(String(block.name));
          const input = tool?.sanitize(block.input);
          if (!tool || !input) bad();
          rounds++;
          blocks.push({ type: "tool_use", id: block.id, name: tool.name, input });
          break;
        }
        case "tool_result": {
          if (role !== "user" || typeof block.tool_use_id !== "string" || !ID.test(block.tool_use_id)) bad();
          const inner: (TextBlock | ImageBlock)[] = [];
          const parts: unknown[] = typeof block.content === "string" ? [{ type: "text", text: block.content }] : Array.isArray(block.content) ? block.content : bad();
          for (const p of parts) {
            if (!p || typeof p !== "object") bad();
            const part = p as Record<string, unknown>;
            if (part.type === "text") {
              const text = cleanText(part.text, MAX_TOOL_TEXT);
              if (text) inner.push({ type: "text", text });
            } else if (part.type === "image") {
              if (++images > MAX_IMAGES) bad();
              inner.push({ type: "image", data: parseImage(part.data) });
            } else bad();
          }
          if (inner.length === 0) inner.push({ type: "text", text: "(aucun résultat)" });
          blocks.push({
            type: "tool_result",
            tool_use_id: block.tool_use_id,
            content: inner,
            ...(block.is_error === true ? { is_error: true } : {}),
          });
          break;
        }
        default:
          bad();
      }
    }
    if (blocks.length === 0) continue;
    out.push({ role, content: blocks });
  }

  if (out.length === 0 || out[0].role !== "user" || out[out.length - 1].role !== "user") bad();

  // Chaque appel d'outil doit recevoir sa réponse dans le message suivant (exigé par les fournisseurs).
  for (let i = 0; i < out.length; i++) {
    const uses = out[i].content.filter((b) => b.type === "tool_use").map((b) => (b as { id: string }).id);
    if (uses.length === 0) continue;
    const next = out[i + 1];
    if (!next || next.role !== "user") bad();
    const answered = new Set(next.content.filter((b) => b.type === "tool_result").map((b) => (b as { tool_use_id: string }).tool_use_id));
    if (!uses.every((id) => answered.has(id))) bad();
  }
  // Et inversement : un résultat d'outil doit répondre à un appel du message précédent.
  for (let i = 0; i < out.length; i++) {
    const results = out[i].content.filter((b) => b.type === "tool_result").map((b) => (b as { tool_use_id: string }).tool_use_id);
    if (results.length === 0) continue;
    const prev = out[i - 1];
    const ids = new Set((prev?.role === "assistant" ? prev.content : []).filter((b) => b.type === "tool_use").map((b) => (b as { id: string }).id));
    if (!results.every((id) => ids.has(id))) bad();
  }
  return { messages: out, toolRounds: rounds };
}
