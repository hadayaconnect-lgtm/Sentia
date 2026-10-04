# SENTIA — « Secouez, parlez, SENTIA vous aide »

Assistant IA accessible pour personnes aveugles, malvoyantes, sourdes ou malentendantes (Djibouti, concours ANPH 2026).
Application **Android native (Kotlin)** + **serveur sécurisé** (`backend/`) qui parle à **Claude**.

```
sentia/
  android/   Application Android (Kotlin, Android Studio) + core-test/ (tests de la logique pure)
  backend/   Serveur Next.js : clés d'IA, quotas, annuaire, Whisper, voix
```

## Ce que fait l'application (MVP)
- **Réveil** : secousse volontaire du téléphone (détection 100 % locale) **ou** les deux touches de volume tenues 1 s.
  Le téléphone vibre une fois = « SENTIA est réveillé ».
- **Conversation** par la voix ou par écrit, en **français, anglais, somali, arabe** (langue forcée ou automatique).
- **Caméra** : « décris ce que tu vois », « lis ce papier » (l'IA demande la photo, l'application la prend après permission).
- **Position** : « où suis-je ? » (adresse approximative), **heure**, **navigation** (confirmation obligatoire avant d'ouvrir la carte).
- **Aveugles** : réponses parlées, écoute automatique au réveil, compatible TalkBack. **Sourds** : texte large, vibrations (1 courte = réveillé, 2 = info, 1 longue = attention, 3 = événement détecté).
- **Sons** (expérimental) : sonnette, klaxon, alarme, sonnerie, pleurs de bébé — formulé avec prudence (« un son ressemblant à… »).
- **Aucune fonction SOS / urgence** dans ce MVP, volontairement.

## Installer (≈ 1 heure)
1. **Serveur** : voir `backend/README.md`. Notez l'adresse HTTPS déployée.
2. **Android Studio** (Koala ou plus récent) → *Open* → dossier `android/`.
   Dans `android/gradle.properties`, remplacez `sentia.serverUrl` par l'adresse de votre serveur. *Sync* → *Run* sur un vrai téléphone (le simulateur n'a pas de secousse ni de touches de volume utiles).
3. Premier lancement : choisir le profil, accepter le message de confidentialité, autoriser micro/notifications.
4. **Réglages** → *Autoriser SENTIA en arrière-plan* (batterie) et *Activer le service des touches de volume* (Accessibilité → SENTIA).
5. **Sons (facultatif)** : télécharger le modèle YAMNet `.tflite` (TensorFlow Hub / Kaggle, « yamnet classification tflite ») et le copier sous `android/app/src/main/assets/yamnet.tflite`, puis recompiler.

> ⚠️ **Le code Android n'a pas pu être compilé là où il a été écrit** (pas d'accès au SDK Android). Il a été relu ligne à ligne, et la logique pure (secousse, volume, sons, vibrations) est testée sur ordinateur, mais attendez-vous à corriger quelques erreurs de compilation au premier *Sync/Build*. Commencez par ça.

## Tests de la logique sans Android
`bash android/core-test/run.sh` — signaux simulés : 200 secousses variées (≥ 95 % détectées), 40 min de marche / course / transport cahoteux (0 fausse détection en sensibilité moyenne), chute, immobilité, anti-répétition, geste des deux touches de volume, filtre des sons.
Côté serveur : `cd backend && npm test`.

## Protocole de test sur téléphone (à faire AVANT d'affirmer que ça marche)
Pour chaque modèle (au moins 2 marques, ex. Samsung + Tecno/Infinix/Xiaomi) et chaque réglage de sensibilité :
| Test | Attendu | Noter |
|---|---|---|
| Écran allumé, app ouverte, secousse | vibration 1×, écoute | oui/non |
| App en arrière-plan, écran allumé | l'écran SENTIA s'ouvre | oui / seulement la notification / non |
| Écran éteint | réveil ? | oui / non |
| Téléphone verrouillé | l'écran s'ouvre par-dessus le verrouillage ? caméra utilisable ? | |
| Après 1 h, 8 h, 24 h | le service tourne encore (notification « SENTIA en veille ») ? | |
| Marche 10 min, bus, moto, poche | 0 réveil involontaire | nombre |
| Touches de volume ×1 s (écran allumé / éteint / verrouillé) | réveil | |
| Batterie sur 24 h | pourcentage consommé par SENTIA | |
Faire tester par de **vraies personnes aveugles et sourdes** : c'est la seule validation qui compte pour le jury.

## Limites honnêtes
- **Arrière-plan** : Android et surtout Xiaomi/Tecno/Infinix/Huawei/Samsung peuvent arrêter le service. Le réveil écran éteint / verrouillé et l'ouverture de l'écran depuis l'arrière-plan **doivent être validés** modèle par modèle ; repli prévu : notification plein écran.
- **Touches de volume** : la première touche appuyée change le volume d'un cran avant que la seconde ne soit détectée. Le service d'accessibilité n'est pas garanti écran éteint selon les marques. Google Play examine l'usage des services d'accessibilité : pour le concours/un pilote local ce n'est pas bloquant, pour le Play Store il faudra le justifier.
- **Batterie** : veille permanente sur l'accéléromètre = consommation réelle (à mesurer).
- **Claude ne prend pas l'audio** : la voix passe par Whisper (OpenAI) puis Claude ; ce sont deux fournisseurs, à dire dans la politique de confidentialité.
- **Somali** : transcription, voix du téléphone et qualité de l'IA plus faibles. Les traductions somali/arabe de l'interface **doivent être relues par un locuteur natif**.
- **Distances / sécurité** : l'IA ne donne pas de distance précise et ne dit jamais « c'est sûr de traverser ». Une photo non cadrée peut être mal décrite.
- **Sons** : modèle local expérimental, faux positifs et oublis possibles ; ne jamais s'y fier pour la sécurité.
- **Authentification du client** : voir `backend/README.md` (code d'accès + quotas ; Play Integrity à venir).
- **Pas d'iOS** dans ce MVP.

## Kotlin natif, React Native ou Expo ?
Kotlin natif a été retenu : le cœur du produit (service en arrière-plan toujours actif, capteurs, service d'accessibilité, notification plein écran, TalkBack) est du code Android que React Native / Expo ne gèrent pas sans modules natifs écrits… en Kotlin. Contrepartie : Android uniquement.

## Après le MVP
Traduction en direct, autres fournisseurs d'IA (déjà prévu côté serveur), Play Integrity, modèle de sons spécifique (sonnette djiboutienne), mode hors ligne partiel, iOS.
