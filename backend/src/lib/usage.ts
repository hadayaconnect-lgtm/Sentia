import { db } from "./supabase";

export interface UsageCounts {
  user: number;
  global: number;
}

/**
 * Incrémente le compteur du jour pour `subject` (appareil anonymisé, ou « sos:… ») et le compteur global.
 * Renvoie null si la base est indisponible : l'appelant décide alors de laisser passer (la disponibilité
 * pour une personne handicapée prime ; la limite par adresse IP reste active).
 */
export async function incrementUsage(subject: string): Promise<UsageCounts | null> {
  try {
    const day = new Date().toISOString().slice(0, 10);
    const { data, error } = await db().rpc("increment_usage", { p_subject: subject, p_day: day });
    if (error) throw new Error(error.message);
    const row = (Array.isArray(data) ? data[0] : data) as { user_count: number; global_count: number } | undefined;
    if (!row) return null;
    return { user: Number(row.user_count), global: Number(row.global_count) };
  } catch (error) {
    console.error("Quotas indisponibles :", (error as Error).message);
    return null;
  }
}
