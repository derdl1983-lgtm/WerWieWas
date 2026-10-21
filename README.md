# Wer Wie Was

Foto machen → verstehen: **Wer Wie Was** analysiert Objekte mit einer KI deiner Wahl und erklärt auf Deutsch, was sie sind, wie sie funktionieren und wofür man sie benutzt.

## Funktionen

- **Foto oder Galerie** – Objekt ablichten oder ein vorhandenes Foto analysieren
- **Schnellfoto über mehrere Wege** – großer Kamera-Button auf der Startseite, App-Symbol gedrückt halten (Shortcuts „Foto machen“ und „Galerie“) und die Schnelleinstellungs-Kachel öffnen die App direkt im Fotografier- bzw. Bildauswahl-Modus
- **Direktanalyse ohne Rückfrage** – optional in den Einstellungen aktivierbar: Die Online-Analyse startet sofort nach dem Foto, ohne zusätzlichen Bestätigungsdialog
- **KI-Analyse online** – das Foto wird mit deinem eigenen API-Schlüssel von der KI analysiert; unterstützt werden Mistral, OpenAI-kompatible Dienste, OpenRouter, Google Gemini, Anthropic Claude und xAI Grok
- **Ehrliche Unsicherheit** – ist sich die KI nicht sicher, sagt die App das deutlich; bei kritischen Objekten (Pilze, Medikamente, Werkzeuge) wird nie eine Gewissheit behauptet
- **Erklärung in vier Fragen** – Was ist das? Wie funktioniert es? Wofür wird es verwendet? Plus Fakten und Sicherheitshinweise
- **Erklärvideo** – Foto, Textkarten und passende Online-Themenbilder (frei lizenziert, aus Wikimedia Commons/Openverse), vorgelesen über kostenlose Online-Stimmen oder die Android-Sprachausgabe
- **Verlauf & Favoriten** – alle Entdeckungen bleiben auf dem Gerät
- **Echte Links** – Wikipedia (per API direkt auf den passenden Artikel aufgelöst), YouTube-Suche, DuckDuckGo und Amazon-Suche; Suchanfragen werden aus dem Kernthema und Schlüsselwörtern der gesamten Analyse gebaut
- **Moderne Oberfläche** – Material-3-Design mit Farbverlauf, animiertem Kamera-Button und Karten-Layout für hellen und dunklen Modus
- **Datenschutz zuerst** – Foto-Versand standardmäßig nur nach ausdrücklicher Bestätigung pro Foto (optional Direktanalyse ohne Rückfrage), mit eigenem API-Schlüssel, ohne Zwischenserver

## APK beziehen (nur mit dem Handy)

Dieses Repository baut bei jedem Push automatisch eine Debug-APK:

1. Öffne `https://github.com/derdl1983-lgtm/WerWieWas` im Browser.
2. Reiter **Actions**: Der Workflow „Build Debug-APK“ läuft bei jedem Push automatisch.
3. Ist der Lauf grün: Reiter **Releases** → Release „Automatischer APK-Build“ → **app-debug.apk** herunterladen.
4. Datei öffnen → „Installation aus unbekannten Quellen“ für deinen Browser erlauben → installieren.

Schlägt der Build fehl, committet der Workflow automatisch ein `last-build-error.log` ins Repository – daran lässt sich der Fehler nachvollziehen.

Details zur Installation und Einrichtung: siehe `EINRICHTUNG.md`.

## Projektstruktur

```
app/
  src/main/
    java/de/derdl1983/weriewas/
      MainActivity.kt        Navigation, Kamera, Galerie, Shortcuts
      data/                  Entdeckungs-Modell, Link-Builder für Weiterbildung
      ml/                    OnlineAnalyzer: KI-Analyse (Mistral, OpenAI-kompatibel,
                             OpenRouter, Gemini, Claude, Grok)
      service/               Kamera-Kachel für die Schnelleinstellungen
      ui/                    Compose-Screens (Start, Analyse, Ergebnis, Verlauf, Video, Einstellungen)
      util/                  Edge-TTS-Stimmen, Bild-/Video-Hilfsfunktionen,
                             Online-Bildersuche (Wikimedia Commons/Openverse)
    res/                     Icons, Strings, Themes, App-Shortcuts
.github/workflows/build-apk.yml   CI: baut die APK und veröffentlicht sie als Release
```

## Selbst bauen

- **Am Handy:** nicht nötig – der GitHub-Workflow übernimmt alles.
- **Am PC:** Repository klonen, JDK 17 und Gradle 8.7 installieren, dann `gradle :app:assembleDebug`. Die APK liegt anschließend unter `app/build/outputs/apk/debug/`.

## Technologie

- Kotlin, Jetpack Compose (Material 3)
- Reine HTTP-API-Anbindung der KI-Anbieter (keine externen SDKs), Edge-TTS für kostenlose Online-Stimmen
- minSdk 26, targetSdk 34, JDK 17

## Weitere Dokumente

- `EINRICHTUNG.md` – Installation und Einrichtung Schritt für Schritt
- `DATENSCHUTZ.md` – welche Daten wo gespeichert werden
- `KOSTEN.md` – was die App kostet (Spoiler: die App selbst 0 €)
- `ROADMAP.md` – was fertig ist und was noch kommt
