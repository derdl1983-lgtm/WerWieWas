# Einrichtung (nur mit dem Handy)

## 1. APK herunterladen und installieren

1. Öffne `https://github.com/derdl1983-lgtm/WerWieWas` im Browser deines Handys.
2. Tippe auf den Reiter **Releases**.
3. Öffne den neuesten Release **„Automatischer APK-Build“** und lade **app-debug.apk** herunter.
4. Tippe auf die Download-Benachrichtigung.
5. Erlaube **„Installation aus unbekannten Quellen“** für deinen Browser (einmalig).
6. Tippe auf **Installieren**. Fertig.

Ist unter Releases keine APK zu finden, schaue in den Reiter **Actions**: Läuft der Workflow noch, dauert es ein paar Minuten. Ist er rot, liegt im Repository eine Datei `last-build-error.log` mit dem Fehlerprotokoll – warte dann auf den nächsten Build-Versuch.

## 2. Erste Nutzung

1. App **„Wer Wie Was“** öffnen.
2. Beim ersten Foto die **Kamera-Berechtigung** erlauben.
3. Objekt fotografieren → kurze Offline-Analyse → Erklärung mit **Was / Wie / Wofür / Fakten / Sicherheit**.
4. Über **Erklärvideo ansehen** lässt sich die Erklärung vorlesen.
5. **Verlauf** zeigt alle Entdeckungen; Sterne markieren Favoriten.

## 3. Kamera-Kachel für den Schnellzugriff

1. Benachrichtigungsleiste **zweimal nach unten** ziehen (Schnelleinstellungen öffnen).
2. Auf den **Bearbeiten-Stift** tippen.
3. Kachel **„Wer Wie Was“** suchen und in die obere (aktive) Reihe ziehen.
4. Ab sofort: Kachel antippen → die App öffnet direkt die Kamera.

## 4. Optional: Online-Tiefenanalyse (Mistral)

1. Kostenloses Konto auf `console.mistral.ai` anlegen und einen **API-Schlüssel** erstellen.
2. In der App: **Einstellungen → Mistral-API-Schlüssel** eintragen → **Speichern**. Der Schlüssel bleibt nur auf deinem Gerät.
3. Bei unsicheren Erkennungen fragt die App **pro Foto**, ob sie online analysieren darf. Ohne Bestätigung passiert nichts.
4. Ohne Schlüssel läuft die App vollständig offline – der Punkt ist rein optional.

## 5. Probleme lösen

- **App stürzt ab:** Einstellungen → „Alle Entdeckungen löschen“ versuchen oder App neu installieren.
- **Keine Sprachausgabe im Erklärvideo:** Prüfe unter Android → Einstellungen → Bedienungshilfen → Text-in-Sprache-Ausgabe, ob eine deutsche Stimme installiert ist.
- **Objekt wird nicht erkannt:** Mehr Licht, näher ans Objekt, Hintergrund ruhiger halten.
