import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { fromOpenAIResponse, toOpenAIMessages } from "../../services/ai/openai-compat";
import { fromAnthropicContent, toAnthropicMessages } from "../../services/ai/anthropic";
import { buildAgentPrompt } from "../prompts";
import { extensionFor } from "../../services/transcription.service";
import type { Message, Service } from "@/types";

const convo: Message[] = [
  { role: "user", content: [{ type: "text", text: "Qu'y a-t-il devant moi ?" }] },
  { role: "assistant", content: [{ type: "text", text: "Je regarde." }, { type: "tool_use", id: "t1", name: "capture_camera", input: { purpose: "scene" } }] },
  { role: "user", content: [{ type: "tool_result", tool_use_id: "t1", content: [{ type: "image", data: "QUJD" }] }] },
];

describe("adaptateur Claude", () => {
  it("convertit appel d'outil et résultat avec image", () => {
    const m = toAnthropicMessages(convo) as unknown as { role: string; content: { type: string; content?: { type: string }[] }[] }[];
    assert.equal(m[1].content[1].type, "tool_use");
    assert.equal(m[2].content[0].type, "tool_result");
    assert.equal(m[2].content[0].content![0].type, "image");
  });
  it("lit texte et appels d'outils", () => {
    const blocks = fromAnthropicContent([{ type: "text", text: " Bonjour " }, { type: "tool_use", id: "x", name: "get_time", input: {} }, { type: "thinking" }]);
    assert.deepEqual(blocks.map((b) => b.type), ["text", "tool_use"]);
  });
});

describe("adaptateur OpenAI / Gemini / Llama", () => {
  it("convertit la conversation : rôle tool puis image dans un message utilisateur", () => {
    const m = toOpenAIMessages("SYS", convo) as { role: string; content?: unknown; tool_calls?: { id: string }[]; tool_call_id?: string }[];
    assert.equal(m[0].role, "system");
    assert.equal(m[2].role, "assistant");
    assert.equal(m[2].tool_calls![0].id, "t1");
    assert.equal(m[3].role, "tool");
    assert.equal(m[3].tool_call_id, "t1");
    assert.equal(m[4].role, "user");
    assert.ok(JSON.stringify(m[4].content).includes("data:image/jpeg;base64,QUJD"));
  });
  it("lit le format OpenAI avec appels d'outils", () => {
    const blocks = fromOpenAIResponse({ choices: [{ message: { content: "Je regarde.", tool_calls: [{ id: "c1", type: "function", function: { name: "capture_camera", arguments: '{"purpose":"document"}' } }] } }] });
    assert.deepEqual(blocks.map((b) => b.type), ["text", "tool_use"]);
    assert.deepEqual((blocks[1] as { input: unknown }).input, { purpose: "document" });
  });
  it("tolère des arguments invalides et un identifiant absent", () => {
    const blocks = fromOpenAIResponse({ choices: [{ message: { content: null, tool_calls: [{ function: { name: "get_time", arguments: "pas du json" } }] } }] });
    assert.equal((blocks[0] as { id: string }).id, "call_0");
    assert.deepEqual((blocks[0] as { input: unknown }).input, {});
  });
  it("lit le format Llama natif", () => {
    assert.equal((fromOpenAIResponse({ completion_message: { content: { type: "text", text: "Salut" } } })[0] as { text: string }).text, "Salut");
    assert.deepEqual(fromOpenAIResponse({}), []);
  });
});

const svc: Service = { id: "anph", name: "ANPH", category: "handicap", description: null, phone: "+253 21 33 25 00", address: null, opening_hours: null, verified: true, verified_at: null, source: null };

describe("prompt SENTIA", () => {
  const base = { lang: "fr" as const, profile: "blind" as const, services: [svc], toolsAvailable: true };
  it("n'expose jamais de coordonnées, seulement l'identifiant", () => {
    const p = buildAgentPrompt(base);
    assert.match(p, /id=anph/);
    assert.ok(!p.includes("+253"));
  });
  it("interdit distances précises, « sûr de traverser » et posologie", () => {
    const p = buildAgentPrompt(base);
    assert.match(p, /distance précise/);
    assert.match(p, /JAMAIS qu'il est sûr de traverser/);
    assert.match(p, /posologie/);
    assert.match(p, /injection/i);
  });
  it("n'a pas de fonction d'urgence", () => {
    assert.match(buildAgentPrompt(base), /pas de fonction d'urgence/);
  });
  it("adapte au profil", () => {
    assert.match(buildAgentPrompt(base), /LUES À VOIX HAUTE/);
    assert.match(buildAgentPrompt({ ...base, profile: "deaf" }), /LUES À L'ÉCRAN/);
    assert.match(buildAgentPrompt({ ...base, profile: "deaf" }), /Ne dis jamais « écoutez »/);
  });
  it("gère les langues : imposée ou automatique", () => {
    assert.match(buildAgentPrompt({ ...base, lang: "ar" }), /Réponds en arabe/);
    assert.match(buildAgentPrompt({ ...base, lang: "so" }), /somali/);
    assert.match(buildAgentPrompt({ ...base, lang: "en" }), /Réponds en anglais/);
    assert.match(buildAgentPrompt({ ...base, lang: null }), /langue de la dernière demande/);
  });
  it("retire les outils quand la limite est atteinte", () => {
    assert.match(buildAgentPrompt({ ...base, toolsAvailable: false }), /Aucun outil/);
    assert.ok(!buildAgentPrompt({ ...base, toolsAvailable: false }).includes("capture_camera"));
  });
  it("annonce l'absence d'annuaire", () => {
    assert.match(buildAgentPrompt({ ...base, services: [] }), /AUCUN service/);
  });
});

describe("formats audio", () => {
  it("reconnaît les formats courants", () => {
    assert.equal(extensionFor("audio/webm;codecs=opus"), "webm");
    assert.equal(extensionFor("audio/ogg; codecs=opus"), "ogg");
    assert.equal(extensionFor("audio/mp4"), "m4a");
    assert.equal(extensionFor("audio/mpeg"), "mp3");
    assert.equal(extensionFor("audio/wav"), "wav");
    assert.equal(extensionFor("application/octet-stream"), null);
  });
});
