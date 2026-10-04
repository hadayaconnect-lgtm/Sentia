import { guard, handle, jsonResponse, toPublicService } from "@/lib/api";
import { listVerifiedServices, pickHumanServices } from "@/services/orientation.service";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

/** Annuaire vérifié, champs publics uniquement. ?human=1 : services pour « Parler à une personne ». */
export const GET = handle(async (request) => {
  await guard(request, { readOnly: true });
  const all = await listVerifiedServices();
  const human = new URL(request.url).searchParams.get("human") === "1";
  return jsonResponse({ services: (human ? pickHumanServices(all) : all).map(toPublicService) });
});
