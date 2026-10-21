# Roadmap

## Phase A – Kern-App (fertig)

- Foto aufnehmen oder aus der Galerie wählen [erledigt]
- Deutsche Erklärung: Was / Wie / Wofür / Praktische Tipps / Fakten / Sicherheit [erledigt]
- Unsicherheitsanzeige mit Warnkarte; bei kritischen Objekten (Pilze, Medikamente, Werkzeuge, Tiere) keine Gewissheitsbehauptung [erledigt]
- Verlauf mit Favoriten und Löschen [erledigt]
- Kamera-Schnellzugriff über Schnelleinstellungs-Kachel [erledigt]
- Automatischer APK-Build über GitHub Actions mit Release-Download [erledigt]

## Phase B – Erklärvideo (fertig, eine offene Abhängigkeit)

- In-App-Erklärvideo: Foto + Textkarten + Sprachausgabe (Edge-TTS-Online-Stimmen oder Android-TTS) mit Fortschrittsanzeige [erledigt]
- MP4-Export als Videodatei [offen – dokumentierte Abhängigkeit: benötigt die Integration von `MediaCodec`/`MediaMuxer`; die In-App-Wiedergabe deckt das Nutzungserlebnis bereits ab]

## Phase C – Echte Links (fertig)

- Echte, funktionierende Links pro Entdeckung: Wikipedia (Artikel wenn vorhanden, sonst Suche), YouTube-Suche, DuckDuckGo-Suche, Amazon-Suche [erledigt]
- Keine erfundenen URLs; keine YouTube- oder Amazon-API-Anbindung nötig [erledigt]

## Phase D – KI-Analyse online (fertig)

- Analyse ausschließlich online mit eigenem API-Schlüssel, Einwilligung vor jedem Foto [erledigt]
- Anbieter-Auswahl in den Einstellungen: Mistral, OpenAI-kompatibel (inkl. eigener Server), OpenRouter, Google Gemini, Anthropic Claude, xAI Grok [erledigt]
- Aktueller Modellstand je Anbieter mit Schnellauswahl (GPT-6-Familie, Gemini 3.x/4, Claude 5.5/Fable, Grok 4.x, Mistral Medium 3.5) [erledigt]
- Verständliche Fehlermeldungen bei 401/404/429/5xx der Anbieter-APIs [erledigt]
- Frühere lokale ML-Kit-Erkennung und kuratierte Offline-Wissensbasis wurden entfernt – die Analyse läuft ausschließlich online [erledigt]

## Phase E – Bedienung & Schnellstart (fertig)

- Modernisierte Oberfläche: Material-3-Farbschema mit Farbverlauf, animierter Kamera-Button, Karten-Layout auf der Startseite [erledigt]
- Schnellfoto über mehrere Wege: großer Kamera-Button, App-Symbol-Long-Press-Shortcuts (Foto/Galerie), Schnelleinstellungs-Kachel [erledigt]
- Direktanalyse ohne Rückfrage (Opt-in in den Einstellungen): Analyse startet sofort nach dem Foto [erledigt]

## Phase F – Ausbau (geplant)

- Mehrere Fotos pro Objekt für genauere Erkennung
- Verlauf als Datei exportieren/importieren (Backup)
- Zusätzliche Sprachen
- Homescreen-Widget für Schnellstart

## Phase G – Qualität & Verteilung (geplant)

- Unit- und UI-Tests
- Signierter Release-Build (eigener Keystore) statt Debug-APK
- Vorbereitung einer optionalen Play-Store-Veröffentlichung
