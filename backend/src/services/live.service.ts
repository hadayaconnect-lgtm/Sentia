import { buildAgentPrompt } from "@/lib/prompts";
import { CAMERA_PURPOSES } from "@/lib/tools";
import { env } from "@/lib/env";
import { ApiError } from "@/lib/api";
import type { Language, Profile } from "@/types";

/**
 * Mode conversation en direct (OpenAI Realtime, ou Gemini Live) : le téléphone se connecte DIRECTEMENT au fournisseur avec un
 * jeton temporaire créé ici. La vraie clé reste sur le serveur. Les consignes de sécurité sont les mêmes que pour le mode classique.
 */

export const LIVE_TOOLS = [
  {
    functionDeclarations: [
      {
        name: "capture_camera",
        description:
          "Prend UNE photo avec la caméra arrière du téléphone et la place dans la conversation. À utiliser seulement quand la personne demande de regarder, lire, identifier ou vérifier ce qui est devant elle. Une seule photo par demande.",
        parameters: {
          type: "OBJECT",
          properties: {
            purpose: {
              type: "STRING",
              enum: [...CAMERA_PURPOSES],
              description: "scene (environnement), document ou text (lire du texte), money (billet de banque), object ou color",
            },
          },
          required: ["purpose"],
        },
      },
      { name: "get_time", description: "Donne la date et l'heure locales du téléphone.", parameters: { type: "OBJECT", properties: {} } },
    ],
  },
];

/** Mêmes outils, au format OpenAI Realtime (« function » à plat, schéma JSON en minuscules). */
export const LIVE_TOOLS_OPENAI = [
  {
    type: "function",
    name: "capture_camera",
    description:
      "Prend UNE photo avec la caméra arrière du téléphone et la place dans la conversation. À utiliser seulement quand la personne demande de regarder, lire, identifier ou vérifier ce qui est devant elle. Une seule photo par demande.",
    parameters: {
      type: "object",
      properties: {
        purpose: {
          type: "string",
          enum: [...CAMERA_PURPOSES],
          description: "scene (environnement), document ou text (lire du texte), money (billet de banque), object ou color",
        },
      },
      required: ["purpose"],
    },
  },
  { type: "function", name: "get_time", description: "Donne la date et l'heure locales du téléphone.", parameters: { type: "object", properties: {} } },
];

const LIVE_ADDENDUM = [
  "Mode conversation en direct (tu parles à voix haute, en continu) :",
  "- Tu es le compagnon vocal d'une personne aveugle ou malvoyante : chaleureux, calme, bref. Une à trois phrases, sauf si la personne demande des détails.",
  "- Tu ne décris rien de toi-même, tu ne parles pas sans raison : tu réponds à ce qui est demandé.",
  "- Tu ne vois que ce que montre la photo que tu as prise (outil capture_camera). Tu n'as PAS de vidéo continue. Si la personne demande ce qu'il y a devant elle maintenant, ou si elle peut avancer, prends une NOUVELLE photo. Une seule photo par demande ; ne rappelle pas l'outil dans la même demande.",
  "- Avant de prendre la photo, dis « Je regarde. » Après réception, réponds directement.",
  "- Pour une question de suivi sur la photo déjà prise (« Qu'est-ce qui est écrit sur la bouteille ? »), réponds depuis cette photo sans en refaire une, tant que la scène n'a pas changé.",
  "- Si la photo est noire, floue ou ne montre pas ce qui est demandé, dis-le et demande de bouger le téléphone, puis de redemander. Ne devine jamais.",
  "- Tu n'as pas d'annuaire de services dans ce mode : n'écris aucun marqueur [[SERVICE:...]] ni numéro de téléphone ; dis « Je n'ai pas cette information. »",
  "- Si la personne dit « Stop » ou « Arrête », tu t'arrêtes de parler tout de suite.",
  "- Si tu entends une voix qui n'est manifestement pas celle de la personne (télévision, passants), ne réponds pas.",
].join("\n");

export function buildLiveInstruction(lang: Language | null, profile: Profile, localTime?: string): string {
  return buildAgentPrompt({ lang, profile, services: [], toolsAvailable: true, localTime }) + "\n\n" + LIVE_ADDENDUM;
}

export interface LiveSessionInfo {
  /** « openai » ou « gemini » : le téléphone en déduit le langage à parler avec le serveur de voix. */
  provider: "openai" | "gemini";
  token: string;
  urls: string[];
  model: string;
  voice: string;
  instruction: string;
  tools: unknown[];
  /** Durée pendant laquelle le jeton permet d'ouvrir une session. */
  startWithinSec: number;
}

export interface LiveTokenOptions {
  lang: Language | null;
  profile: Profile;
  localTime?: string;
  provider?: "openai" | "gemini";
  fetchImpl?: typeof fetch;
  now?: () => number;
}

const START_WITHIN_SEC = 90;
const TOKEN_LIFETIME_SEC = 30 * 60;

function cleanDetail(text: string): string {
  return text.replace(/[A-Za-z0-9_\-.]{28,}/g, "…").replace(/\s+/g, " ").slice(0, 160);
}

/** Crée le jeton temporaire de la conversation en direct chez le fournisseur choisi. */
export async function createLiveSession(o: LiveTokenOptions): Promise<LiveSessionInfo> {
  return (o.provider ?? env.liveProvider) === "gemini" ? createGeminiSession(o) : createOpenAiSession(o);
}

/** OpenAI Realtime : clé temporaire « ek_… » (valable quelques minutes pour OUVRIR la session), la vraie clé reste ici. */
async function createOpenAiSession(o: LiveTokenOptions): Promise<LiveSessionInfo> {
  const f = o.fetchImpl ?? fetch;
  const model = env.openaiLiveModel;
  const voice = env.openaiLiveVoice;
  const res = await f("https://api.openai.com/v1/realtime/client_secrets", {
    method: "POST",
    headers: { "content-type": "application/json", Authorization: `Bearer ${env.openaiLiveApiKey}` },
    body: JSON.stringify({
      expires_after: { anchor: "created_at", seconds: START_WITHIN_SEC * 2 },
      session: { type: "realtime", model, audio: { output: { voice } } },
    }),
    signal: AbortSignal.timeout(15_000),
  });
  if (!res.ok) {
    const detail = cleanDetail(await res.text().catch(() => ""));
    throw new ApiError(502, "live_token", `Jeton OpenAI refusé (HTTP ${res.status}) ${detail}`);
  }
  const data = (await res.json().catch(() => null)) as { value?: unknown } | null;
  if (!data || typeof data.value !== "string" || !data.value) throw new ApiError(502, "live_token", "Jeton OpenAI illisible");
  return {
    provider: "openai",
    token: data.value,
    urls: [`${env.openaiLiveUrl}?model=${encodeURIComponent(model)}`],
    model,
    voice,
    instruction: buildLiveInstruction(o.lang, o.profile, o.localTime),
    tools: LIVE_TOOLS_OPENAI,
    startWithinSec: START_WITHIN_SEC,
  };
}

/** Gemini Live : jeton à usage unique, limité à CE modèle et à la réponse audio. */
async function createGeminiSession(o: LiveTokenOptions): Promise<LiveSessionInfo> {
  const f = o.fetchImpl ?? fetch;
  const now = (o.now ?? Date.now)();
  const model = env.liveModel;
  const res = await f("https://generativelanguage.googleapis.com/v1beta/auth_tokens", {
    method: "POST",
    headers: { "content-type": "application/json", "x-goog-api-key": env.liveApiKey },
    body: JSON.stringify({
      uses: 1,
      expireTime: new Date(now + TOKEN_LIFETIME_SEC * 1000).toISOString(),
      newSessionExpireTime: new Date(now + START_WITHIN_SEC * 1000).toISOString(),
      liveConnectConstraints: {
        model: `models/${model}`,
        config: { sessionResumption: {}, responseModalities: ["AUDIO"] },
      },
    }),
    signal: AbortSignal.timeout(15_000),
  });
  if (!res.ok) {
    const detail = cleanDetail(await res.text().catch(() => ""));
    throw new ApiError(502, "live_token", `Jeton Live refusé (HTTP ${res.status}) ${detail}`);
  }
  const data = (await res.json().catch(() => null)) as { name?: unknown } | null;
  if (!data || typeof data.name !== "string" || !data.name) throw new ApiError(502, "live_token", "Jeton Live illisible");
  return {
    provider: "gemini",
    token: data.name,
    urls: env.liveWsUrls,
    model: `models/${model}`,
    voice: "",
    instruction: buildLiveInstruction(o.lang, o.profile, o.localTime),
    tools: LIVE_TOOLS,
    startWithinSec: START_WITHIN_SEC,
  };
}
