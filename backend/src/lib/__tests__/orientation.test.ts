import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { formatService, pickHumanServices, resolveServiceMarkers } from "../../services/orientation.service";
import type { Service } from "@/types";

const svc = (over: Partial<Service>): Service => ({
  id: "anph", name: "ANPH", category: "handicap", description: null, phone: "+253 21 33 25 00",
  address: null, opening_hours: null, verified: true, verified_at: "2026-09-01T00:00:00Z", source: null, ...over,
});

describe("marqueurs de service", () => {
  it("remplace le marqueur par la fiche vérifiée", () => {
    const out = resolveServiceMarkers("Contactez [[SERVICE:anph]] pour de l'aide.", [svc({})], "fr");
    assert.match(out, /ANPH/);
    assert.match(out, /\+253 21 33 25 00/);
    assert.match(out, /vérifiée le 2026-09-01/);
  });

  it("supprime un marqueur inconnu : le modèle ne peut pas inventer de coordonnées", () => {
    const out = resolveServiceMarkers("Appelez [[SERVICE:inconnu]] maintenant.", [svc({})], "fr");
    assert.ok(!out.includes("[["));
    assert.ok(!out.includes("253"));
  });

  it("tolère des espaces et la casse dans le marqueur", () => {
    assert.match(resolveServiceMarkers("[[ service : anph ]]", [svc({})], "fr"), /ANPH/);
  });

  it("n'affiche que les champs présents et traduit les libellés", () => {
    const fr = formatService(svc({ address: "Rue 1", opening_hours: "8h-14h" }), "fr");
    assert.match(fr, /Adresse : Rue 1/);
    assert.match(fr, /Horaires : 8h-14h/);
    const ar = formatService(svc({}), "ar");
    assert.match(ar, /الهاتف/);
    assert.ok(!formatService(svc({ phone: null }), "fr").includes("Téléphone"));
  });

  it("classe les services pour « Parler à une personne »", () => {
    const list = pickHumanServices([
      svc({ id: "a", category: "association" }),
      svc({ id: "b", category: "urgence" }),
      svc({ id: "c", category: "handicap" }),
    ]);
    assert.deepEqual(list.map((s) => s.id), ["c", "a"]);
  });
});
