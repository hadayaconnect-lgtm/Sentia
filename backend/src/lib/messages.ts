/**
 * Mise en forme des textes : suppression des émojis et du markdown
 * (lecteurs d'écran et synthèse vocale les liraient à voix haute).
 */

/** Supprime les émojis (lus à voix haute par les lecteurs d'écran). */
export function stripEmojis(text: string): string {
  return text
    .replace(/[\p{Extended_Pictographic}️‍⃣]/gu, "")
    .replace(/[ \t]{2,}/g, " ")
    .replace(/[ \t]+\n/g, "\n")
    .trim();
}

/** Supprime le balisage markdown résiduel. */
export function stripMarkdown(text: string): string {
  return text
    .replace(/```[\s\S]*?```/g, (block) => block.replace(/```/g, ""))
    .replace(/`([^`]*)`/g, "$1")
    .replace(/\*\*([^*]+)\*\*/g, "$1")
    .replace(/__([^_]+)__/g, "$1")
    .replace(/^#{1,6}\s+/gm, "")
    .replace(/^\s*[-*•]\s+/gm, "")
    .trim();
}

/** Texte prêt pour la synthèse vocale : jamais d'émojis ni de balisage. */
export function textForSpeech(text: string): string {
  return stripEmojis(stripMarkdown(text)).replace(/\n{2,}/g, "\n\n");
}
