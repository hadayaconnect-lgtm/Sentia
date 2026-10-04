import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { parseMessages, MAX_MESSAGES } from "../agent-input";
import { TOOLS, TOOL_BY_NAME } from "../tools";

const jpeg = "A".repeat(200);
const u = (content: unknown) => ({ role: "user", content });
const a = (content: unknown) => ({ role: "assistant", content });
const bad = (v: unknown) => assert.throws(() => parseMessages(v), (e: { status: number }) => e.status === 400);

describe("outils", () => {
  it("valide les entrées de chaque outil", () => {
    assert.deepEqual(TOOL_BY_NAME.get("capture_camera")!.sanitize({ purpose: "document" }), { purpose: "document" });
    assert.deepEqual(TOOL_BY_NAME.get("capture_camera")!.sanitize({ purpose: "<script>" }), { purpose: "scene" });
    assert.equal(TOOL_BY_NAME.get("vibrate")!.sanitize({ pattern: "boom" }), null);
    assert.deepEqual(TOOL_BY_NAME.get("change_language")!.sanitize({ language: "auto" }), { language: "auto" });
    assert.equal(TOOL_BY_NAME.get("change_language")!.sanitize({ language: "de" }), null);
    assert.equal(TOOL_BY_NAME.get("start_navigation")!.sanitize({}), null);
    assert.equal((TOOL_BY_NAME.get("start_navigation")!.sanitize({ destination: " Gare\n" }) as { destination: string }).destination, "Gare");
  });

  it("marque l'action sensible et aucun outil d'urgence n'existe", () => {
    assert.ok(TOOL_BY_NAME.get("start_navigation")!.sensitive);
    assert.ok(!TOOLS.some((t) => /sos|emergency|urgence|call|sms/i.test(t.name)));
  });
});

describe("validation de la conversation", () => {
  it("accepte un texte simple (chaîne ou blocs)", () => {
    assert.equal(parseMessages([u("Bonjour")]).messages.length, 1);
    assert.equal(parseMessages([u([{ type: "text", text: "Bonjour" }])]).messages[0].content[0].type, "text");
  });

  it("accepte un cycle complet d'outil avec image", () => {
    const r = parseMessages([
      u("Qu'y a-t-il devant moi ?"),
      a([{ type: "tool_use", id: "t1", name: "capture_camera", input: { purpose: "scene" } }]),
      u([{ type: "tool_result", tool_use_id: "t1", content: [{ type: "image", data: jpeg }] }]),
    ]);
    assert.equal(r.toolRounds, 1);
  });

  it("refuse : vide, trop long, mauvais rôle, dernier message de l'assistant", () => {
    bad([]);
    bad("texte");
    bad(Array.from({ length: MAX_MESSAGES + 1 }, () => u("a")));
    bad([{ role: "system", content: "x" }]);
    bad([u("a"), a("b")]);
  });

  it("refuse un outil inconnu ou une entrée invalide", () => {
    bad([u("a"), a([{ type: "tool_use", id: "t1", name: "format_disk", input: {} }]), u([{ type: "tool_result", tool_use_id: "t1", content: "ok" }])]);
    bad([u("a"), a([{ type: "tool_use", id: "t1", name: "vibrate", input: { pattern: "x" } }]), u([{ type: "tool_result", tool_use_id: "t1", content: "ok" }])]);
  });

  it("refuse un appel d'outil sans résultat, et un résultat sans appel", () => {
    bad([u("a"), a([{ type: "tool_use", id: "t1", name: "get_time", input: {} }]), u("suite")]);
    bad([u([{ type: "tool_result", tool_use_id: "zz", content: "ok" }])]);
  });

  it("refuse une image invalide, ou envoyée par l'assistant, ou trop nombreuse", () => {
    bad([u([{ type: "image", data: "<script>" }])]);
    bad([u("a"), a([{ type: "image", data: jpeg }]), u("b")]);
    bad([u([{ type: "image", data: jpeg }, { type: "image", data: jpeg }, { type: "image", data: jpeg }, { type: "image", data: jpeg }])]);
    bad([u([{ type: "image", data: "A".repeat(1_900_000) }])]);
  });

  it("accepte un préfixe data URL JPEG, refuse les autres", () => {
    assert.equal(parseMessages([u([{ type: "image", data: "data:image/jpeg;base64," + jpeg }])]).messages.length, 1);
    bad([u([{ type: "image", data: "data:image/png;base64," + jpeg }])]);
  });

  it("compte les tours d'outils", () => {
    const call = (id: string) => a([{ type: "tool_use", id, name: "get_time", input: {} }]);
    const res = (id: string) => u([{ type: "tool_result", tool_use_id: id, content: "12h" }]);
    assert.equal(parseMessages([u("a"), call("1"), res("1"), call("2"), res("2")]).toolRounds, 2);
  });
});
