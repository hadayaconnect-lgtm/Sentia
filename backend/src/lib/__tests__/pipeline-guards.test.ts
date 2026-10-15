import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { buildAgentPrompt } from "../prompts";

// Garde-fous : vérifient dans le CODE Android que le pipeline est RÉVEIL → 1 PHOTO → ANALYSE → VOIX
// (pas de caméra continue, pas de 10 s global, pas de 2e photo, pas d'image invalide envoyée).
const dir = join(process.cwd(), "..", "android/app/src/main/java/dj/sentia");
const src = (f: string) => readFileSync(join(dir, f), "utf8");

describe("pipeline photo unique (code Android)", () => {
  const engine = src("AssistantEngine.kt");
  const camera = src("CameraCapture.kt");
  const activity = src("AssistantActivity.kt");
  const tools = src("ToolExecutor.kt");

  it("plus de délai global de 10 s", () => {
    for (const code of [engine, activity, camera]) {
      assert.doesNotMatch(code, /HOLD_AT_MS|ANALYSIS_MAX_MS|ANALYSIS_HARD_MS|10_000L/);
    }
  });

  it("la caméra est libérée après la capture (release → unbindAll, dans un finally côté core)", () => {
    assert.match(camera, /release\s*=\s*\{\s*provider\.unbindAll\(\)/);
    assert.match(src("core/PhotoPipeline.kt"), /finally\s*\{\s*release\(\)/);
  });

  it("une photo invalide n'est jamais envoyée : échec → Kind.IMAGE, pas de photo", () => {
    assert.match(camera, /throw AgentException\(AgentException\.Kind\.IMAGE/);
    assert.match(engine, /\?:\s*throw AgentException\(AgentException\.Kind\.IMAGE/);
  });

  it("une seule photo par demande : budget + outil refusé si déjà prise", () => {
    assert.match(engine, /PhotoBudget\(\)\.also \{ it\.tryTake\(\) \}/);
    assert.match(engine, /if \(!photos\.tryTake\(\)\)/);
  });

  it("une nouvelle analyse oublie les anciennes photos", () => {
    assert.match(engine, /conversation\.dropImages\(\)/);
  });

  it("aucune boucle de frames, aucun aperçu, un seul point de capture", () => {
    for (const f of ["CameraCapture.kt", "AssistantEngine.kt", "AssistantActivity.kt", "ToolExecutor.kt", "ShakeService.kt"]) {
      assert.doesNotMatch(src(f), /ImageAnalysis|setAnalyzer|PreviewView|setSurfaceProvider/);
    }
    assert.doesNotMatch(tools, /bindToLifecycle/);
    assert.equal((camera.match(/bindToLifecycle/g) ?? []).length, 1);
    assert.equal((camera.match(/takePicture\(/g) ?? []).length, 1);
  });

  it("les étapes sont chronométrées", () => {
    for (const k of ["cam_open", "shot", "validate", "compress"]) assert.ok(camera.includes(`Perf.put("${k}"`), k);
    assert.ok(src("Speaker.kt").includes("Perf.ttsStarted"));
  });
});

describe("anti-hallucination (prompt serveur)", () => {
  const p = buildAgentPrompt({ lang: "fr", profile: "blind", services: [], toolsAvailable: true });
  it("phrases exigées et cas limites", () => {
    assert.ok(p.includes("Je ne peux pas le confirmer."));
    assert.ok(p.includes("Je ne vois pas clairement de billet."));
    assert.match(p, /mur vide ou table vide/);
    assert.match(p, /hors du champ/);
    assert.match(p, /partie visible/);
  });
  it("une seule photo par demande", () => {
    assert.match(p, /Une seule photo par demande/);
  });
});

describe("conversation en direct (code Android)", () => {
  const session = src("LiveSession.kt");
  const audio = src("LiveAudio.kt");
  const proto = src("core/LiveProtocol.kt");
  const activity = src("AssistantActivity.kt");

  it("jamais de flux d'images : un seul envoi de photo, déclenché par l'outil, une photo par demande", () => {
    assert.equal((session.match(/LiveProtocol\.photo\(/g) ?? []).length, 1);
    assert.match(session, /if \(!gate\.tryTake\(\)\)/);
    assert.doesNotMatch(audio, /ImageAnalysis|CameraX|ImageCapture/);
  });

  it("aucune clé secrète dans l'application", () => {
    for (const f of ["LiveSession.kt", "AgentClient.kt", "core/LiveProtocol.kt", "Settings.kt"]) {
      assert.doesNotMatch(src(f), /AIza[0-9A-Za-z_-]{20,}|GEMINI_API_KEY|x-goog-api-key/);
    }
    assert.match(src("AgentClient.kt"), /\/api\/live-token/);
  });

  it("la photo d'un échec n'est jamais envoyée : l'IA reçoit une erreur et dit le message exigé", () => {
    assert.match(session, /catch \(e: AgentException\)[\s\S]{0,200}Je n'arrive pas à obtenir une image claire/);
  });

  it("micro coupé pendant la parole sans annulation d'écho ; veille ; reconnexion limitée", () => {
    assert.match(session, /hasEchoCancel == false && player\.speaking/);
    assert.match(session, /IDLE_LIMIT_MS/);
    assert.match(proto, /MAX_ATTEMPTS = 3/);
  });

  it("repli automatique sur le mode classique et mode direct désactivable", () => {
    assert.match(activity, /catch \(e: AgentException\)[\s\S]{0,600}conversation\(/);
    assert.match(src("Settings.kt"), /live_mode/);
  });

  it("le mode direct est coupé quand l'application quitte l'écran (batterie, confidentialité)", () => {
    assert.match(activity, /override fun onStop\(\)[\s\S]{0,120}job\?\.cancel\(\)/);
  });
});
