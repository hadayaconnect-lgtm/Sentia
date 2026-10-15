import { afterEach, beforeEach, describe, it } from "node:test";
import assert from "node:assert/strict";
import { buildLiveInstruction, createLiveSession, LIVE_TOOLS } from "@/services/live.service";

describe("conversation en direct : jeton éphémère", () => {
  const saved = { ...process.env };
  beforeEach(() => { process.env.GEMINI_API_KEY = "SECRET-KEY-SECRET-KEY-SECRET-KEY-123"; delete process.env.LIVE_MODEL; delete process.env.LIVE_WS_URL; });
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
