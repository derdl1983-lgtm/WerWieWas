# Datenschutz

## Kurz gesagt

Wer Wie Was funktioniert ohne Konto der App, ohne Tracking und ohne Werbung. Die Foto-Analyse läuft über den KI-Anbieter deiner Wahl mit deinem eigenen API-Schlüssel – Fotos werden standardmäßig nur versendet, wenn du vor jedem Foto ausdrücklich zustimmst; optional kannst du die „Direktanalyse ohne Rückfrage“ aktivieren, sodass die Analyse direkt nach dem Foto startet.

## Was wo gespeichert wird

| Daten | Ort | Löschung |
| --- | --- | --- |
| Fotos (verkleinerte Kopie) | App-interner Speicher (filesDir/images) | Löschen der Entdeckung oder „Alle Entdeckungen löschen“ |
| Entdeckungen (Text, Labels, Links) | filesDir/discoveries.json | dito |
| API-Schlüssel des gewählten KI-Anbieters | Android-SharedPreferences (nur lokal) | Einstellungen → „Entfernen“ |
| Temporäre Sprach- und Bilddateien | App-Cache (cacheDir, „www_…“) | automatisch nach 24 Stunden |

Deinstallieren der App entfernt alle Daten endgültig.

## Foto-Analyse (online)

Die Analyse läuft ausschließlich online über den KI-Anbieter, den du in den Einstellungen wählst (Mistral, OpenAI-kompatibler Dienst, OpenRouter, Google Gemini, Anthropic Claude oder xAI Grok):

- Standardmäßig stimmst du dem Versand vor jedem einzelnen Foto ausdrücklich zu. Ohne deine Bestätigung wird kein Foto übertragen.
- Optional kannst du in den Einstellungen die **„Direktanalyse ohne Rückfrage“** aktivieren: Dann wird jedes neu aufgenommene oder gewählte Foto automatisch sofort an den gewählten KI-Anbieter übertragen, ohne separate Rückfrage. Die Einstellung kannst du jederzeit wieder deaktivieren.
- Das Foto wird direkt über eine HTTPS-Verbindung an die API des gewählten Anbieters gesendet (z. B. `api.mistral.ai`, `api.openai.com`, `openrouter.ai`, `generativelanguage.googleapis.com`, `api.anthropic.com`, `api.x.ai`); es gibt keinen weiteren Server der App.
- Die Server der Anbieter unterliegen deren Datenschutzerklärungen.
- Der API-Schlüssel wird ausschließlich im Gerät gespeichert und direkt an den Anbieter übertragen.

## Weiterführende Links und Themenbilder (online)

Ohne deine Fotos werden bei bestimmten Aktionen kurze Suchanfragen im Klartext an externe Dienste gesendet:

- Beim Abschluss einer Analyse fragt die App die **deutsche Wikipedia-API** (`de.wikipedia.org`) nach dem passenden Artikel, um den Weiterbildungs-Link direkt auf den Artikel zeigen zu lassen.
- Beim Öffnen des Erklärvideos werden Suchbegriffe aus dem Analyseergebnis (Kernthema und Schlüsselwörter, nicht das Foto) an **Wikimedia Commons** (`commons.wikimedia.org`) und ersatzweise an **Openverse** (`api.openverse.org`) gesendet, um frei lizenzierte Themenbilder zu finden. Gefundene Bilder werden mit Quellen- und Lizenzangabe angezeigt und landen temporär im App-Cache (automatische Löschung nach 24 Stunden).

Bei beiden Diensten handelt es sich um offene APIs ohne API-Schlüssel und ohne Konto.

## Berechtigungen

- **Internet:** für die Foto-Analyse, die kostenlosen Online-Stimmen (Edge-TTS), die Themenbildersuche (Wikimedia Commons/Openverse), die Wikipedia-Artikelsuche und das Öffnen von Links im Browser.
- **Kamera:** Fotos werden über die Systemkamera aufgenommen; die App selbst besitzt keinen direkten Kamerazugriff.
- **Schnelleinstellungen-Kachel:** reiner Start-Zugriff, keine Daten.

## Keine Datenweitergabe

Die App enthält keine Analyse-SDKs, keine Werbung und keine Crash-Reporting-Dienste von Drittanbietern. Der Absturz-Protokollordner (für „Fehlerbericht teilen“) bleibt ausschließlich auf dem Gerät.


