# Datenschutz

## Kurz gesagt

Wer Wie Was funktioniert ohne Konto der App, ohne Tracking und ohne Werbung. Die Foto-Analyse läuft über den KI-Anbieter deiner Wahl mit deinem eigenen API-Schlüssel – Fotos werden nur versendet, wenn du vor jedem Foto ausdrücklich zustimmst.

## Was wo gespeichert wird

| Daten | Ort | Löschung |
| --- | --- | --- |
| Fotos (verkleinerte Kopie) | App-interner Speicher (filesDir/images) | Löschen der Entdeckung oder „Alle Entdeckungen löschen“ |
| Entdeckungen (Text, Labels, Links) | filesDir/discoveries.json | dito |
| API-Schlüssel des gewählten KI-Anbieters | Android-SharedPreferences (nur lokal) | Einstellungen → „Entfernen“ |

Deinstallieren der App entfernt alle Daten endgültig.

## Foto-Analyse (online)

Die Analyse läuft ausschließlich online über den KI-Anbieter, den du in den Einstellungen wählst (Mistral, OpenAI-kompatibler Dienst, OpenRouter, Google Gemini, Anthropic Claude oder xAI Grok):

- Vor jedem Foto stimmst du dem Versand ausdrücklich zu. Ohne deine Bestätigung wird kein Foto übertragen.
- Das Foto wird einmalig direkt über eine HTTPS-Verbindung an die API des gewählten Anbieters gesendet (z. B. `api.mistral.ai`, `api.openai.com`, `openrouter.ai`, `generativelanguage.googleapis.com`, `api.anthropic.com`, `api.x.ai`); es gibt keinen weiteren Server der App.
- Die Server der Anbieter unterliegen deren Datenschutzerklärungen.
- Der API-Schlüssel wird ausschließlich im Gerät gespeichert und direkt an den Anbieter übertragen.

## Berechtigungen

- **Internet:** für die Foto-Analyse, die kostenlosen Online-Stimmen (Edge-TTS) und das Öffnen von Links im Browser.
- **Kamera:** Fotos werden über die Systemkamera aufgenommen; die App selbst besitzt keinen direkten Kamerazugriff.
- **Schnelleinstellungen-Kachel:** reiner Start-Zugriff, keine Daten.

## Keine Datenweitergabe

Die App enthält keine Analyse-SDKs, keine Werbung und keine Crash-Reporting-Dienste von Drittanbietern. Der Absturz-Protokollordner (für „Fehlerbericht teilen“) bleibt ausschließlich auf dem Gerät.
