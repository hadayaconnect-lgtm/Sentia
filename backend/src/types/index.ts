export type Language = "fr" | "en" | "so" | "ar";
export const LANGUAGES: Language[] = ["fr", "en", "so", "ar"];

/** Profil d'accessibilité choisi à la première configuration. */
export type Profile = "blind" | "deaf" | "both" | "other";
export const PROFILES: Profile[] = ["blind", "deaf", "both", "other"];

export interface Service {
  id: string;
  name: string;
  category: string;
  description: string | null;
  phone: string | null;
  address: string | null;
  opening_hours: string | null;
  verified: boolean;
  verified_at: string | null;
  source: string | null;
}

/** Fiche envoyée à l'application : seulement les champs utiles et vérifiés. */
export interface PublicService {
  id: string;
  name: string;
  category: string;
  description: string | null;
  phone: string | null;
  address: string | null;
  opening_hours: string | null;
  verified_at: string | null;
}

// ---------------------------------------------------------------------------
// Messages « neutres » : indépendants du fournisseur d'IA (Claude, OpenAI, Gemini, Llama…).
// L'application Android garde cette liste et la renvoie à chaque tour (serveur sans état).
// ---------------------------------------------------------------------------
export interface TextBlock { type: "text"; text: string }
/** JPEG en base64 (sans préfixe data:). */
export interface ImageBlock { type: "image"; data: string }
export interface ToolUseBlock { type: "tool_use"; id: string; name: string; input: Record<string, unknown> }
export interface ToolResultBlock {
  type: "tool_result";
  tool_use_id: string;
  content: (TextBlock | ImageBlock)[];
  is_error?: boolean;
}
export type Block = TextBlock | ImageBlock | ToolUseBlock | ToolResultBlock;

export interface Message {
  role: "user" | "assistant";
  content: Block[];
}
