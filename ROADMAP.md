# Roadmap

## Phase A – Kern-App (fertig)

- Foto aufnehmen oder aus der Galerie wählen [erledigt]
- Lokale, offline laufende Objekterkennung (ML Kit) [erledigt]
- Deutsche Erklärung: Was / Wie / Wofür / Praktische Tipps / Fakten / Sicherheit [erledigt]
- Kuratierte Wissensbasis mit rund 40 Alltagsobjekten in 9 Kategorien [erledigt]
- Unsicherheitsanzeige mit Warnkarte; bei kritischen Objekten (Pilze, Medikamente, Werkzeuge, Tiere) keine Gewissheitsbehauptung [erledigt]
- Verlauf mit Favoriten und Löschen [erledigt]
- Kamera-Schnellzugriff über Schnelleinstellungs-Kachel [erledigt]
- Automatischer APK-Build über GitHub Actions mit Release-Download [erledigt]

## Phase B – Erklärvideo (fertig, eine offene Abhängigkeit)

- In-App-Erklärvideo: Foto + Textkarten + Android-Sprachausgabe (TTS) mit Fortschrittsanzeige [erledigt]
- MP4-Export als Videodatei [offen – dokumentierte Abhängigkeit: benötigt die Integration von `MediaCodec`/`MediaMuxer`; die In-App-Wiedergabe deckt das Nutzungserlebnis bereits ab]

## Phase C – Echte Links (fertig)

- Echte, funktionierende Links pro Entdeckung: Wikipedia (Artikel wenn vorhanden, sonst Suche), YouTube-Suche, DuckDuckGo-Suche, Amazon-Suche [erledigt]
- Keine erfundenen URLs; keine YouTube- oder Amazon-API-Anbindung nötig [erledigt]

## Phase D – Optionale KI-Tiefenanalyse (fertig)

- Mistral-Vision-Analyse nur mit eigenem API-Schlüssel, nur auf Anfrage und nur mit Einwilligung pro Foto [erledigt]
- Automatischer Fallback auf die lokale Analyse, wenn die Anfrage fehlschlägt [erledigt]

## Phase E – Ausbau (geplant)

- Mehrere Fotos pro Objekt für genauere Erkennung
- Verlauf als Datei exportieren/importieren (Backup)
- Zusätzliche Sprachen
- Homescreen-Widget für Schnellstart

## Phase F – Qualität & Verteilung (geplant)

- Unit- und UI-Tests
- Signierter Release-Build (eigener Keystore) statt Debug-APK
- Vorbereitung einer optionalen Play-Store-Veröffentlichung
