# SENTIA — serveur

Serveur sans état (Next.js 15, routes `/api/*`) : il garde les **clés d'IA**, applique les **quotas**, tient
l'**annuaire vérifié** et fait le lien avec Claude. L'application Android ne contient aucune clé.

| Route | Rôle |
|---|---|
| `POST /api/agent` | un tour de conversation (texte + images + résultats d'outils). Renvoie `final` ou `tool_calls`. |
| `POST /api/transcribe` | audio → texte + langue détectée (Whisper) |
| `POST /api/speak` | texte → MP3 (ElevenLabs), repli quand le téléphone n'a pas la voix de la langue |
| `GET /api/services` | annuaire vérifié |
| `GET /api/config` | fournisseur d'IA, code requis ou non |

## Installer
1. Supabase : exécuter `supabase/migrations/001_init.sql`, puis ajouter vos services vérifiés (`verified = true`).
2. `cp .env.example .env` et remplir (au minimum `ANTHROPIC_API_KEY`, `OPENAI_API_KEY` pour Whisper, `SUPABASE_*`, `DEVICE_HMAC_SECRET`).
3. `npm install && npm test && npm run typecheck`, puis déployer (Vercel : importer le dossier `backend`, copier les variables).

## Changer de fournisseur d'IA
`AI_PROVIDER=anthropic | openai | gemini | llama` + les variables correspondantes (voir `.env.example`). Rien à changer dans l'application.
Les images et les outils passent par un format neutre ; seuls `src/services/ai/*.ts` parlent aux fournisseurs.

## Garde-fous (dans le code, pas seulement dans le prompt)
- Outils autorisés : liste fermée (`src/lib/tools.ts`), entrées nettoyées. **Aucun outil SOS / urgence.**
- L'IA n'écrit jamais de numéro : elle cite `[[SERVICE:id]]` et le serveur insère la fiche vérifiée.
- Jamais « c'est sûr » (traversée, médicament…), pas de distance précise, dates/montants à confirmer.
- Validation stricte de chaque message reçu (taille, rôles, 3 images max, 4 tours d'outils max).
- Quotas par installation (identifiant haché) et globaux, limite par IP. Rien n'est conservé : ni texte, ni photo, ni audio.

## Limite honnête sur l'authentification
L'en-tête `x-client: sentia-android` n'est **pas une preuve** que la requête vient de votre application : n'importe qui peut l'imiter.
Protections réelles : `APP_ACCESS_CODE` (code à saisir une fois dans Réglages), quotas, limites par IP, `NATIVE_CLIENT_ENABLED=false` pour couper.
Étape suivante recommandée avant une diffusion large : **Play Integrity** (non implémenté).
