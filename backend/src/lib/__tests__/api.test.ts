import assert from "node:assert/strict";
import { describe, it, beforeEach } from "node:test";
import { cleanText, finalizeText, guard, parseLang } from "../api";
import { resetRateLimits } from "../security";
import type { Service } from "@/types";

describe("entrées", () => {
  it("nettoie le texte", () => {
    assert.equal(cleanText("  a\u0000b  ", 10), "ab");
    assert.equal(cleanText("abcdef", 3), "abc");
    assert.equal(cleanText(42, 3), "");
  });

  it("choisit la langue avec le français par défaut", () => {
    assert.equal(parseLang("ar"), "ar");
    assert.equal(parseLang("so"), "so");
    assert.equal(parseLang("en"), "en");
    assert.equal(parseLang("xx"), "fr");
  });

  it("finalise : marqueurs résolus, markdown et émojis retirés", () => {
    const s: Service = { id: "anph", name: "ANPH", category: "handicap", description: null, phone: "+253 21 33 25 00", address: null, opening_hours: null, verified: true, verified_at: null, source: null };
    const out = finalizeText("**Appelez** 📞 [[SERVICE:anph]] ou [[SERVICE:faux]]", [s], "fr");
    assert.ok(!out.includes("*") && !out.includes("📞") && !out.includes("[["));
    assert.match(out, /\+253 21 33 25 00/);
  });
});

describe("guard (accès)", () => {
  beforeEach(() => {
    resetRateLimits();
    process.env.DEVICE_HMAC_SECRET = "s";
    delete process.env.APP_ACCESS_CODE;
    delete process.env.NATIVE_CLIENT_ENABLED;
  });
  const req = (headers: Record<string, string>) =>
    new Request("https://sentia.example/api/agent", { method: "POST", headers: { host: "sentia.example", ...headers } });
  const dev = "123e4567-e89b-12d3-a456-426614174000";

  it("accepte l'application Android (pas d'Origin, en-tête client, identifiant d'installation)", async () => {
    const r = await guard(req({ "x-client": "sentia-android", "x-device-id": dev }));
    assert.equal(r.deviceId, dev);
  });

  it("refuse une requête sans Origin ni en-tête client", async () => {
    await assert.rejects(guard(req({ "x-device-id": dev })), (e: { status: number }) => e.status === 403);
  });

  it("refuse une origine étrangère même avec l'en-tête client", async () => {
    await assert.rejects(
      guard(req({ "x-client": "sentia-android", origin: "https://evil.example", "x-device-id": dev })),
      (e: { status: number }) => e.status === 403
    );
  });

  it("refuse l'application Android si désactivée", async () => {
    process.env.NATIVE_CLIENT_ENABLED = "false";
    await assert.rejects(guard(req({ "x-client": "sentia-android", "x-device-id": dev })), (e: { status: number }) => e.status === 403);
  });

  it("exige le code d'accès quand il est configuré", async () => {
    process.env.APP_ACCESS_CODE = "1234";
    await assert.rejects(guard(req({ "x-client": "sentia-android", "x-device-id": dev })), (e: { status: number }) => e.status === 401);
    const ok = await guard(req({ "x-client": "sentia-android", "x-device-id": dev, "x-access-code": "1234" }));
    assert.equal(ok.deviceId, dev);
  });

  it("exige un identifiant d'installation valide", async () => {
    await assert.rejects(guard(req({ "x-client": "sentia-android", "x-device-id": "court" })), (e: { status: number }) => e.status === 400);
  });
});
