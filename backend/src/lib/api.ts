import { env } from "./env";
import { clientIp, deviceHmac, isSameOrigin, isValidDeviceId, rateLimited, safeEqual } from "./security";
import { incrementUsage } from "./usage";
import { stripEmojis, stripMarkdown } from "./messages";
import { listVerifiedServices, resolveServiceMarkers } from "@/services/orientation.service";
import type { Language, PublicService, Service } from "@/types";

export class ApiError extends Error {
  constructor(
    public status: number,
    public code: string,
    message?: string
  ) {
    super(message ?? code);
  }
}

export function jsonResponse(body: unknown, status = 200): Response {
  return Response.json(body, { status, headers: { "Cache-Control": "no-store" } });
}

export function errorResponse(error: unknown): Response {
  if (error instanceof ApiError) return jsonResponse({ error: error.code }, error.status);
  console.error("Erreur serveur :", (error as Error)?.message ?? "inconnue");
  return jsonResponse({ error: "server" }, 500);
}

/** Enveloppe commune : toute exception devient une réponse JSON propre, sans détail interne. */
export function handle(fn: (request: Request) => Promise<Response>) {
  return async (request: Request): Promise<Response> => {
    try {
      return await fn(request);
    } catch (error) {
      return errorResponse(error);
    }
  };
}

export const MAX_BODY_BYTES = 4_300_000;
const IP_LIMIT_PER_MINUTE = 40;

export interface GuardOptions {
  /** Compte la requête dans le quota quotidien de l'appareil. */
  quota?: boolean;
  /** GET public (pas de contrôle d'origine). */
  readOnly?: boolean;
}

export interface GuardResult {
  deviceId: string;
  subject: string;
}

/**
 * Contrôles communs : taille, origine, code d'accès, débit par IP, identifiant d'appareil, quota.
 * Les quotas sont « fail-open » si la base est indisponible (la limite par IP reste active).
 */
export async function guard(request: Request, options: GuardOptions = {}): Promise<GuardResult> {
  const length = Number(request.headers.get("content-length") ?? "0");
  if (length > MAX_BODY_BYTES) throw new ApiError(413, "too_large");

  // Web : même origine. Application Android : pas d'en-tête Origin, mais un en-tête client dédié.
  // Ce n'est PAS une preuve d'authenticité (un script peut l'imiter) : les limites par IP, les quotas par
  // installation, le plafond global et le code d'accès (APP_ACCESS_CODE) protègent vos clés.
  const native = env.nativeClientEnabled && request.headers.get("x-client") === "sentia-android" && !request.headers.get("origin");
  if (!options.readOnly && !native && !isSameOrigin(request)) throw new ApiError(403, "forbidden");

  const code = env.appAccessCode;
  if (code && !safeEqual(request.headers.get("x-access-code") ?? "", code)) {
    throw new ApiError(401, "access_code");
  }

  if (rateLimited(`ip:${clientIp(request)}`, IP_LIMIT_PER_MINUTE, 60_000)) {
    throw new ApiError(429, "rate");
  }

  const deviceId = request.headers.get("x-device-id");
  if (!isValidDeviceId(deviceId)) throw new ApiError(400, "device");
  const subject = deviceHmac(deviceId);

  if (options.quota) {
    const usage = await incrementUsage(subject);
    if (usage && (usage.user > env.userDailyLimit || usage.global > env.globalDailyLimit)) {
      throw new ApiError(429, "quota");
    }
  }
  return { deviceId, subject };
}

export async function readJson(request: Request): Promise<Record<string, unknown>> {
  try {
    const body = await request.json();
    if (body && typeof body === "object" && !Array.isArray(body)) return body as Record<string, unknown>;
  } catch {
    /* corps invalide */
  }
  throw new ApiError(400, "bad_json");
}

export function parseLang(value: unknown): Language {
  return value === "ar" || value === "so" || value === "en" ? value : "fr";
}

export function cleanText(value: unknown, max: number): string {
  if (typeof value !== "string") return "";
  return value.replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F]/g, "").trim().slice(0, max);
}

export function toPublicService(s: Service): PublicService {
  return {
    id: s.id,
    name: s.name,
    category: s.category,
    description: s.description,
    phone: s.phone,
    address: s.address,
    opening_hours: s.opening_hours,
    verified_at: s.verified_at,
  };
}

/** Annuaire vérifié ; si la base est indisponible, l'IA continue sans aucune coordonnée. */
export async function loadServices(): Promise<Service[]> {
  try {
    return await listVerifiedServices();
  } catch (error) {
    console.error("Annuaire indisponible :", (error as Error).message);
    return [];
  }
}

/** Texte final : marqueurs remplacés par les fiches vérifiées, sans markdown ni émojis. */
export function finalizeText(raw: string, services: Service[], lang: Language): string {
  return stripEmojis(stripMarkdown(resolveServiceMarkers(raw, services, lang)));
}
