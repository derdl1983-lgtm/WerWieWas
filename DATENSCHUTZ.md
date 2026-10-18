# Datenschutz

## Kurz gesagt

Wer Wie Was funktioniert ohne Konto, ohne Tracking und ohne Werbung. Fotos und Analysen bleiben standardmäßig vollständig auf dem Gerät.

## Was wo gespeichert wird

| Daten | Ort | Löschung |
| --- | --- | --- |
| Fotos (verkleinerte Kopie) | App-interner Speicher (filesDir/images) | Löschen der Entdeckung oder „Alle Entdeckungen löschen“ |
| Entdeckungen (Text, Labels, Links) | filesDir/discoveries.json | dito |
| Optionaler Mistral-API-Schlüssel | Android-SharedPreferences (nur lokal) | Einstellungen → „Entfernen“ |

Deinstallieren der App entfernt alle Daten endgültig.

## Bilderkennung

Die Erkennung nutzt **Google ML Kit Image Labeling** mit einem gebündelten Modell, das lokal auf dem Gerät läuft. Das Foto verlässt dein Gerät dabei **nie**.

## Optionale Online-Analyse

Nur wenn du einen eigenen Mistral-API-Schlüssel hinterlegt hast **und** vor dem jeweiligen Foto ausdrücklich zustimmst:

- Das Foto wird einmalig an `api.mistral.ai` gesendet, um eine genauere Analyse zu erhalten.
- Der Versand erfolgt direkt über eine HTTPS-Verbindung; es gibt keinen weiteren Server der App.
- Die Server von Mistral unterliegen deren Datenschutzerklärung (mistral.ai).

Ohne deine Zustimmung wird kein einziges Foto übertragen.

## Berechtigungen

- **Internet:** nur für die optionale Online-Analyse und das Öffnen von Links im Browser.
- **Kamera:** Fotos werden über die Systemkamera aufgenommen; die App selbst besitzt keinen direkten Kamerazugriff.
- **Schnelleinstellungen-Kachel:** reiner Start-Zugriff, keine Daten.

## Keine Datenweitergabe

Die App enthält keine Analyse-SDKs, keine Werbung und keine Crash-Reporting-Dienste von Drittanbietern.
