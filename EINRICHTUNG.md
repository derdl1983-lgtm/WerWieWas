# Einrichtung (nur mit dem Handy)

## 1. APK herunterladen und installieren

1. Öffne `https://github.com/derdl1983-lgtm/WerWieWas` im Browser deines Handys.
2. Tippe auf den Reiter **Releases**.
3. Öffne den neuesten Release **„Automatischer APK-Build“** und lade **app-debug.apk** herunter.
4. Tippe auf die Download-Benachrichtigung.
5. Erlaube **„Installation aus unbekannten Quellen“** für deinen Browser (einmalig).
6. Tippe auf **Installieren**. Fertig.

Ist unter Releases keine APK zu finden, schaue in den Reiter **Actions**: Läuft der Workflow noch, dauert es ein paar Minuten. Ist er rot, liegt im Repository eine Datei `last-build-error.log` mit dem Fehlerprotokoll – warte dann auf den nächsten Build-Versuch.

## 2. KI-Anbieter einrichten (nötig für die Foto-Analyse)

Die Foto-Analyse läuft ausschließlich online mit deinem eigenen API-Schlüssel. Ohne Schlüssel kann keine Analyse gestartet werden – die App weist dich dann direkt auf die Einstellungen hin.

1. Bei einem Anbieter deiner Wahl ein Konto anlegen und einen **API-Schlüssel** erstellen:
   - **Mistral:** `console.mistral.ai`
   - **OpenAI (oder jeder OpenAI-kompatible Dienst):** `platform.openai.com/api-keys`
   - **OpenRouter** (viele Modelle mit einem Schlüssel): `openrouter.ai/settings/keys`
   - **Google Gemini:** `aistudio.google.com/apikey`
   - **Anthropic Claude:** `console.anthropic.com`
   - **xAI Grok:** `console.x.ai`
2. In der App: **Einstellungen → KI-Anbieter** auswählen → **API-Schlüssel** eintragen → **Speichern**. Der Schlüssel bleibt nur auf deinem Gerät.
3. Optional: ein anderes **Modell** wählen oder frei eintragen – unter dem Feld findest du die bekannten Bildverständnis-Modelle des Anbieters zum Antippen.
4. Hinweis zu **OpenAI-kompatibel:** Für OpenAI selbst die Basis-URL LEER lassen. Nur bei einem anderen Dienst (OpenRouter braucht keine, z. B. eigener oder lokaler Server) die API-Basis-URL eintragen – meist endet sie auf `/v1`. Das ist nicht die Website-Adresse.

## 3. Erste Nutzung

1. App **„Wer Wie Was“** öffnen.
2. Beim ersten Foto die **Kamera-Berechtigung** erlauben.
3. Objekt fotografieren → vor jedem Foto stimmst du dem einmaligen Versand des Fotos an den KI-Anbieter zu → Erklärung mit **Was / Wie / Wofür / Fakten / Sicherheit**.
4. Über **Erklärvideo ansehen** lässt sich die Erklärung vorlesen.
5. **Verlauf** zeigt alle Entdeckungen; Sterne markieren Favoriten.

## 4. Kamera-Kachel für den Schnellzugriff

1. Benachrichtigungsleiste **zweimal nach unten** ziehen (Schnelleinstellungen öffnen).
2. Auf den **Bearbeiten-Stift** tippen.
3. Kachel **„Wer Wie Was“** suchen und in die obere (aktive) Reihe ziehen.
4. Ab sofort: Kachel antippen → die App öffnet direkt die Kamera.

## 5. Probleme lösen

- **Analyse schlägt fehl:** Fehlermeldung lesen – meist ist der API-Schlüssel ungültig (HTTP 401), das Modell falsch geschrieben (404) oder das Anfragelimit erreicht (429). Einstellungen → Daten → **„Fehlerbericht teilen“** hilft bei der Fehlersuche.
- **Keine Sprachausgabe im Erklärvideo:** Wähle in den Einstellungen eine kostenlose Online-Stimme (Edge-TTS) oder prüfe unter Android → Einstellungen → Bedienungshilfen → Text-in-Sprache-Ausgabe, ob eine deutsche Stimme installiert ist.
- **Objekt wird nicht erkannt:** Mehr Licht, näher ans Objekt, Hintergrund ruhiger halten.
