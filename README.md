# Wer Wie Was

Foto machen → verstehen: **Wer Wie Was** erkennt Objekte direkt auf dem Gerät und erklärt auf Deutsch, was sie sind, wie sie funktionieren und wofür man sie benutzt.

## Funktionen

- **Foto oder Galerie** – Objekt ablichten oder ein vorhandenes Foto analysieren
- **Lokale Erkennung (Google ML Kit)** – komplett offline, kostenlos, ohne Konto und ohne Cloud
- **Ehrliche Unsicherheit** – ist sich die App nicht sicher, sagt sie das deutlich; bei kritischen Objekten (Pilze, Medikamente, Werkzeuge) wird nie eine Gewissheit behauptet
- **Erklärung in vier Fragen** – Was ist das? Wie funktioniert es? Wofür wird es verwendet? Plus Fakten und Sicherheitshinweise
- **Erklärvideo** – Foto und Textkarten, vorgelesen über die Android-Sprachausgabe
- **Verlauf & Favoriten** – alle Entdeckungen bleiben auf dem Gerät
- **Echte Links** – Wikipedia, YouTube-Suche, DuckDuckGo und Amazon-Suche als echte Suchlinks (keine erfundenen URLs)
- **Kamera-Schnellzugriff** – Schnelleinstellungs-Kachel öffnet die App direkt in der Kamera
- **Optionale Online-Tiefenanalyse** – über die Mistral-API, nur mit eigenem Schlüssel und nur nach Bestätigung pro Foto

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
      MainActivity.kt        Navigation, Kamera, Galerie
      data/                  Entdeckungs-Modell, Wissensbasis (~40 Objekte)
      ml/                    Lokale ML-Kit-Analyse, optionale Mistral-Analyse
      service/               Kamera-Kachel für die Schnelleinstellungen
      ui/                    Compose-Screens (Start, Analyse, Ergebnis, Verlauf, Video, Einstellungen)
      util/                  Bild-Hilfsfunktionen
    res/                     Icons, Strings, Themes
.github/workflows/build-apk.yml   CI: baut die APK und veröffentlicht sie als Release
```

## Selbst bauen

- **Am Handy:** nicht nötig – der GitHub-Workflow übernimmt alles.
- **Am PC:** Repository klonen, JDK 17 und Gradle 8.7 installieren, dann `gradle :app:assembleDebug`. Die APK liegt anschließend unter `app/build/outputs/apk/debug/`.

## Technologie

- Kotlin, Jetpack Compose (Material 3)
- Google ML Kit Image Labeling (offline, auf dem Gerät gebündelt)
- minSdk 26, targetSdk 34, JDK 17

## Weitere Dokumente

- `EINRICHTUNG.md` – Installation und Einrichtung Schritt für Schritt
- `DATENSCHUTZ.md` – welche Daten wo gespeichert werden
- `KOSTEN.md` – was die App kostet (Spoiler: 0 €)
- `ROADMAP.md` – was fertig ist und was noch kommt
