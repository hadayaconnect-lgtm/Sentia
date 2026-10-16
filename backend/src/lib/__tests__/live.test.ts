import { afterEach, beforeEach, describe, it } from "node:test";
import assert from "node:assert/strict";
import { buildLiveInstruction, createLiveSession, LIVE_TOOLS } from "@/services/live.service";

describe("conversation en direct : jeton éphémère", () => {
  const saved = { ...process.env };
  beforeEach(() => { process.env.GEMINI_API_KEY = "SECRET-KEY-SECRET-KEY-SECRET-KEY-123"; delete process.env.LIVE_MODEL; delete process.env.LIVE_WS_URL; process.env.LIVE_PROVIDER = "gemini"; });
  afterEach(() => { process.env = { ...saved }; });

  it("demande un jeton à usage unique, limité au modèle et à l'audio, avec la clé en en-tête seulement", async () => {
    let seen: { url: string; init: RequestInit } | null = null;
    const fetchImpl = (async (url: string, init: RequestInit) => {
      seen = { url, init };
      return new Response(JSON.stringify({ name: "auth_tokens/abc" }), { status: 200 });
    }) as unknown as typeof fetch;
    const out = await createLiveSession({ lang: "fr", profile: "blind", fetchImpl, now: () => Date.UTC(2026, 9, 13, 12, 0, 0) });
    assert.equal(seen!.url, "https://generativelanguage.googleapis.com/v1beta/auth_tokens");
    const headers = seen!.init.headers as Record<string, string>;
    assert.equal(headers["x-goog-api-key"], process.env.GEMINI_API_KEY);
    const body = JSON.parse(seen!.init.body as string);
    assert.equal(body.uses, 1);
    assert.equal(body.liveConnectConstraints.model, "models/gemini-3.8-live");
    assert.deepEqual(body.liveConnectConstraints.config.responseModalities, ["AUDIO"]);
    assert.equal(body.newSessionExpireTime, "2026-10-13T12:01:30.000Z");
    assert.equal(out.token, "auth_tokens/abc");
    assert.ok(out.urls.length >= 1 && out.urls.every((u) => u.startsWith("wss://")));
    assert.ok(!JSON.stringify(out).includes("SECRET-KEY"), "la clé secrète ne doit jamais être renvoyée");
  });

  it("erreur Google : message clair, sans clé", async () => {
    const fetchImpl = (async () => new Response('{"error":{"message":"billing required SECRET-KEY-SECRET-KEY-SECRET-KEY-123"}}', { status: 403 })) as unknown as typeof fetch;
    await assert.rejects(createLiveSession({ lang: null, profile: "blind", fetchImpl }), (e: Error) => /403/.test(e.message) && !e.message.includes("SECRET-KEY"));
  });

  it("réponse sans jeton : refusée", async () => {
    const fetchImpl = (async () => new Response("{}", { status: 200 })) as unknown as typeof fetch;
    await assert.rejects(createLiveSession({ lang: null, profile: "blind", fetchImpl }));
  });

  it("adresses et modèle modifiables par variables d'environnement", async () => {
    process.env.LIVE_MODEL = "gemini-3.8-live-extended-thinking";
    process.env.LIVE_WS_URL = "wss://a.example/x, wss://b.example/y";
    const fetchImpl = (async () => new Response(JSON.stringify({ name: "t" }), { status: 200 })) as unknown as typeof fetch;
    const out = await createLiveSession({ lang: null, profile: "blind", fetchImpl });
    assert.equal(out.model, "models/gemini-3.8-live-extended-thinking");
    assert.deepEqual(out.urls, ["wss://a.example/x", "wss://b.example/y"]);
  });
});

describe("conversation en direct : consignes", () => {
  const p = buildLiveInstruction("fr", "blind");
  it("garde les protections anti-hallucination et de sécurité", () => {
    assert.ok(p.includes("Je ne peux pas le confirmer."));
    assert.ok(p.includes("Je ne vois pas clairement de billet."));
    assert.match(p, /JAMAIS qu'il est sûr de traverser/);
    assert.match(p, /Ne donne JAMAIS de distance précise/);
    assert.match(p, /injection/i);
  });
  it("photo unique, nouvelle photo pour la sécurité, pas de vidéo continue", () => {
    assert.match(p, /Une seule photo par demande/);
    assert.match(p, /NOUVELLE photo/);
    assert.match(p, /PAS de vidéo continue/);
    assert.match(p, /Je regarde\./);
  });
  it("outil de photo déclaré avec ses objectifs, et rien de dangereux", () => {
    const names = LIVE_TOOLS[0].functionDeclarations.map((d) => d.name);
    assert.deepEqual(names, ["capture_camera", "get_time"]);
    const cam = LIVE_TOOLS[0].functionDeclarations[0].parameters as { properties: { purpose: { enum: string[] } } };
    assert.ok(cam.properties.purpose.enum.includes("money"));
  });
});

describe("conversation en direct : OpenAI Realtime (par défaut)", () => {
  const saved = { ...process.env };
  beforeEach(() => { process.env.OPENAI_API_KEY = "sk-SECRET-SECRET-SECRET-SECRET-SECRET-123"; delete process.env.LIVE_PROVIDER; delete process.env.OPENAI_LIVE_MODEL; delete process.env.OPENAI_LIVE_VOICE; delete process.env.OPENAI_LIVE_URL; });
  afterEach(() => { process.env = { ...saved }; });

  it("crée une clé temporaire chez OpenAI ; la vraie clé ne quitte jamais le serveur", async () => {
    let seen: { url: string; init: RequestInit } | null = null;
    const fetchImpl = (async (url: string, init: RequestInit) => {
      seen = { url, init };
      return new Response(JSON.stringify({ value: "ek_temp_123", expires_at: 1 }), { status: 200 });
    }) as unknown as typeof fetch;
    const out = await createLiveSession({ lang: "fr", profile: "blind", fetchImpl });
    assert.equal(seen!.url, "https://api.openai.com/v1/realtime/client_secrets");
    assert.equal((seen!.init.headers as Record<string, string>).Authorization, `Bearer ${process.env.OPENAI_API_KEY}`);
    const body = JSON.parse(seen!.init.body as string);
    assert.equal(body.session.type, "realtime");
    assert.equal(body.session.model, "gpt-realtime-2");
    assert.equal(body.session.audio.output.voice, "marin");
    assert.equal(out.provider, "openai");
    assert.equal(out.token, "ek_temp_123");
    assert.deepEqual(out.urls, ["wss://api.openai.com/v1/realtime?model=gpt-realtime-2"]);
    assert.equal(out.voice, "marin");
    const tools = out.tools as { type: string; name: string }[];
    assert.ok(tools.every((t) => t.type === "function") && tools.some((t) => t.name === "capture_camera"));
    assert.ok(out.instruction.includes("capture_camera"));
    assert.ok(!JSON.stringify(out).includes("SECRET"), "la clé secrète ne doit jamais être renvoyée");
  });

  it("modèle, voix et adresse modifiables par variables d'environnement", async () => {
    process.env.OPENAI_LIVE_MODEL = "gpt-realtime-2.1";
    process.env.OPENAI_LIVE_VOICE = "cedar";
    const fetchImpl = (async () => new Response(JSON.stringify({ value: "ek_x" }), { status: 200 })) as unknown as typeof fetch;
    const out = await createLiveSession({ lang: null, profile: "blind", fetchImpl });
    assert.equal(out.model, "gpt-realtime-2.1");
    assert.equal(out.voice, "cedar");
    assert.ok(out.urls[0].endsWith("?model=gpt-realtime-2.1"));
  });

  it("refus d'OpenAI : message clair avec le code HTTP, sans clé", async () => {
    const fetchImpl = (async () => new Response('{"error":{"message":"Incorrect key sk-SECRET-SECRET-SECRET-SECRET-SECRET-123"}}', { status: 401 })) as unknown as typeof fetch;
    await assert.rejects(createLiveSession({ lang: null, profile: "blind", fetchImpl }), (e: Error) => /401/.test(e.message) && !e.message.includes("SECRET"));
  });

  it("réponse sans clé temporaire : refusée", async () => {
    const fetchImpl = (async () => new Response("{}", { status: 200 })) as unknown as typeof fetch;
    await assert.rejects(createLiveSession({ lang: null, profile: "blind", fetchImpl }));
  });

  it("LIVE_PROVIDER=gemini garde Gemini Live disponible", async () => {
    process.env.GEMINI_API_KEY = "g-key-g-key-g-key-g-key-g-key-123456";
    process.env.LIVE_PROVIDER = "gemini";
    const fetchImpl = (async () => new Response(JSON.stringify({ name: "auth_tokens/abc" }), { status: 200 })) as unknown as typeof fetch;
    const out = await createLiveSession({ lang: null, profile: "blind", fetchImpl });
    assert.equal(out.provider, "gemini");
  });
});
