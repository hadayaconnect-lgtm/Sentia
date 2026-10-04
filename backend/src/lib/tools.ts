/**
 * Catalogue des outils que l'IA peut demander. Ils sont EXÉCUTÉS PAR L'APPLICATION (capteurs,
 * caméra, vibration…), jamais par le serveur : l'IA décide, le téléphone contrôle les permissions
 * et les actions sensibles. Toute entrée est validée ici avant d'être renvoyée à l'application.
 */
import { LANGUAGES } from "@/types";

export const VIBRATION_PATTERNS = ["awake", "info", "attention", "detected"] as const;
export const CAMERA_PURPOSES = ["scene", "document", "object", "color"] as const;

export interface ToolDef {
  name: string;
  description: string;
  input_schema: Record<string, unknown>;
  /** Action sensible : l'application demande une confirmation à la personne avant de l'exécuter. */
  sensitive: boolean;
  /** Nettoie et valide l'entrée ; renvoie null si invalide. */
  sanitize(input: unknown): Record<string, unknown> | null;
}

const obj = (properties: Record<string, unknown> = {}, required: string[] = []) => ({
  type: "object",
  properties,
  required,
  additionalProperties: false,
});

const asRecord = (v: unknown): Record<string, unknown> =>
  v && typeof v === "object" && !Array.isArray(v) ? (v as Record<string, unknown>) : {};

export const TOOLS: ToolDef[] = [
  {
    name: "capture_camera",
    description:
      "Prend une photo avec la caméra arrière du téléphone et vous la renvoie. À utiliser seulement quand la personne demande de regarder, lire, identifier un objet ou une couleur. Si la photo est floue ou mal cadrée, demandez à la personne de se repositionner puis rappelez l'outil.",
    input_schema: obj(
      { purpose: { type: "string", enum: [...CAMERA_PURPOSES], description: "scene, document, object ou color" } },
      ["purpose"]
    ),
    sensitive: false,
    sanitize(input) {
      const p = asRecord(input).purpose;
      return { purpose: (CAMERA_PURPOSES as readonly unknown[]).includes(p) ? p : "scene" };
    },
  },
  {
    name: "get_location",
    description:
      "Renvoie la position du téléphone (latitude, longitude, précision, adresse approximative). À utiliser seulement pour « où suis-je », une adresse ou un lieu proche.",
    input_schema: obj(),
    sensitive: false,
    sanitize: () => ({}),
  },
  {
    name: "get_time",
    description: "Renvoie la date et l'heure locales du téléphone.",
    input_schema: obj(),
    sensitive: false,
    sanitize: () => ({}),
  },
  {
    name: "get_recent_sounds",
    description:
      "Renvoie les derniers sons détectés localement par le téléphone (sonnette, klaxon, alarme, bébé, sonnerie…) avec un niveau de confiance. Ce sont des estimations, jamais des certitudes.",
    input_schema: obj(),
    sensitive: false,
    sanitize: () => ({}),
  },
  {
    name: "vibrate",
    description:
      "Fait vibrer le téléphone avec un motif simple : awake (réveillé), info (nouvelle information), attention (attention), detected (événement détecté).",
    input_schema: obj({ pattern: { type: "string", enum: [...VIBRATION_PATTERNS] } }, ["pattern"]),
    sensitive: false,
    sanitize(input) {
      const p = asRecord(input).pattern;
      return (VIBRATION_PATTERNS as readonly unknown[]).includes(p) ? { pattern: p } : null;
    },
  },
  {
    name: "change_language",
    description:
      "Change la langue de l'application et des réponses (fr, en, so, ar), ou « auto » pour détecter la langue parlée. À utiliser seulement si la personne le demande.",
    input_schema: obj({ language: { type: "string", enum: [...LANGUAGES, "auto"] } }, ["language"]),
    sensitive: false,
    sanitize(input) {
      const l = asRecord(input).language;
      return l === "auto" || (LANGUAGES as readonly unknown[]).includes(l) ? { language: l } : null;
    },
  },
  {
    name: "start_navigation",
    description:
      "Ouvre l'application de cartes du téléphone vers une destination, après confirmation de la personne. Ne donne jamais d'itinéraire vous-même.",
    input_schema: obj({ destination: { type: "string", description: "Nom ou adresse de la destination" } }, ["destination"]),
    sensitive: true,
    sanitize(input) {
      const d = asRecord(input).destination;
      if (typeof d !== "string") return null;
      const clean = d.replace(/[\u0000-\u001F]/g, " ").trim().slice(0, 120);
      return clean ? { destination: clean } : null;
    },
  },
];

export const TOOL_BY_NAME = new Map(TOOLS.map((t) => [t.name, t]));
