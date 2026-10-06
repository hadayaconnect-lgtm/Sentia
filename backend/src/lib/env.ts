/**
 * Accès centralisé aux variables d'environnement.
 * Les valeurs ne sont lues qu'à l'usage (lazy) pour ne pas casser le build.
 * Aucune valeur n'est jamais loggée.
 */

function required(name: string): string {
  const value = process.env[name];
  if (!value) throw new Error(`Variable d'environnement manquante : ${name}`);
  return value;
}

function optional(name: string, fallback: string): string {
  return process.env[name] || fallback;
}

function int(name: string, fallback: number): number {
  const raw = process.env[name];
  if (!raw) return fallback;
  const parsed = Number.parseInt(raw, 10);
  return Number.isFinite(parsed) ? parsed : fallback;
}

export type AiProvider = "anthropic" | "openai" | "gemini" | "llama";

export const env = {
  /** "anthropic" (Claude, défaut), "openai", "gemini" ou "llama". Un seul réglage pour changer de cerveau. */
  get aiProvider(): AiProvider {
    const v = process.env.AI_PROVIDER;
    return v === "openai" || v === "gemini" || v === "llama" ? v : "anthropic";
  },
  get anthropicApiKey() { return required("ANTHROPIC_API_KEY"); },
  get claudeModel() { return optional("CLAUDE_MODEL", "claude-sonnet-5-5"); },

  /** Réglages du fournisseur « compatible OpenAI » actif (openai, gemini ou llama). */
  get compat(): { label: string; baseUrl: string; apiKey: string; model: string } {
    const clean = (u: string) => u.replace(/\/+$/, "");
    switch (env.aiProvider) {
      case "gemini":
        return {
          label: "Gemini (Google)",
          baseUrl: clean(optional("GEMINI_BASE_URL", "https://generativelanguage.googleapis.com/v1beta/openai")),
          apiKey: required("GEMINI_API_KEY"),
          model: optional("GEMINI_MODEL", "gemini-3.1-flash-lite"),
        };
      case "openai":
        return {
          label: "OpenAI",
          baseUrl: clean(optional("OPENAI_CHAT_BASE_URL", "https://api.openai.com/v1")),
          apiKey: required("OPENAI_API_KEY"),
          model: optional("OPENAI_CHAT_MODEL", "gpt-4.1"),
        };
      default:
        return {
          label: "Llama (Meta)",
          baseUrl: clean(optional("LLAMA_BASE_URL", "https://api.llama.com/compat/v1")),
          apiKey: required("LLAMA_API_KEY"),
          model: optional("LLAMA_MODEL", "Llama-4-Maverick-17B-128E-Instruct-FP8"),
        };
    }
  },

  get openaiApiKey() { return required("OPENAI_API_KEY"); },
  get whisperModel() { return optional("OPENAI_TRANSCRIBE_MODEL", "whisper-1"); },
  /** Force la langue de Whisper (ex. "fr"). Vide = détection automatique. */
  get whisperLanguage() { return process.env.WHISPER_LANGUAGE || ""; },

  get elevenlabsApiKey() { return required("ELEVENLABS_API_KEY"); },
  get elevenlabsVoiceId() { return required("ELEVENLABS_VOICE_ID"); },
  get elevenlabsModelId() { return optional("ELEVENLABS_MODEL_ID", "eleven_multilingual_v2"); },
  get elevenlabsBaseUrl() { return optional("ELEVENLABS_BASE_URL", "https://api.elevenlabs.io").replace(/\/+$/, ""); },

  get supabaseUrl() { return required("SUPABASE_URL"); },
  get supabaseServiceRoleKey() { return required("SUPABASE_SERVICE_ROLE_KEY"); },

  /** Clé secrète pour rendre anonymes les identifiants d'appareil (openssl rand -hex 32). */
  get deviceHmacSecret() { return required("DEVICE_HMAC_SECRET"); },
  /** Code d'accès facultatif (recommandé pendant le pilote). Vide = accès libre. */
  get appAccessCode() { return process.env.APP_ACCESS_CODE || ""; },
  get cronSecret() { return required("CRON_SECRET"); },

  get userDailyLimit() { return int("USER_DAILY_LIMIT", 150); },
  get globalDailyLimit() { return int("GLOBAL_DAILY_LIMIT", 3000); },

  /** Accepte l'application Android (sans en-tête Origin). Mettre "false" pour n'accepter que le web. */
  get nativeClientEnabled() { return process.env.NATIVE_CLIENT_ENABLED !== "false"; },
};
