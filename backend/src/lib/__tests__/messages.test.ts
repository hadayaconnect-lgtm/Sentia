import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { stripEmojis, stripMarkdown, textForSpeech } from "../messages";

describe("stripEmojis / stripMarkdown", () => {
  it("retire les émojis", () => {
    assert.equal(stripEmojis("Bonjour 👋 voici 📷 la photo ✅"), "Bonjour voici la photo");
  });

  it("garde les chiffres, y compris dans les listes numérotées", () => {
    assert.equal(stripEmojis("1. Texte\n2. Vocal"), "1. Texte\n2. Vocal");
  });

  it("retire le markdown", () => {
    assert.equal(stripMarkdown("**Date** : 15 octobre\n- point un\n# Titre"), "Date : 15 octobre\npoint un\nTitre");
  });

  it("prépare un texte pour la voix sans émoji ni balisage", () => {
    assert.equal(textForSpeech("**Important** 📅 rendez-vous mardi"), "Important rendez-vous mardi");
  });
});
