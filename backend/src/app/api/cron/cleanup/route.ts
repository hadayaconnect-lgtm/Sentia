import { isAuthorizedCron } from "@/lib/security";
import { db } from "@/lib/supabase";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";
export const maxDuration = 60;

/** Nettoyage quotidien : anciens compteurs d'usage. */
export async function GET(request: Request): Promise<Response> {
  if (!isAuthorizedCron(request)) return new Response("Unauthorized", { status: 401 });
  const { error } = await db().rpc("cleanup_expired_data");
  if (error) console.error("Nettoyage de la base impossible", error.message);
  return Response.json({ ok: !error });
}
