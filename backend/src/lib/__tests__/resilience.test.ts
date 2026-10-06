import { afterEach, beforeEach, describe, it } from "node:test";
import assert from "node:assert/strict";
import { AiHttpError, isTransient, openaiCompatProvider } from "@/services/ai/openai-compat";

const ok = () => new Response(JSON.stringify({ choices: [{ message: { content: "Bonjour" } }] }), { status: 200 });
const overloaded = () => new Response('{"error":{"code":503,"message":"high demand"}}', { status: 503 });

describe("résilience de l'IA (Gemini surchargé)", () => {
  const realFetch = globalThis.fetch;
  const saved = { ...process.env };
  beforeEach(() => {
    process.env.AI_PROVIDER = "gemini";
    process.env.GEMINI_API_KEY = "test-key";
    process.env.GEMINI_MODEL = "gemini-3.1-flash-lite";
    process.env.GEMINI_FALLBACK_MODEL = "gemini-2.5-flash";
  });
  afterEach(() => {
    globalThis.fetch = realFetch;
    process.env = { ...saved };
  });

  it("classe les erreurs passagères", () => {
    assert.equal(isTransient(new AiHttpError("x", 503)), true);
    assert.equal(isTransient(new AiHttpError("x", 429)), true);
    assert.equal(isTransient(new AiHttpError("x", 400)), false);
    assert.equal(isTransient(new AiHttpError("x", 401)), false);
  });

  it("réessaie après un 503 puis réussit", async () => {
    const models: string[] = [];
    let n = 0;
    globalThis.fetch = (async (_u: unknown, init: RequestInit) => {
      models.push(JSON.parse(String(init.body)).model);
      return n++ === 0 ? overloaded() : ok();
    }) as typeof fetch;
    const r = await openaiCompatProvider().generate({ system: "s", messages: [{ role: "user", content: [{ type: "text", text: "salut" }] }] });
    assert.equal(r.content[0].type, "text");
    assert.deepEqual(models, ["gemini-3.1-flash-lite", "gemini-3.1-flash-lite"]);
  });

  it("bascule sur le modèle de secours si le principal reste surchargé", async () => {
    const models: string[] = [];
    globalThis.fetch = (async (_u: unknown, init: RequestInit) => {
      const model = JSON.parse(String(init.body)).model;
      models.push(model);
      return model === "gemini-2.5-flash" ? ok() : overloaded();
    }) as typeof fetch;
    const r = await openaiCompatProvider().generate({ system: "s", messages: [{ role: "user", content: [{ type: "text", text: "salut" }] }] });
    assert.equal(r.content.length, 1);
    assert.equal(models.at(-1), "gemini-2.5-flash");
  });

  it("ne réessaie pas une erreur de requête (400)", async () => {
    let n = 0;
    globalThis.fetch = (async () => { n++; return new Response("bad", { status: 400 }); }) as typeof fetch;
    await assert.rejects(openaiCompatProvider().generate({ system: "s", messages: [{ role: "user", content: [{ type: "text", text: "x" }] }] }), /HTTP 400/);
    assert.equal(n, 1);
  });
});
