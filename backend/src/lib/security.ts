import { createHash, createHmac, timingSafeEqual } from "node:crypto";
import { env } from "./env";

/** Identifiant d'appareil : un UUID généré par l'application, jamais un numéro de téléphone. */
export function isValidDeviceId(id: string | null | undefined): id is string {
  return typeof id === "string" && /^[A-Za-z0-9-]{16,64}$/.test(id);
}

/**
 * Identifiant anonymisé pour les quotas : HMAC avec clé secrète.
 * La base de données ne contient donc jamais l'identifiant réel de l'appareil.
 */
export function deviceHmac(deviceId: string): string {
  return createHmac("sha256", env.deviceHmacSecret).update(deviceId).digest("hex");
}

/** Comparaison à temps constant (les longueurs ne fuient pas : on compare des empreintes). */
export function safeEqual(a: string, b: string): boolean {
  const ha = createHash("sha256").update(a).digest();
  const hb = createHash("sha256").update(b).digest();
  return timingSafeEqual(ha, hb);
}

/** Vérifie « Authorization: Bearer <CRON_SECRET> » (Vercel Cron l'envoie automatiquement). */
export function isAuthorizedCron(request: Request): boolean {
  return safeEqual(request.headers.get("authorization") ?? "", `Bearer ${env.cronSecret}`);
}

export function clientIp(request: Request): string {
  const forwarded = (request.headers.get("x-forwarded-for") ?? "").split(",")[0].trim();
  return forwarded || request.headers.get("x-real-ip") || "unknown";
}

/**
 * Même origine uniquement : une requête POST venant du site d'un tiers est refusée,
 * pour qu'il ne puisse pas utiliser vos clés depuis le navigateur d'un visiteur.
 */
export function isSameOrigin(request: Request): boolean {
  const origin = request.headers.get("origin");
  if (!origin) return false;
  try {
    return new URL(origin).host === request.headers.get("host");
  } catch {
    return false;
  }
}

// Limitation de débit « au mieux » : la mémoire est propre à chaque instance serverless.
// Les quotas par appareil et globaux (base de données) restent la limite de référence.
const hits = new Map<string, number[]>();

export function rateLimited(key: string, limit: number, windowMs: number, now: number = Date.now()): boolean {
  const recent = (hits.get(key) ?? []).filter((t) => now - t < windowMs);
  recent.push(now);
  hits.set(key, recent);
  if (hits.size > 5000) {
    for (const [k, times] of hits) {
      if (!times.some((t) => now - t < windowMs)) hits.delete(k);
    }
  }
  return recent.length > limit;
}

export function resetRateLimits(): void {
  hits.clear();
}
