package de.derdl1983.weriewas.data

import de.derdl1983.weriewas.data.LabelConfidence
import java.net.URLEncoder

/**
 * Kuratierte Wissensbasis von „Wer Wie Was“.
 * Ordnet ML-Kit-Labels (Englisch) deutsche Erklärungen zu: Was, Wie, Wofür,
 * Praktisches, Fakten, Sicherheit – plus echte Weiterbildungslinks.
 * Bei sicherheitskritischen Objekten wird NIE eine Gewissheit behauptet.
 */
object KnowledgeBase {

    /** Ein Wissensbasis-Eintrag. */
    private data class Entry(
        val labels: List<String>,      // ML-Kit-Labels (Englisch)
        val title: String,
        val category: String,
        val summary: String,
        val howItWorks: String,
        val usage: String,
        val practical: String,
        val facts: List<String>,
        val safety: String,
        val wiki: String? = null,      // Wikipedia-Artikelname oder null → Suchlink
        val critical: Boolean = false
    )

    private val entries: List<Entry> = listOf(
        // ---------- Haushalt ----------
        Entry(
            listOf("Cup", "Mug", "Coffee cup", "Teacup"),
            "Becher / Tasse", "Haushalt",
            "Ein kleines Trinkgefäß aus Keramik, Glas oder Kunststoff mit Henkel.",
            "Das heiße Getränk wird im hohlraum aufgenommen; Keramik speichert Wärme, der Henkel schützt die Finger.",
            "Zum Trinken von Kaffee, Tee oder anderen Heißgetränken.",
            "Zerbrechliche Tassen mit einem weichen Untersetzer vor Kratzer schützen.",
            listOf("Tassen gibt es seit etwa 10.000 Jahren – damals noch ohne Henkel.", "Der Henkel wurde erst im 18. Jahrhundert populär, weil Tee sehr heiß getrunken wurde."),
            "Heiße Tassen können Verbrennungen verursachen – Henkel oder Untersetzer benutzen."
        ),
        Entry(
            listOf("Bottle", "Water bottle"),
            "Flasche", "Haushalt",
            "Ein schmales, hohes Gefäß mit engem Hals zum Aufbewahren von Flüssigkeiten.",
            "Der enge Hals erleichtert das Ausgießen; Schraub- oder Steckverschluss halten die Flüssigkeit dicht.",
            "Für Wasser, Saft, Softdrinks oder andere Getränke – transportabel und wiederverschließbar.",
            "Mehrwegflaschen nach jedem Gebrauch ausspülen und offen trocknen lassen.",
            listOf("Glasflaschen sind zu 100 % recycelbar.", "Plastikflaschen können bis zu 450 Jahre zum Zersetzen brauchen."),
            "Nicht in der Sonne liegen lassen, wenn Getränke darin sind – das begünstigt Keime in Plastikflaschen."
        ),
        Entry(
            listOf("Plate", "Dish", "Dinner plate"),
            "Teller", "Haushalt",
            "Eine flache, runde Unterlage aus Keramik oder Kunststoff für Speisen.",
            "Die flache Form hält portionsgroße Speisen an Ort und Stelle; der leicht erhöhte Rand schützt vor Verschütten.",
            "Als Essunterlage für Hauptgerichte, Salate oder Desserts.",
            "Teller erst abkühlen lassen, bevor sie ins kalte Wasser kommen – Temperaturschock kann Keramik springen lassen.",
            listOf("Das Wort „Teller“ kommt vom altfranzösischen „telloire“ für Holzschneidebrett.", "Für Menüs gibt es eigene Tellergrößen von Vorspeise bis Dessert."),
            "Zerbrochene Teller sofort aufkehren – Scherben schneiden."
        ),
        Entry(
            listOf("Fork"),
            "Gabel", "Haushalt",
            "Ein Essbesteck mit mehreren langen Zinken zum Aufspießen von Speisen.",
            "Die Zinken greifen fests Speisen, der Griff gibt sicheren Halt in der Hand.",
            "Zum Essen von Fleisch, Salat, Pasta und zum portionieren beim Kochen.",
            "Gabeln mit den Zinken nach unten in den Besteckkorb der Spülmaschine legen.",
            listOf("Gabeln waren in Europa zunächst umstritten – die Kirche hielt sie im Mittelalter für überflüssig.", "Die heutige vierzinkige Form setzte sich erst im 19. Jahrhundert durch."),
            "Kinder sollten beim Essen mit der Gabel aufpassen – die Zinken sind spitz."
        ),
        Entry(
            listOf("Spoon", "Tablespoon", "Teaspoon"),
            "Löffel", "Haushalt",
            "Ein Essbesteck mit ovaler Schale am Stiel zum Aufnehmen von Flüssigem oder Weichem.",
            "Die Schale nimmt einen mundgerechten Teil auf, der Stiel führt sie zum Mund.",
            "Für Suppe, Joghurt, Müsli oder zum Abmessen in der Küche.",
            "Ein Esslöffel fasst etwa 15 ml, ein Teelöffel etwa 5 ml – praktisch beim Kochen.",
            listOf("Löffel gab es schon in der Steinzeit – aus Muscheln oder Holz.", "Silberlöffel galten jahrhundertelang als Statussymbol."),
            "Löffel aus Metall werden sehr heiß, wenn sie in heißen Getränken liegen bleiben."
        ),
        Entry(
            listOf("Knife", "Kitchen knife", "Chef's knife", "Pocketknife", "Cutting tool"),
            "Küchenmesser", "Haushalt",
            "Ein Werkzeug mit scharfer Klinge zum Schneiden von Lebensmitteln und Materialien.",
            "Der scharfe, dünne Keil der Klinge konzentriert die Handkraft auf eine schmale Linie und trennt so Material.",
            "In der Küche zum Schneiden von Gemüse, Fleisch und Brot.",
            "Schnitze auf ein stabiles Schneidebrett und führe die Klinge vom Körper weg.",
            listOf("Messer gehören zu den ältesten Werkzeugen der Menschheit – älter als 2 Millionen Jahre.", "Eine gut gepflegte Küchenmesser-Klinge ist dünner als ein menschliches Haar an der Schneide."),
            "Messer sind scharf: Nie in Richtung der freien Hand schneiden, nie im Abwasch liegend greifen, Kinder fernhalten.",
            critical = true
        ),
        Entry(
            listOf("Scissors", "Shears"),
            "Schere", "Haushalt",
            "Ein Schneidewerkzeug aus zwei beweglich verbundenen Klingen mit Griffen.",
            "Zwei Klingen gleiten aneinander und scheren das Material, wenn die Griffe zusammengedrückt werden.",
            "Für Papier, Stoff, Haare oder Verpackungen.",
            "Eine dedizierte Papierschere nicht für Stoff nutzen – Stoff stumpft die Klinge schneller ab.",
            listOf("Die Schere wurde vermutlich im alten Ägypten um 1500 v. Chr. erfunden.", "Die moderne Kreuzschere mit zwei Griffen stammt aus dem Rom des 1. Jahrhunderts."),
            "Niemals mit der Schere auf jemanden zeigen oder laufen – die Spitzen sind spitz.",
            critical = true
        ),
        Entry(
            listOf("Bucket", "Pail"),
            "Eimer", "Haushalt",
            "Ein zylindrischer Behälter mit Henkel zum Tragen von Flüssigkeiten oder Schüttgut.",
            "Der stabile Boden trägt den Inhalt, der Henkel verteilt das Gewicht beim Tragen.",
            "Für Putzwasser, zum Transportieren oder Aufbewahren.",
            "Nasse Eimer offen und kopfüber trocknen lassen – so entsteht kein Geruch.",
            listOf("Antike Eimer waren oft aus Holz dauben – wie kleine Fässer.", "Der Plastikeimer wurde erst Mitte des 20. Jahrhunderts alltäglich."),
            "Volle Eimer können sehr schwer werden – Rücken gerade halten beim Anheben."
        ),
        Entry(
            listOf("Umbrella"),
            "Regenschirm", "Haushalt",
            "Ein faltbarer Schutz vor Regen mit gestänkerten Bezug.",
            "Das Gestänge spannt einen wasserabweisenden Stoff auf, der den Regen ablenkt, ohne ihn durchzulassen.",
            "Regenschutz für unterwegs; Sonnen- und Regenschatten.",
            "Den Schirm nach Gebrauch halb offen trocknen lassen, dann hält der Bezug länger.",
            listOf("Der klappbare Regenschirm wurde erst in den 1920er-Jahren populär.", "In Japan gilt es als Kunststück, im Gedränge einen Schirm zu teilen – daher heißt es dort „kasa“."),
            "Bei Sturm den Schirm schließen – Gestänge können umknicken."
        ),
        // ---------- Werkzeug ----------
        Entry(
            listOf("Hammer", "Claw hammer", "Mallet"),
            "Hammer", "Werkzeug",
            "Ein Schlagwerkzeug mit Kopf und Stiel zum Einschlagen oder Formen.",
            "Der schwere Kopf wandelt eine kurze Armbewegung in konzentrierte Schlagkraft um.",
            "Für Nägel, zum Abbauen oder in der Werkstatt.",
            "Vor dem Schlag kurz prüfen, dass der Hammerkopf fest sitzt – gelockerte Köpfe können abfliegen.",
            listOf("Der Hammer ist eines der ältesten Werkzeuge überhaupt – Steinhammerwerkzeuge sind über 3 Millionen Jahre alt.", "Der Zimmermannshammer mit gebogenem Nagelzieher heißt „Klaue“ – daher Claw Hammer."),
            "Niemals auf die Finger schlagen; Schutzbrille tragen – Metallteile können absplittern.",
            critical = true
        ),
        Entry(
            listOf("Screwdriver"),
            "Schraubendreher", "Werkzeug",
            "Ein Drehwerkzeug mit Klinge und Griff zum Ein- und Ausdrehen von Schrauben.",
            "Die Klinge greift in den Schraubenkopf, der Griff vergrößert das Drehmoment der Hand.",
            "Für Montage und Reparaturen von Möbeln bis Elektronik.",
            "Kreuz- (PH) und Schlitzschrauben haben verschiedene Klingengrößen – der Griff sagt viel über die Qualität.",
            listOf("Der Schraubendreher kam erst mit den ersten Maschinenschrauben im 15. Jahrhundert auf.", "Die Phillips-Kreuzklinge wurde für Automontage entwickelt – damit der Treiber nicht herausrutscht."),
            "Nicht mit zu viel Kraft drehen – der Treiber kann abrutschen und Schnittwunden verursachen.",
            critical = true
        ),
        Entry(
            listOf("Wrench", "Spanner", "Adjustable wrench", "Monkey wrench"),
            "Schraubenschlüssel", "Werkzeug",
            "Ein Greifwerkzeug mit Maul oder verstellbarer Backe für Sechskantmuttern und -schrauben.",
            "Der Schlüssel umschließt die Kanten des Sechskants; Hebelwirkung des Griffs dreht die Mutter.",
            "Für Sanitär-, Fahrrad- und Maschinenreparaturen.",
            "Der Schlüssel muss voll auf der Mutter sitzen – halb aufgesetzt rundet er die Kanten ab.",
            listOf("Der verstellbare Engländer wurde 1842 patentiert – daher der Name „Engländer“ im Volksmund.", "Rohrglieder heißen Ringschlüssel und greifen besonders sicher."),
            "Festklemmen hilft gegen Abrutschen; bei harten Verklemmungen kein Verlängerungsrohr nutzen.",
            critical = true
        ),
        Entry(
            listOf("Drill", "Power drill", "Electric drill"),
            "Bohrmaschine", "Werkzeug",
            "Ein Elektrowerkzeug, das mit rotierendem Bohrer Löcher erzeugt.",
            "Ein Motor rotiert den Bohrer; Vorschubdruck und Drehzahl bestimmen, wie schnell das Material durchdrungen wird.",
            "Für Löcher in Holz, Metall, Mauerwerk – mit Aufsatz auch als Schrauber nutzbar.",
            "Bohrdurchmesser passend zum Dübel wählen und Materialprobe bohren.",
            listOf("Die erste elektrische Handbohrmaschine kam 1895 auf den Markt und wog mehrere Kilogramm.", "Schlagbohrfunktionen hämmern zusätzlich vorwärts – ideal für Beton."),
            "Schutzbrille tragen, Werkstück einspannen, NIEMALS in Stromleitungen bohren – Karten- und Leitungssucher nutzen.",
            critical = true
        ),
        Entry(
            listOf("Saw", "Hand saw", "Circular saw", "Chainsaw", "Hacksaw"),
            "Säge", "Werkzeug",
            "Ein Werkzeug mit gezahnter Klinge zum Trennen von Holz, Metall oder Kunststoff.",
            "Die Sägezähne entfernen bei jeder Bewegung kleine Späne – der Schnitt entsteht durch wiederholtes Ziehen.",
            "Für Holzbau, Baum- und Metallarbeiten.",
            "Die Klinge regelmäßig reinigen und bei Hartholz schneiden, sonst setzt sie sich zu.",
            listOf("Sägeblätter haben je nach Nutzung verschiedene Zahnformen – rippen für Längsschnitt, kreuzen für Querschnitt.", "Die Kreissäge löste die Handsäge im Berufsbau fast völlig ab."),
            "Sägeblätter sind extrem scharf: Schutzhandschuhe, Arbeitshandschuhe und Halterungen am Werkstück; niemals mit der Hand in die Schnittlinie greifen.",
            critical = true
        ),
        Entry(
            listOf("Ladder", "Step ladder"),
            "Leiter", "Werkzeug",
            "Ein Steigwerkzeug aus Sprossen oder Stufen zum Arbeiten in der Höhe.",
            "Die geneigte oder A-Form der Leiter gibt Stabilität; Sprossen dienen als Stufen.",
            "Für Malerarbeiten, Lampenwechsel, Dachkontrolle.",
            "Die Leiter pro vier Meter Höhe einen Meter Abstand zum Gebäude – die 4-zu-1-Regel beachten.",
            listOf("Die älteste Darstellung einer Leiter ist über 10.000 Jahre alt – in einer Höhle in Spanien.", "Klappleitern aus Aluminium sind heute Standard – leicht und stabil."),
            "Nie auf der obersten Stufe stehen, Leiter nur auf festem Untergrund aufstellen und mit einer Hand festhalten.",
            critical = true
        ),
        Entry(
            listOf("Pliers", "Long-nose pliers", "Cutting pliers"),
            "Zange", "Werkzeug",
            "Ein Greif- und Trennwerkzeug mit zwei Hebelarmen und Backen.",
            "Die Hebelwirkung der Arme verstärkt die Handkraft an den Backen – greifen, halten oder schneiden.",
            "Für Elektronikmontage, Drahtarbeiten und kleine Reparaturen.",
            "Für Elektronik eine abisolierbare Pinzettenspitze nutzen – sie beschädigt Kabel nicht.",
            listOf("Es gibt über 50 Zangenarten – von der Flachzange bis zur Knipex-Schlauchzange.", "Isolierte Zangen sind bis 1000 Volt geprüft."),
            "Zangen sind kein Hammerersatz; bei Elektrik nur isolierte Griffe benutzen.",
            critical = true
        ),
        Entry(
            listOf("Nail", "Spike"),
            "Nagel", "Werkzeug",
            "Ein schlanker Metallstift mit Spitze und Kopf zum Befestigen von Materialien.",
            "Der Nagel wird durch Schläge in das Material getrieben; Reibung an den Seiten hält ihn fest.",
            "Für Holzbau, Zargenmontage, Zierarbeiten.",
            "Dünne Nägel vor dem Einschlagen leicht anfeilen – das verhindert Absplittern.",
            listOf("Nägel wurden jahrhundertelang von Hand geschmiedet – ein Schmied schaffte etwa 100 pro Minute.", "Maschinennägel aus Draht setzten sich erst im 19. Jahrhundert durch."),
            "Hände beim Einschlagen weg vom Nagel halten, Nägel nie in unbekannte Wände schlagen – Strom- und Wasserleitungen prüfen."
        ),
        // ---------- Elektronik ----------
        Entry(
            listOf("Laptop", "Notebook", "Computer"),
            "Laptop", "Elektronik",
            "Ein tragbarer Computer mit eingebauter Tastatur, Bildschirm und Akku.",
            "Prozessor, Arbeitsspeicher und Speicher laufen unter einem Betriebssystem; Tastatur und Touchpad dienen der Eingabe.",
            "Für Arbeit, Lernen, Reisen – überall dort, wo ein Desktop zu groß wäre.",
            "Alle paar Monate die Lüftungsschlitze vom Staub befreien – das verlängert die Lebensdauer deutlich.",
            listOf("Der erste Laptop-Klassiker war der GRiD Compass von 1982 – er kostete umgerechnet über 15.000 Euro.", "Moderne Laptops sind rechnerisch stärker als frühere Supercomputer."),
            "Akku bei starker Hitze nicht dauerhaft laden – Brandgefahr ist bei Laptops gering, aber Verschleiß real."
        ),
        Entry(
            listOf("Mobile phone", "Smartphone", "Phone", "Iphone"),
            "Smartphone", "Elektronik",
            "Ein internetfähiges Mobiltelefon mit Touchscreen und installierbaren Apps.",
            "Ein Betriebssystem steuert Hardware; über Touchscreen werden Apps bedient, Mobilfunk und WLAN verbinden es mit der Welt.",
            "Kommunikation, Fotos, Navigation, Banking – der Begleiter für fast alle digitalen Aufgaben.",
            "Bildschirmzeit und App-Berechtigungen regelmäßig prüfen – das schont Akku und Privatsphäre.",
            listOf("Das erste Smartphone namens „Smartphone“ kam 1997 von Ericsson auf den Markt.", "Ein aktuelles Smartphone hat mehr Rechenleistung als die Computer, mit denen Apollo 11 zum Mond flog."),
            "Nicht beim Autofahren benutzen – das ist verboten und gefährdet alle."
        ),
        Entry(
            listOf("Television", "TV", "Monitor"),
            "Fernseher", "Elektronik",
            "Ein großflächiges Bildschirmgerät für Filme, Serien und Nachrichten.",
            "Tausende Pixel-Leuchtpunkte werden von Elektronik angesteuert und erzeugen gemeinsam das Bild; Lautsprecher liefern den Ton.",
            "Medienkonsum, Gaming und Präsentationen.",
            "Für Filme den Filmmaker-Modus aktivieren – er zeigt Farben so, wie Regisseure sie gedacht haben.",
            listOf("Der erste elektronische Fernseher wurde 1927 demonstriert.", "4K-Auflösung hat die achtfache Pixelzahl von Full HD."),
            "Wände nicht mit schweren TVs überlasten – Halterung nach Anleitung montieren."
        ),
        Entry(
            listOf("Remote control", "Remote"),
            "Fernbedienung", "Elektronik",
            "Ein Handgerät mit Tasten zur drahtlosen Steuerung von Geräten.",
            "Beim Tastendruck sendet eine Infrarot-LED (oder Funk) ein codiertes Signal, das das Gerät entschlüsselt und ausführt.",
            "Für TV, Soundanlage, Streaming-Boxen – Steuerung aus der Ferne.",
            "Bei „verrückten“ Reaktionen zuerst die Batterien prüfen – schwache Zellen senden falsche Signale.",
            listOf("Die erste Fernbedienung namens „Lazy Bones“ gab es 1950 – sie war noch über Kabel verbunden.", "Infrarot-Fernbedienungen arbeiten mit unsichtbarem Licht – Handykameras machen es sichtbar."),
            "Knopfzellen für Kinder unzugänglich aufbewahren – verschluckte Batterien sind gefährlich."
        ),
        Entry(
            listOf("Computer keyboard", "Keyboard"),
            "Tastatur", "Elektronik",
            "Ein Eingabegerät mit Tasten für Zeichen und Befehle.",
            "Jede Taste schließt einen elektrischen Kontakt; die Elektronik meldet die Position, das System erzeugt das zugehörige Zeichen.",
            "Texteingabe am Computer, Steuerung von Software.",
            "Tastaturen mit mechanischen Schaltern halten deutlich länger als Gummimatten – dafür sind sie lauter.",
            listOf("Die QWERTZ-Anordnung stammt von 1873 – von der Schreibmaschine.", "Eine Tastatur überträgt bei jedem Tastendruck einen eigenen Code – daher funktionieren Tasten auch beim schnellen Tippen."),
            "Tastaturen nie in Flüssigkeiten tauchen; bei Verschüttetem sofort abtrennen und trocknen lassen."
        ),
        Entry(
            listOf("Computer mouse", "Mouse"),
            "Computer-Maus", "Elektronik",
            "Ein Zeigegerät, das Bewegungen in Cursor-Positionen am Bildschirm umsetzt.",
            "Ein Sensor misst die Bewegung auf der Unterlage und meldet sie; Klick-Tasten lösen Aktionen am Bildschirm aus.",
            "Navigation am PC oder Laptop.",
            "Mauspads mit Handballenauflage schonen das Handgelenk bei langen Sessions.",
            listOf("Die erste Maus hieß „X-Y Position Indicator“ – der Name „Maus“ kam wegen des Kabelschwanzes.", "Der Maus-Erfinder Douglas Engelbart bekam für sein Patent rund 10.000 Dollar – die Rechte lagen bei seinem Arbeitgeber."),
            "Mäuse mit defektem Kabel nicht weiterverwenden – Brand- und Kurzschlussgefahr."
        ),
        Entry(
            listOf("Headphones", "Earbuds", "Headset", "In-ear headphones"),
            "Kopfhörer", "Elektronik",
            "Ein Tongerät, das Töne direkt ans Ohr leitet.",
            "Kleine Membranen wandeln elektrische Signale in Schwingungen um, die als Ton gehört werden.",
            "Musik, Podcasts, Telefongespräche – ganz ohne Störung der Umgebung.",
            "Lautstärke unter 60 % halten und Pausen einlegen – das schützt das Gehör dauerhaft.",
            listOf("Der erste Kopfhörer stammt aus dem Jahr 1910 – er wog über zwei Kilo.", "In-Ear-Kopfhörer können bei zu hoher Lautstärke schneller das Gehör schädigen als Ohrhörschutz-Träger."),
            "Im Straßenverkehr keine Musik mit Abdeckung der Umgebung – Ampeln und Autos hört man sonst nicht."
        ),
        Entry(
            listOf("Battery", "AA battery", "Car battery"),
            "Batterie", "Elektronik",
            "Ein Energiespeicher, der Chemie in elektrischen Strom umwandelt.",
            "Zwei verschiedene Materialien reagieren elektrochemisch; der Fluss der Elektronen zwischen ihnen ist der Strom.",
            "Für Geräte ohne Steckdosenanschluss – von Uhren bis Autos.",
            "Batterien niemals aufladen, wenn es keine aufladbaren Zellen sind – Explosionsgefahr.",
            listOf("Die erste Batterie erfand Alessandro Volta 1799 – aus Kupfer und Zink in Salzwasser.", "In Deutschland werden jährlich über eine Milliarde Batterien verkauft."),
            "Batterien gehören nicht in den Hausmüll und niemals ins Feuer – Brand- und Vergiftungsgefahr, beim Verschlucken sofort Arzt rufen.",
            critical = true
        ),
        // ---------- Natur ----------
        Entry(
            listOf("Tree", "Willow", "Oak", "Maple", "Pine", "Fir"),
            "Baum", "Natur",
            "Eine mehrjährige Pflanze mit verholztem Stamm und Krone.",
            "Wurzeln nehmen Wasser auf, Blätter betreiben Photosynthese – Holz wächst in Jahresringen.",
            "Spendet Sauerstoff, Schatten, Holz und Lebensraum für Tiere.",
            "Bäume im eigenen Garten erst nach der Hauptbrutzeit (ab Oktober) stark schneiden.",
            listOf("Ein alter Baum kann so viel Wasser aufnehmen wie ein Schwamm – über 200 Liter pro Tag.", "Der älteste Baum der Welt ist eine Latschenkiefer mit fast 5.000 Jahren."),
            "Tote Äste können ohne Vorwarnung brechen – bei Sturm nicht unter alten Bäumen aufhalten.",
            wiki = "Baum"
        ),
        Entry(
            listOf("Flower", "Blossom", "Plant", "Houseplant"),
            "Blume / Pflanze", "Natur",
            "Eine Pflanze mit auffälligen, oft farbigen Blüten.",
            "Blüten locken Bestäuber an; aus befruchteten Blüten entstehen Samen und Früchte.",
            "Zierde in Garten und Wohnung, Nahrung für Insekten, Bienenweide.",
            "Blumen früh morgens gießen – dann verdunstet weniger.",
            listOf("Die größte Blume der Welt, Rafflesia, stinkt nach Aas und wird bis zu einem Meter groß.", "Tulpen waren im 17. Jahrhundert in Holland so wertvoll, dass eine Zwiebel den Preis eines Hauses erreichte."),
            "Pflanzenteile unbekannter Pflanzen nicht essen und Tiere wie Bienen nicht anstupsen – manche sind giftig oder stechen.",
            wiki = null
        ),
        Entry(
            listOf("Rose", "Rosa"),
            "Rose", "Natur",
            "Ein blühender Strauch mit gefüllten, oft duftenden Blüten.",
            "Die Rose blüht über Bestäubung und bildet danach Hagebutten als Früchte.",
            "Zierpflanze, Schnittblume, Symbol für Liebe.",
            "Verwelkte Blüten regelmäßig entfernen – dann blüht die Rose oft ein zweites Mal.",
            listOf("Rosen existieren seit rund 35 Millionen Jahren, wie Fossilfunde zeigen.", "Es gibt über 30.000 Rosensorten weltweit."),
            "Rosen haben Dornen – Handschuhe beim Schneiden schützen.",
            wiki = "Rose_(Pflanze)"
        ),
        Entry(
            listOf("Dog", "Puppy", "Canine"),
            "Hund", "Natur",
            "Ein domestiziertes Raubtiere aus der Familie der Hunde (Caniden).",
            "Hunde leben in sozialen Verbänden, kommunizieren über Körpersprache und Lauten und lernen durch Konditionierung.",
            "Haustier, Begleiter, Arbeitshund bei Polizei und Rettungsdiensten.",
            "Für die Bindung: gemeinsame Spaziergänge sind wertvoller als teures Spielzeug.",
            listOf("Der Hund stammt vom Wolf ab und ist das älteste Haustier des Menschen – seit etwa 15.000 Jahren.", "Hunde riechen bis zu 100.000-mal besser als Menschen."),
            "Fremde Hunde nie ohne Erlaubnis des Besitzers streicheln; Hunde nie beim Fressen stören – Bissgefahr.",
            wiki = "Haushund"
        ),
        Entry(
            listOf("Cat", "Kitten", "Feline"),
            "Katze", "Natur",
            "Ein kleines, räuberisches Haustier aus der Familie der Katzen.",
            "Katzen jagen aus dem Hinterhalt, sehen gut in der Dämmerung und kommunizieren über Miauen, Schwanz und Ohren.",
            "Haustier und Mäusejäger – einer der beliebtesten Begleiter weltweit.",
            "Katzen brauchen Rückzugsorte – ein Karton reicht dafür völlig.",
            listOf("Katzen verbringen bis zu 16 Stunden täglich mit Schlafen.", "Das Schnurren liegt bei etwa 25 Hertz und kann Knochenwachstum fördern."),
            "Katzenbisse können stark infizieren – bei Bisswunden ärztlich abklären lassen.",
            wiki = "Hauskatze"
        ),
        Entry(
            listOf("Bird", "Sparrow", "Pigeon", "Robin", "Eagle", "Owl"),
            "Vögel", "Natur",
            "Gefiederte Tiere mit Flügeln, die aus Eiern schlüpfen.",
            "Vögel sind Warmblüter, halten Körpertemperatur konstant und nutzen Federn für Fliegen und Isolierung.",
            "Bereichern Ökosysteme als Samenverbreiter und Insektenjäger.",
            "Im Winter Wasser tränken – Futter aber sparsam und richtig platzieren.",
            listOf("Es gibt weltweit rund 11.000 Vogelarten.", "Einige Zugvögel fliegen pro Jahr 70.000 km – so viel wie zweimal um die Erde."),
            "Wildvögel nicht anfassen – einige übertragen Salmonellen; Jungtiere am Boden meist nicht verlassen, sondern beobachten.",
            wiki = "Vögel"
        ),
        Entry(
            listOf("Butterfly", "Moth"),
            "Schmetterling", "Natur",
            "Ein Insekt mit großen, oft farbenprächtigen Flügeln.",
            "Aus der Raupe entsteht über die Verpuppung der fertige Falter – eine vollständige Verwandlung.",
            "Bestäuber von Blüten; Gradmesser für intakte Natur.",
            "Schmetterlings-freundliche Gärten pflanzen: Nektarblumen und Raupenfutterpflanzen kombinieren.",
            listOf("Schmetterlinge schmecken mit den Füßen.", "Der Monarchfalter fliegt bis zu 4.000 km von Kanada nach Mexiko."),
            "Falter nicht berühren – die Flügel sind mit winzigen Schuppen bedeckt, die bei Berührung abbrechen.",
            wiki = "Schmetterlinge"
        ),
        Entry(
            listOf("Mushroom", "Fungus", "Toadstool"),
            "Pilz", "Natur",
            "Ein Sporenpilz mit Hut und Stiel, der oft in Wäldern wächst.",
            "Der sichtbare Fruchtkörper ist nur ein Teil; das eigentliche Pilzgeflecht (Myzel) lebt im Boden und zersetzt organisches Material.",
            "Forstlich wichtig als Zersetzer; manche Arten sind essbar – viele hochgiftig.",
            "Keine Pilze ohne 100%ige Kenntnis und Bestätigung durch Fachleute essen.",
            listOf("Der größte bekannte Pilz der Welt ist ein Hallimasch in den USA – sein Myzel erstreckt sich über 900 Hektar.", "Pilze sind näher mit Tieren verwandt als mit Pflanzen."),
            "Viele Speisepilze haben tödliche Doppelgänger – der Grüne Knollenblätterpilz verursacht ohne Behandlung tödliches Leberversagen. Diese Erkennung ist KEINE Bestätigung! Sammle nur mit Pilzschein/Pilzberater, im Zweifel die Giftnotruf-Zentrale anrufen.",
            critical = true
        ),
        Entry(
            listOf("Snake", "Serpent", "Viper", "Cobra", "Python"),
            "Schlange", "Natur",
            "Ein Reptil ohne Beine mit langem, geschupptem Körper.",
            "Schlangen bewegen sich durch seitliche Wellenbewegungen; viele töten Beute durch Umschlingen oder Gift.",
            "Regeln als Räuber Ökosysteme – die meisten Arten sind für den Menschen harmlos.",
            "Bei Begegnung ruhig bleiben und langsam zurückweichen – Schlangen greifen nur bei Bedrohung an.",
            listOf("Es gibt rund 4.000 Schlangenarten – nur etwa 600 sind giftig für Menschen.", "Manche Schlangen können wochenlang ohne Nahrung auskommen."),
            "Manche Schlangen sind lebensgefährlich giftig – im Zweifel IMMER Abstand halten und bei einem Biss sofort den Notruf (112) wählen und ruhig bleiben. Diese Erkennung ist KEINE Bestätigung.",
            critical = true
        ),
        // ---------- Lebensmittel ----------
        Entry(
            listOf("Apple", "Fruit"),
            "Apfel", "Lebensmittel",
            "Eine runde Frucht mit fester Schale, knusprigem Fruchtfleisch und Kerngehäuse.",
            "Der Apfel wächst am Baum und reift nach der Blüte im Sommer bis Herbst heran.",
            "Frisch gegessen, als Saft, Mus oder im Gebäck – eine der beliebtesten Obstsorten.",
            "Äpfel im Kühlschrank lagern – so bleiben sie wochenlang frisch.",
            listOf("Es gibt weltweit über 20.000 Apfelsorten.", "Äpfel bestehen zu rund 85 % aus Wasser."),
            "Kerngehäuse und Stiel entfernen; Apfelsamen enthalten in großen Mengen Amygdalin – einzelne Kerne sind unbedenklich.",
            wiki = "Apfel"
        ),
        Entry(
            listOf("Banana", "Bananas"),
            "Banane", "Lebensmittel",
            "Eine längliche, krumme Tropenfrucht mit gelber Schale.",
            "Bananen wachsen an Stauden in Büscheln und reifen nach der Ernte nach – Ethylen beschleunigt das.",
            "Als Snack, im Smoothie oder Müsli – reich an Kalium.",
            "Grüne Bananen mit einem reifen Apfel zusammen in eine Tüte legen – schnelleres Nachreifen.",
            listOf("Die Banane ist botanisch eine Beere.", "In Deutschland isst jeder Mensch im Schnitt rund 10 kg Bananen pro Jahr."),
            "Bananen für Diabetiker in Maßen – sie enthalten schnell verfügbaren Zucker.",
            wiki = "Banane"
        ),
        Entry(
            listOf("Bread", "Toast", "Baguette", "Loaf"),
            "Brot", "Lebensmittel",
            "Ein Grundnahrungsmittel aus gebackenem Getreideteig.",
            "Hefe oder Sauerteig lassen den Teig gehen; im Ofen stärkehaltiges Getreide verbindet sich zur Krume mit Kruste.",
            "Beilage, Snack oder Hauptbestandteil – je nach Sorte.",
            "Brot in einem Leinenbeutel oder Brotkasten aufbewahren – im Kühlschrank wird es schneller altbacken.",
            listOf("Brot ist eines der ältesten verarbeiteten Lebensmittel – über 14.000 Jahre alt.", "Deutschland hat über 3.000 eingetragene Brotsorten."),
            "Nicht bei Schimmel verzehren – die Sporen durchziehen das ganze Brot."
        ),
        Entry(
            listOf("Cheese", "Cheddar", "Mozzarella", "Gouda"),
            "Käse", "Lebensmittel",
            "Ein Milchprodukt, das durch Gerinnen von Milch entsteht.",
            "Lab oder Säure trennen die Milch in feste Bestandteile (Bruch) und Flüssigkeit (Molke); der Bruch reift zum Käse.",
            "Als Brotbelag, in Saucen, auf Pizza – ein Grundnahrungsmittel vieler Küchen.",
            "Käse bei etwa 8-10 Grad lagern – dann entfaltet er das beste Aroma.",
            listOf("Frankreich produziert über 1.800 Käsesorten.", "Roquefort wird traditionell in Höhlen gereift."),
            "Rohmilchkäse in der Schwangerschaft vermeiden."
        ),
        Entry(
            listOf("Coffee", "Cappuccino", "Espresso", "Latte"),
            "Kaffee", "Lebensmittel",
            "Ein heißes Aufgussgetränk aus gerösteten Kaffeebohnen.",
            "Heißes Wasser löst Aromen und Koffein aus dem gemahlenen Röstgut – Brühverfahren bestimmen Geschmack und Stärke.",
            "Wachmacher, Kaffeekultur, soziales Getränk.",
            "Kaffeebohnen luftdicht und kühl lagern – gemahlen verliert Aroma in Tagen.",
            listOf("Nach Erdöl ist Kaffee das zweitwichtigste Handelsgut der Welt.", "Ein Barista-Espresso hat 25-30 ml und 9 bar Brückendruck."),
            "Koffein regt an – nach 16 Uhr lieber entkoffeiniert, sonst leidet der Schlaf."
        ),
        Entry(
            listOf("Egg", "Eggs"),
            "Ei", "Lebensmittel",
            "Eine hartschalige Kapsel mit Embryo-Nährlösung von Hühnern.",
            "Das Eigelb enthält Nährstoffe, das Eiweiß schützt den Embryo; beim Kochen gerinnen beide zu fester Struktur.",
            "Als Frühstück, für Kuchen, Saucen, Binden von Teigen.",
            "Frische Eier in Wasser sinken – schwimmende Eier sind alt und gehören in den Müll.",
            listOf("Ein Huhn legt rund 300 Eier pro Jahr.", "Die Schalenfarbe hängt von der Rute des Huhns ab – nicht vom Geschmack."),
            "Rohe Eier können Salmonellen enthalten – für Schwangere, Kinder und Kranke nur durcherhitzt."
        ),
        Entry(
            listOf("Tomato", "Vegetable"),
            "Tomate", "Lebensmittel",
            "Eine runde, rote Frucht (botanisch eine Beere), die meist als Gemüse genutzt wird.",
            "Tomaten reifen nach der Ernte nach – Ethylen und Wärme beschleunigen das.",
            "Roh im Salat, gekocht für Saucen, als Grundlage der mediterranen Küche.",
            "Tomaten niemals im Kühlschrank lagern – sie verlieren dort Aroma und Aromastoffe.",
            listOf("Die Tomate stammt aus Südamerika und kam erst im 16. Jahrhundert nach Europa.", "Tomaten enthalten den Pflanzenfarbstoff Lycopin – ein Antioxidans."),
            "Grüne Tomaten und Tomatenblätter enthalten Solanin – nicht mitessen."
        ),
        // ---------- Sport & Freizeit ----------
        Entry(
            listOf("Bicycle", "Bike", "Mountain bike", "Racing bicycle"),
            "Fahrrad", "Sport & Freizeit",
            "Ein zweirädriges, muskelbetriebenes Fahrzeug mit Rahmen, Rädern und Kette.",
            "Die Pedale drehen die Kette, die Kette das Hinterrad; Lenker und Gleichgewicht halten die Spur.",
            "Fortbewegung, Sport, Pendeln, Freizeit.",
            "Alle zwei Monate Reifendruck prüfen – zu wenig Luft kostet Kraft.",
            listOf("Das erste Fahrrad mit Pedalen stammt von 1817 – hieß „Laufmaschine“.", "Ein Radfahrer erzeugt bei gemäßigtem Tempo mehr Leistung pro Kilometer als ein Auto."),
            "Immer mit Helm fahren und Beleuchtung aktivieren – Radunfälle gehören zu den häufigsten Verkehrsverletzungen.",
            wiki = "Fahrrad"
        ),
        Entry(
            listOf("Football", "Soccer ball", "Ball", "Football ball"),
            "Fußball", "Sport & Freizeit",
            "Ein runder Spielball aus Leder oder Kunststoff mit Luftkammer.",
            "Der Ball springt durch die innere Luftkammer – die Dehnung der Hülle gibt ihm Form und Dynamik.",
            "Spielball für Fußball – auf jedem Platz, Rasen oder Bolzplatz.",
            "Den Ball nach dem Spiel trocknen lassen – Feuchtigkeit ruiniert die Naht.",
            listOf("Der offizielle WM-Ball hat einen Umfang von 68-70 cm.", "Ein Fußball kann im Profispiel bis zu 120 km/h schnell werden."),
            "Beim Kopfballtraining bei Jugendlichen Vorsicht walten lassen – regelmäßiges Kopfen kann das Gehirn belasten.",
            wiki = "Fußball"
        ),
        Entry(
            listOf("Tennis racket", "Racket", "Badminton racket"),
            "Schläger", "Sport & Freizeit",
            "Ein Sportgerät mit Griff, Rahmen und besaiteter Fläche zum Schlagen eines Balls.",
            "Die Besaitung gibt dem Ball Schwung mit – Trägheit und Kraftübertragung bestimmen Präzision.",
            "Für Tennis, Badminton, Squash und weitere Rückschlagspiele.",
            "Bespannung nach Bedarf erneuern – bei Nichtnutzung verliert sie Spannung.",
            listOf("Tennisbespannungen haben je nach Härte unterschiedliche Spielgefühle – Profis wechseln die Bespannung fast täglich.", "Die ersten Tennisschläger waren aus Holz – heute aus Carbon."),
            "Aufwärmen vor dem Spiel schützt vor Tennisarm."
        ),
        Entry(
            listOf("Book", "Paperback", "Hardcover"),
            "Buch", "Sport & Freizeit",
            "Ein gebundenes Werk aus bedruckten Seiten mit Einband.",
            "Buchstaben tragen Sprache in Schriftform; Seiten werden gebunden und durch Einband geschützt.",
            "Lesen, Lernen, Unterhaltung – Wissensspeicher über Jahrtausende.",
            "Bücher aufrecht in Regalen lagern – die billigste Form der Buchpflege.",
            listOf("Das älteste gedruckte Buch mit Datum ist das Diamant-Sutra aus dem Jahr 868.", "In Deutschland erscheinen jedes Jahr rund 70.000 neue Bücher."),
            "Bücher nicht bei Feuchtigkeit lagern – Schimmel zerstört Papier dauerhaft.",
            wiki = "Buch"
        ),
        Entry(
            listOf("Backpack", "Rucksack", "Bag", "Handbag"),
            "Rucksack / Tasche", "Sport & Freizeit",
            "Ein Behälter aus Stoff mit Trägern, der auf dem Rücken getragen wird.",
            "Zwei Gurte verteilen das Gewicht gleichmäßig auf beide Schultern – der Rücken trägt, nicht die Hände.",
            "Für Schule, Wandern, Reisen, Einkaufen.",
            "Schwere Rucksäcke nah am Körper und mit gepolstertem Hüftgurt tragen.",
            listOf("Der moderne Rucksack mit Hüftgurt stammt aus dem Alpinismus des 19. Jahrhunderts.", "Zehn Kilo im Rucksack können ohne Hüftgurt die Schultern drücken – mit Hüftgurt entfällt der Großteil auf die Hüfte."),
            "Nicht zu schwer packen – Rückenschmerzen sind die Folge; bei Schmerzen Gewicht reduzieren."
        ),
        // ---------- Kleidung ----------
        Entry(
            listOf("Shoe", "Footwear", "Sneaker", "Boot", "Sandals"),
            "Schuh", "Kleidung",
            "Eine Fußbekleidung aus Sohle und Oberteil.",
            "Die Sohle dämpft den Boden, das Oberteil hält den Fuß im Schuh und stützt das Fußgewölbe.",
            "Alltag, Sport, Arbeit – schützt und stützt den Fuß.",
            "Lederschuhe nach dem Tragen mit einem Spanner ausspannen – sie behalten ihre Form.",
            listOf("Die ältesten bekannten Schuhe sind rund 10.000 Jahre alt.", "Gute Laufschuhe haben eine Lebensdauer von etwa 600-800 Kilometern."),
            "Abgelaufene Schuhe können Gelenke belasten – Sohle regelmäßig prüfen."
        ),
        Entry(
            listOf("Sock"),
            "Socke", "Kleidung",
            "Ein schlauchförmiges Kleidungsstück für den Fuß.",
            "Elastisches Gewebe passt sich dem Fuß an und absorbiert Schweiß.",
            "Wärmeschutz, Sauberkeit, Komfort im Schuh.",
            "Socken aus Merinowolle bleiben länger geruchsneutral als Baumwolle.",
            listOf("Bis ins 20. Jahrhundert waren Strümpfe meist handgestrickt.", "Socken sind nachhaltig – gut gepflegt halten sie Jahre."),
            "Sauber und trocken tragen – nasse Socken begünstigen Fußpilz."
        ),
        Entry(
            listOf("Jacket", "Coat", "Raincoat", "Windbreaker"),
            "Jacke", "Kleidung",
            "Ein Kleidungsstück für den Oberkörper mit Ärmeln und Verschluss.",
            "Meist mehrlagige Materialien halten Wärme am Körper oder blocken Wind und Regen.",
            "Wärme-, Regen- und Sichtschutz in jeder Jahreszeit.",
            "Funktionsjacken mit Membran richtig waschen – zu viel Weichspüler zerstört die Imprägnierung.",
            listOf("Die Heizjacke ist eine der ältesten Kleidungsformen – Felljacken prägten die Menschheitsgeschichte.", "Gore-Tex wurde 1969 zufällig entdeckt."),
            "Zugestrickte Bänder bei Kinderjacken können klemmen – auf Sicherheitshinweise achten."
        ),
        Entry(
            listOf("Hat", "Cap", "Baseball cap"),
            "Hut / Cap", "Kleidung",
            "Eine Kopfbedeckung mit Krempe oder Schild.",
            "Das Material schirmt Sonne, Wind und Kälte vom Kopf ab.",
            "Sonnenschutz, Mode, Schutz bei Arbeit.",
            "Hüte mit Krempe halten mehr UV-Strahlung ab als Caps mit Schild – wichtig in der Sonne.",
            listOf("Der Hut war jahrhundertelang ein Statussymbol – Barhäuptigkeit galt als unhöflich.", "Die Baseball-Cap stammt aus den 1860ern – das Schild schützt die Augen beim Catcher-Spiel."),
            "Im Sommer schützen Hüte vor Sonnenstich – dunkle Hüte heizen stärker auf."
        ),
        Entry(
            listOf("T-shirt", "Tee", "Shirt", "Blouse", "Sweater"),
            "T-Shirt / Oberteil", "Kleidung",
            "Ein einfaches Oberbekleidungsstück mit Ärmeln und rundausschnitt.",
            "Gewirkte Baumwolle oder Mischgewebe formen den Körper-freundlichen Schnitt.",
            "Alltagskleidung – von Freizeit bis Business Casual.",
            "Baumwoll-Shirts bei 30 Grad waschen – hält Farbe und Passform.",
            listOf("Das T-Shirt wurde nach der T-Form benannt.", "Der älteste erhaltene Pullover der Welt ist über 1.700 Jahre alt."),
            "Reflektierende Streifen fehlen bei dunkler Kleidung im Straßenverkehr – helle Kleidung macht sichtbarer."
        ),
        // ---------- Verkehr ----------
        Entry(
            listOf("Car", "Vehicle", "Automobile", "Sedan", "SUV", "Van"),
            "Auto", "Verkehr",
            "Ein mehrspuriges Kraftfahrzeug mit eigenem Motor.",
            "Ein Verbrennungs- oder Elektromotor treibt die Räder an; Lenkung, Bremsen und Karosserie bieten Schutz und Steuerung.",
            "Transport von Personen und Gütern auf Straßen.",
            "Reifendruck monatlich prüfen – falscher Druck kostet Kraftstoff und Sicherheit.",
            listOf("Das erste Automobil baute Carl Benz 1885/86 – mit 0,75 PS.", "Moderne Autos haben über 100 Sensoren und Rechner an Bord."),
            "Autos sind bei Unfällen die häufigste Todesursache im Straßenverkehr – niemals abgelenkt fahren, Sicherheitsgurte immer anlegen. Diese Erkennung ist KEINE Bestätigung des Zustands.",
            critical = true
        ),
        Entry(
            listOf("Motorcycle", "Motorbike", "Scooter", "Moped"),
            "Motorrad", "Verkehr",
            "Ein einspuriges Kraftfahrzeug mit Motor und zwei Rädern.",
            "Das Vorderrad lenkt, der Motor treibt das Hinterrad – Balance und Fahrtechnik halten das Fahrzeug aufrecht.",
            "Fortbewegung, Freizeit, Sport – mit hoher Beschleunigung.",
            "Motorrad-Helm nach jedem Sturz prüfen – unsichtbare Schäden gefährden den Schutz.",
            listOf("Das erste Motorrad baute 1885 Gottlieb Daimler – mit 0,5 PS.", "Motorradfahrer sterben pro Kilometer deutlich häufiger als Autofahrer – Schutzkleidung senkt das Risiko erheblich."),
            "Motorradfahren ist deutlich gefährlicher als Autofahren: Helm, Schutzkleidung und defensive Fahrweise sind lebenswichtig. Diese Erkennung ist KEINE Bestätigung, dass das Fahrzeug sicher ist.",
            critical = true
        ),
        Entry(
            listOf("Helmet", "Motorcycle helmet", "Bicycle helmet", "Hard hat"),
            "Helm", "Verkehr",
            "Eine Schutzhülle für den Kopf aus harter Außenschale und stoßdämpfender Innenschicht.",
            "Bei einem Aufprall zerquetscht die Innenschicht kontrolliert und verlängert die Bremszeit des Kopfes – die Energie wird absorbiert.",
            "Schutz beim Motorradfahren, Radfahren, Arbeiten, Klettern.",
            "Helme nach jedem harten Aufprall ersetzen – auch ohne sichtbaren Schaden.",
            listOf("Radhelme senken das Risiko schwerer Kopfverletzungen um rund 70 %.", "Der Helm-Zulassungsprozess umfasst Tests bei minus 20 bis plus 50 Grad."),
            "Beschädigte Helme schützen nicht – nach einem Sturz unbedingt ersetzen."
        ),
        Entry(
            listOf("Traffic light", "Stop sign", "Street sign"),
            "Ampel / Verkehrszeichen", "Verkehr",
            "Eine Licht- oder Verkehrszeichenanlage, die Verkehrsflüsse regelt.",
            "Signale folgen einer festen Logik – Rot, Gelb, Grün – und koordinieren Kreuzungen sicher.",
            "Regelung des Straßenverkehrs – für alle Verkehrsteilnehmer.",
            "Auch bei Grün: erst schauen, dann losfahren – Rot-Brecher sind häufig.",
            listOf("Die erste Verkehrsampel der Welt stand 1868 in London – und explodierte nach kurzer Zeit.", "Deutschland hat über zwei Millionen Verkehrszeichen."),
            "Ampeln gelten für alle – auch Radfahrer und Fußgänger müssen sie beachten."
        ),
        Entry(
            listOf("Bus", "Coach"),
            "Bus", "Verkehr",
            "Ein großes Straßenfahrzeug für viele Personen.",
            "Ein Motor treibt das Fahrzeug, Lenkung und Bremsen sind auf hohes Gewicht ausgelegt – Busse fahren feste Linien.",
            "Öffentlicher Personennahverkehr, Schüler- und Reiseverkehr.",
            "Blinker beim Aussteigen beachten – Haltestellen sind Unfallschwerpunkte.",
            listOf("Der erste Linienbusverkehr startete 1895 in Deutschland – mit 6,5 km Strecke.", "Ein voller Reisebus ersetzt bis zu 50 Autos."),
            "Im Bus gut festhalten – abruptes Bremsen führt häufig zu Stürzen."
        ),
        Entry(
            listOf("Train", "Locomotive", "Railway"),
            "Zug", "Verkehr",
            "Ein schienengebundenes Verkehrsmittel für Personen oder Güter.",
            "Stahlräder laufen auf Stahlschienen mit minimaler Rollreibung – das macht Züge effizient und sparsam bei hoher Kapazität.",
            "Massenverkehr, Fernreisen, Gütertransport.",
            "Am Bahnsteig hinter der weißen Linie bleiben – Züge haben manchmal Überbreite.",
            listOf("Der erste deutsche Personenzug fuhr 1835 zwischen Nürnberg und Fürth – mit 6 km/h.", "Ein ICE wiegt leer rund 400 Tonnen."),
            "Bahnübergänge nie bei rotem Signal oder geschlossenen Schranken überqueren – Züge haben lange Bremswege."
        ),
        // ---------- Medizin ----------
        Entry(
            listOf("Pill", "Tablet", "Medicine", "Medication", "Capsule", "Drug"),
            "Tablette (Medikament)", "Medizin",
            "Ein fest gepresstes Arzneimittel zum Einnehmen.",
            "Der Wirkstoff wird im Körper freigesetzt – je nach Formulierung sofort oder über Stunden verteilt (retardiert).",
            "Behandlung von Krankheiten, Schmerzen oder Mangelerscheinungen – nur nach ärztlicher Anweisung.",
            "Tabletten immer in der Originalverpackung mit Beipackzettel aufbewahren.",
            listOf("Nur rund ein Drittel aller Menschen in Deutschland nimmt täglich Medikamente ein – die meisten davon regelmäßig.", "Retardtabletten dürfen nicht zerkaut werden – sonst wird der Wirkstoff zu schnell freigesetzt."),
            "NIEMALS Medikamente einnehmen, die nicht ausdrücklich für dich verschrieben sind – Dosierung und Wechselwirkungen können lebensgefährlich sein. Kinder undTabletten unbedingt getrennt aufbewahren, im Notfall Giftnotruf oder 112 wählen.",
            critical = true
        ),
        Entry(
            listOf("Syringe", "Injection", "Needle"),
            "Spritze / Kanüle", "Medizin",
            "Ein medizinisches Gerät zum Verabreichen oder Entnehmen von Flüssigkeiten durch die Haut.",
            "Eine hohle Nadel durchsticht die Haut; der Kolben drückt oder zieht Flüssigkeit durch die Kanüle.",
            "Impfungen, Blutabnahmen, Injektionen in der Medizin.",
            "Gebrauchte Kanülen gehören in einen Spritzenschutzbehälter – nie in den Hausmüll.",
            listOf("Einweg-Kanülen sind nach jedem Gebrauch stumpf und verkeimt.", "Die erste Spritze mit Gummikolben stammt aus dem Jahr 1845."),
            "Nadelstiche übertragen Krankheiten wie Hepatitis B und HIV – Kanülen nie wiederverwenden und nicht in die Haut stechen; im Notfall sofort ärztliche Hilfe suchen.",
            critical = true
        )
    )

    /**
     * Erzeugt eine Erklärung für die erkannten Labels – oder einen ehrlichen Fallback,
     * wenn kein Eintrag passt.
     */
    fun explain(labels: List<LabelConfidence>): Explanation {
        val sorted = labels.sortedByDescending { it.confidence }
        val entry = findEntry(sorted)
        return if (entry != null) {
            val topConfidence = sorted.firstOrNull()?.confidence ?: 0f
            toExplanation(entry, topConfidence)
        } else {
            fallbackExplanation(sorted)
        }
    }

    /** Sucht den passenden Eintrag – Labels mit höchster Konfidenz zuerst. */
    private fun findEntry(sorted: List<LabelConfidence>): Entry? {
        for (lc in sorted) {
            val hit = entries.firstOrNull { e ->
                e.labels.any { it.equals(lc.label, ignoreCase = true) }
            }
            if (hit != null) return hit
        }
        return null
    }

    /** Wandelt einen Eintrag in eine Explanation um – inkl. Ehrlichkeit über Unsicherheit. */
    private fun toExplanation(entry: Entry, topConfidence: Float): Explanation {
        val uncertain = entry.critical || topConfidence < 0.55f
        val title = if (uncertain) "Möglicherweise: " + entry.title else entry.title
        val safety = if (entry.critical) {
            entry.safety + "\n\nWICHTIG: Dies ist eine automatische Erkennung und KEINE Bestätigung " +
                "durch einen Experten. Verlasse dich bei sicherheitskritischen Objekten nie allein auf die App."
        } else {
            entry.safety
        }
        return Explanation(
            title = title,
            category = entry.category,
            summary = entry.summary,
            howItWorks = entry.howItWorks,
            usage = entry.usage,
            practical = entry.practical,
            facts = entry.facts,
            safety = safety,
            links = buildLinks(entry.title, entry.wiki),
            uncertain = uncertain,
            source = "lokal"
        )
    }

    /** Ehrlicher Fallback, wenn kein Wissensbasis-Eintrag passt. */
    private fun fallbackExplanation(sorted: List<LabelConfidence>): Explanation {
        val top = sorted.firstOrNull()
        val listed = sorted.take(3)
            .joinToString { it.label + " (" + (it.confidence * 100).toInt() + " %)" }
        return Explanation(
            title = if (top != null) "Unbekanntes Objekt (evtl. " + top.label + ")" else "Unbekanntes Objekt",
            category = "Allgemein",
            summary = "Die App konnte dieses Objekt nicht sicher ihrer Wissensbasis zuordnen. " +
                "Die Bilderkennung fand folgende Hinweise: " + listed + ".",
            howItWorks = "Ohne sichere Zuordnung kann die App keine verlässliche Funktionsbeschreibung geben.",
            usage = "Das Objekt konnte keinem bekannten Eintrag zugeordnet werden.",
            practical = "Versuche, das Objekt vor einem ruhigen Hintergrund, mit gutem Licht und aus der Nähe erneut zu fotografieren.",
            facts = emptyList(),
            safety = "Behandle alle Angaben mit Vorsicht – die Erkennung ist in diesem Fall nicht zuverlässig.",
            links = if (top != null) buildPublicLinks(top.label) else emptyList(),
            uncertain = true,
            source = "lokal"
        )
    }

    /** Öffentliche Links für externe Nutzer (z. B. Online-Analyse): nur echte Suchlinks. */
    fun buildPublicLinks(title: String): List<InfoLink> = buildLinks(title, null)

    /** Baut eine präzisere Amazon-Suchanfrage: räumt den Titel auf, damit die Treffer zum Thema passen. */
    private fun buildAmazonQuery(title: String): String {
        var q = title.trim()
        if (q.startsWith("Möglicherweise: ")) q = q.removePrefix("Möglicherweise: ")
        if (q.startsWith("Unbekanntes Objekt")) {
            val inner = Regex("\\((.*?)\\)").find(q)?.groupValues?.get(1) ?: ""
            q = inner.removePrefix("evtl. ").trim()
        }
        q = q.replace(Regex("\\(.*?\\)"), " ")
        q = q.replace('/', ' ')
        q = q.replace(Regex("\\s+"), " ").trim()
        if (q.isBlank()) q = title.trim()
        return q
    }

    /** Baut echte Weiterbildungslinks: Wikipedia (Artikel oder Suche), YouTube, DuckDuckGo, Amazon. */
    private fun buildLinks(title: String, wiki: String?): List<InfoLink> {
        val q = URLEncoder.encode(title, "UTF-8")
        val amazonQuery = buildAmazonQuery(title)
        val links = mutableListOf<InfoLink>()
        if (wiki != null) {
            links.add(
                InfoLink(
                    "Wikipedia: " + wiki.replace('_', ' '),
                    "https://de.wikipedia.org/wiki/" + wiki,
                    "wiki"
                )
            )
        } else {
            links.add(
                InfoLink(
                    "Wikipedia-Suche: " + title,
                    "https://de.wikipedia.org/wiki/Spezial:Suche?search=" + q,
                    "wiki"
                )
            )
        }
        links.add(InfoLink("YouTube-Suche: " + title, "https://www.youtube.com/results?search_query=" + q, "youtube-search"))
        links.add(InfoLink("DuckDuckGo-Suche: " + title, "https://duckduckgo.com/?q=" + q, "web"))
        links.add(InfoLink("Amazon-Suche: " + amazonQuery, "https://www.amazon.de/s?k=" + URLEncoder.encode(amazonQuery, "UTF-8"), "amazon-search"))
        return links
    }
}
