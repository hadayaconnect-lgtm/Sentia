import { db } from "@/lib/supabase";
import type { Language, Service } from "@/types";

const LABELS: Record<Language, Record<"phone" | "address" | "hours" | "verified", string>> = {
  fr: { phone: "Téléphone", address: "Adresse", hours: "Horaires", verified: "Information vérifiée le" },
  en: { phone: "Phone", address: "Address", hours: "Opening hours", verified: "Information verified on" },
  ar: { phone: "الهاتف", address: "العنوان", hours: "ساعات العمل", verified: "تم التحقق من المعلومة في" },
  // Pas de libellés somali relus : repli sur le français (les chiffres restent compréhensibles).
  so: { phone: "Téléphone", address: "Adresse", hours: "Horaires", verified: "Information vérifiée le" },
};

/** Services vérifiés uniquement : l'IA ne voit et n'utilise jamais les entrées non vérifiées. */
export async function listVerifiedServices(): Promise<Service[]> {
  const { data, error } = await db()
    .from("services")
    .select("*")
    .eq("verified", true)
    .order("category")
    .order("name");
  if (error) throw new Error(`Annuaire inaccessible : ${error.message}`);
  return (data ?? []) as Service[];
}

/** Format texte simple d'une fiche service : seuls les champs présents en base sont affichés. */
export function formatService(service: Service, lang: Language): string {
  const l = LABELS[lang];
  const lines = [service.name];
  if (service.description) lines.push(service.description);
  if (service.phone) lines.push(`${l.phone} : ${service.phone}`);
  if (service.address) lines.push(`${l.address} : ${service.address}`);
  if (service.opening_hours) lines.push(`${l.hours} : ${service.opening_hours}`);
  if (service.verified_at) lines.push(`${l.verified} ${service.verified_at.slice(0, 10)}`);
  return lines.join("\n");
}

/**
 * Remplace chaque marqueur [[SERVICE:id]] par la fiche vérifiée issue de la base.
 * Un marqueur inconnu est supprimé : le modèle ne peut donc jamais injecter de coordonnées.
 */
export function resolveServiceMarkers(text: string, services: Service[], lang: Language): string {
  const byId = new Map(services.map((s) => [s.id, s]));
  const replaced = text.replace(/\[\[\s*SERVICE\s*:\s*([^\]\s]+)\s*\]\]/gi, (_m, id: string) => {
    const service = byId.get(id);
    return service ? `\n\n${formatService(service, lang)}\n\n` : "";
  });
  return replaced.replace(/\n{3,}/g, "\n\n").trim();
}

/** Services à proposer pour « Parler à une personne » : handicap d'abord, puis associations. */
export function pickHumanServices(services: Service[]): Service[] {
  const priority = ["handicap", "association", "social"];
  return services
    .filter((s) => priority.includes(s.category))
    .sort((a, b) => priority.indexOf(a.category) - priority.indexOf(b.category));
}
