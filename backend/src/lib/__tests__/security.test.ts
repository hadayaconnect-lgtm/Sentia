import assert from "node:assert/strict";
import { beforeEach, describe, it } from "node:test";
import { isValidDeviceId, isSameOrigin, rateLimited, resetRateLimits, safeEqual, deviceHmac } from "../security";

describe("security", () => {
  beforeEach(() => resetRateLimits());

  it("valide l'identifiant d'appareil", () => {
    assert.ok(isValidDeviceId("123e4567-e89b-12d3-a456-426614174000"));
    assert.ok(!isValidDeviceId("court"));
    assert.ok(!isValidDeviceId("+25377123456789012"));
    assert.ok(!isValidDeviceId("a".repeat(65)));
    assert.ok(!isValidDeviceId(null));
  });

  it("anonymise l'identifiant avec un HMAC stable", () => {
    process.env.DEVICE_HMAC_SECRET = "secret-de-test";
    const a = deviceHmac("123e4567-e89b-12d3-a456-426614174000");
    assert.equal(a, deviceHmac("123e4567-e89b-12d3-a456-426614174000"));
    assert.notEqual(a, deviceHmac("123e4567-e89b-12d3-a456-426614174001"));
    assert.ok(!a.includes("123e4567"));
  });

  it("compare à temps constant", () => {
    assert.ok(safeEqual("abc", "abc"));
    assert.ok(!safeEqual("abc", "abd"));
    assert.ok(!safeEqual("abc", ""));
  });

  it("n'accepte que la même origine", () => {
    const req = (origin: string | null) =>
      new Request("https://app.example/api/chat", {
        method: "POST",
        headers: { host: "app.example", ...(origin ? { origin } : {}) },
      });
    assert.ok(isSameOrigin(req("https://app.example")));
    assert.ok(!isSameOrigin(req("https://evil.example")));
    assert.ok(!isSameOrigin(req(null)));
    assert.ok(!isSameOrigin(req("pas une url")));
  });

  it("limite le débit par fenêtre glissante", () => {
    const t0 = 1_000_000;
    for (let i = 0; i < 3; i++) assert.ok(!rateLimited("k", 3, 60_000, t0 + i));
    assert.ok(rateLimited("k", 3, 60_000, t0 + 10));
    assert.ok(!rateLimited("k", 3, 60_000, t0 + 61_000));
    assert.ok(!rateLimited("autre", 3, 60_000, t0));
  });
});
