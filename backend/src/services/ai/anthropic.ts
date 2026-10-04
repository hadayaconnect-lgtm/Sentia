import Anthropic from "@anthropic-ai/sdk";
import { env } from "@/lib/env";
import type { Block, Message } from "@/types";
import type { AIProvider, GenerateRequest, GenerateResult } from "./types";

type AMessage = Anthropic.MessageParam;

const img = (data: string) => ({
  type: "image" as const,
  source: { type: "base64" as const, media_type: "image/jpeg" as const, data },
});

/** Messages neutres → format Claude. */
export function toAnthropicMessages(messages: Message[]): AMessage[] {
  return messages.map((m) => ({
    role: m.role,
    content: m.content.map((b) => {
      switch (b.type) {
        case "text":
          return { type: "text" as const, text: b.text };
        case "image":
          return img(b.data);
        case "tool_use":
          return { type: "tool_use" as const, id: b.id, name: b.name, input: b.input };
        case "tool_result":
          return {
            type: "tool_result" as const,
            tool_use_id: b.tool_use_id,
            is_error: b.is_error,
            content: b.content.map((c) => (c.type === "text" ? { type: "text" as const, text: c.text } : img(c.data))),
          };
      }
    }),
  })) as AMessage[];
}

/** Réponse Claude → blocs neutres. */
export function fromAnthropicContent(content: { type: string; [k: string]: unknown }[]): Block[] {
  const out: Block[] = [];
  for (const b of content) {
    if (b.type === "text" && typeof b.text === "string" && b.text.trim()) out.push({ type: "text", text: b.text.trim() });
    else if (b.type === "tool_use") {
      out.push({ type: "tool_use", id: String(b.id), name: String(b.name), input: (b.input as Record<string, unknown>) ?? {} });
    }
  }
  return out;
}

let client: Anthropic | null = null;

export const anthropicProvider: AIProvider = {
  label: "Claude (Anthropic)",
  async generate(request: GenerateRequest): Promise<GenerateResult> {
    if (!client) {
      client = new Anthropic({
        apiKey: env.anthropicApiKey,
        baseURL: process.env.ANTHROPIC_BASE_URL || undefined,
        timeout: 60_000,
        maxRetries: 2,
      });
    }
    const response = await client.messages.create({
      model: env.claudeModel,
      max_tokens: request.maxTokens ?? 700,
      system: request.system,
      messages: toAnthropicMessages(request.messages),
      ...(request.tools?.length
        ? {
            tools: request.tools.map((t) => ({
              name: t.name,
              description: t.description,
              input_schema: t.input_schema as Anthropic.Tool.InputSchema,
            })),
          }
        : {}),
    });
    return { content: fromAnthropicContent(response.content as never) };
  },
};
