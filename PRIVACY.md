# Datenschutzerklärung für Cindy

**Stand: 14. September 2026**

## 1. Verantwortlicher

Kevin Raddatz
E-Mail: kevin@raddatz.me

## 2. Das Wichtigste zuerst

Cindy kommt ohne Server aus. Es gibt kein Nutzerkonto, keine Anmeldung und
keinen Dienst, an den die App Daten sendet. Die App fordert keinerlei
Netzwerkzugriff an. Alles, was sie erfasst, bleibt auf deinem Gerät – wenn du
es möchtest, zusätzlich in Apple Health (siehe Abschnitt 5). Sie
enthält keine Analyse-, Werbe- oder Tracking-Bibliotheken.

## 3. Die Kamera

Cindy zählt Wiederholungen, indem sie das Bild der Frontkamera auswertet.
Dafür fragt die App beim ersten Start um Erlaubnis.

Die Auswertung läuft **vollständig auf dem Gerät**, in Apples
Vision-Framework. Aus jedem Kamerabild wird eine einzige Zahl gewonnen – die
Größe deines Gesichts im Bild –, aus der die App Bewegung erkennt. Die Bilder
selbst werden **weder gespeichert noch übertragen**; sie existieren nur für
den Bruchteil einer Sekunde im Arbeitsspeicher, in dem sie verarbeitet werden.
Es entsteht keine Video- oder Fotodatei.

Die Erlaubnis kannst du in den Systemeinstellungen jederzeit widerrufen. Ohne
Kamerazugriff lässt sich die App nicht sinnvoll nutzen, da das Zählen ihre
einzige Funktion ist.

Rechtsgrundlage: Art. 6 Abs. 1 lit. a DSGVO (Einwilligung).

## 4. Daten auf deinem Gerät

Lokal gespeichert werden:

- dein Kalibrierungsprofil, also die gemessenen Schwellwerte deiner Bewegung
- dein Workout-Verlauf: Datum, Dauer, Runden, Wiederholungen
- dein angepasster Workout-Plan
- Einstellungen der App (Sprache, Erscheinungsbild)

Diese Daten liegen ausschließlich im Speicherbereich der App auf deinem Gerät.
Sie werden nicht synchronisiert und nicht gesichert übertragen; ich erhalte
davon nichts.

## 5. Apple Health

Auf Wunsch – abzuschalten und einzuschalten in den Einstellungen unter „Mit
Apple Health synchronisieren" – schreibt Cindy beendete Workouts in Apple
Health: Beginn und Dauer, die einzelnen Runden als Abschnitte sowie Score,
Rundenzahl und Plan als Zusatzangaben. Ist dein Körpergewicht in Health
hinterlegt, kommt eine geschätzte Aktivitätsenergie dazu; ohne Gewicht
schreibt die App keine Energie.

Gelesen werden genau vier Werte: dein **Körpergewicht** (für die
Energieschätzung) sowie **Schlafanalyse**, **Ruhepuls** und
**Herzfrequenzvariabilität** – ausschließlich für die Bereitschafts-Anzeige,
die dir sagt,
ob heute ein guter Trainingstag ist. Diese Auswertung passiert vollständig auf
dem Gerät; die Werte werden weder gespeichert noch weitergegeben. Alles andere
in Health bleibt für die App unsichtbar. Jeden Wert – geschrieben wie gelesen –
erlaubst du im Health-Dialog einzeln und kannst es jederzeit unter
„Einstellungen → Apps → Health → Datenzugriff" widerrufen. Ist der Schalter
aus, greift Cindy überhaupt nicht auf Health zu.

Apple Health liegt auf deinem Gerät. Wenn du in den Systemeinstellungen
iCloud für Health aktiviert hast, synchronisiert **Apple** diese Daten
zwischen deinen Geräten – das ist Apples Dienst, nicht meiner; ich habe
darauf keinen Zugriff. Cindy selbst sendet weiterhin nichts.

Gesundheitsdaten sind eine besondere Kategorie personenbezogener Daten.
Rechtsgrundlage ist deine ausdrückliche Einwilligung nach Art. 9 Abs. 2
lit. a DSGVO, die du im Health-Dialog erteilst.

## 6. Erinnerungen

Auf Wunsch erinnert dich Cindy an das nächste Training – einzuschalten in den
Einstellungen unter „Erinnere mich". Dafür fragt die App einmalig um Erlaubnis
für Mitteilungen.

Die Erinnerung ist eine **lokale Mitteilung**: Dein Gerät plant und zeigt sie
selbst. Es gibt keinen Push-Dienst, keinen Server und keine Geräte-Kennung, die
irgendwohin ginge. Wann sie kommt, rechnet die App auf dem Gerät aus – aus der
Härte deines letzten Workouts, deiner Bereitschafts-Schätzung und der Uhrzeit,
zu der du sonst trainierst.

Damit sich der Zeitpunkt auch dann noch verschieben kann, wenn du die App
gerade nicht öffnest, meldet sich Cindy gelegentlich im Hintergrund kurz zu
Wort (iOS „Hintergrundaktualisierung"). Dabei wird nur neu gerechnet; es wird
nichts übertragen. Du kannst das in den Systemeinstellungen abschalten.

Rechtsgrundlage: Art. 6 Abs. 1 lit. a DSGVO (Einwilligung).

## 7. Aufnahmemodus für die Fehlersuche

Die App kann auf Wunsch mitschreiben, was ihre Signalverarbeitung sieht –
zuschaltbar in den Einstellungen unter „Workouts mitschneiden", außerdem im
Diagnosebereich. Eine solche Aufnahme ist eine reine Zahlentabelle (CSV):
Zeitstempel, Signalwerte, Zustand des Zählers. Sie enthält **keine Bilder und
keinen Ton**.

Die Dateien bleiben auf deinem Gerät und werden dort aufgelistet, geteilt oder
gelöscht – du entscheidest, ob du eine davon weitergibst, etwa um mir einen
Zählfehler zu melden. Die App verschickt nichts von allein.

## 8. Ton

Cindy zählt hörbar mit und spricht Ansagen über die Sprachausgabe des
Systems. Sie greift dafür **nicht auf das Mikrofon zu** und fordert auch keine
Mikrofonberechtigung an.

## 9. Kein Tracking, keine Analyse, keine Werbung

Cindy enthält keine Analyse- oder Werbe-SDKs, keinen Absturzmelder eines
Drittanbieters und keine Werbe-Identifikatoren. Es findet kein Tracking im
Sinne des App-Tracking-Transparency-Rahmens statt, weder innerhalb der App
noch über andere Apps oder Websites hinweg.

## 10. Berichte über Apple

Wenn du in den Systemeinstellungen deines Geräts zugestimmt hast, Diagnose-
und Nutzungsdaten mit App-Entwicklern zu teilen, stellt Apple mir
Absturzberichte und aggregierte Nutzungsstatistiken bereit. Diese Daten
erhalte ich ausschließlich in der von Apple aufbereiteten Form; sie enthalten
keine Trainingsdaten und lassen keinen Rückschluss auf einzelne Personen zu.
Die Zustimmung kannst du jederzeit unter „Einstellungen → Datenschutz &
Sicherheit → Analyse & Verbesserungen" widerrufen.

## 11. Speicherdauer und Löschen

Da ich keine Daten von dir erhalte, speichere ich auch nichts. Deine Daten
löschst du selbst: Die App zu löschen entfernt alles, was sie angelegt hat –
Kalibrierung, Verlauf, Plan, Einstellungen und etwaige Aufnahmen. Einzelne
Aufnahmen lassen sich in den Einstellungen der App entfernen.

Was bereits in Apple Health geschrieben wurde, gehört ab dann Health und
bleibt auch nach dem Löschen der App erhalten. Diese Einträge entfernst du in
der Health-App selbst (unter „Durchsuchen → Aktivität → Trainings" oder über
„Quellen → Cindy").

## 12. Deine Rechte

Dir stehen nach der DSGVO die Rechte auf Auskunft (Art. 15), Berichtigung
(Art. 16), Löschung (Art. 17), Einschränkung der Verarbeitung (Art. 18),
Datenübertragbarkeit (Art. 20) und Widerspruch (Art. 21) zu, ebenso das Recht,
eine erteilte Einwilligung jederzeit zu widerrufen.

In der Praxis läuft eine Auskunftsanfrage an mich ins Leere, weil bei mir
keine personenbezogenen Daten über dich vorliegen. Melde dich trotzdem gern
unter kevin@raddatz.me, wenn du Fragen hast.

Du kannst dich außerdem bei einer Datenschutz-Aufsichtsbehörde beschweren.
Zuständig ist die Behörde deines Wohnsitzes.

## 13. Kinder

Cindy richtet sich nicht gezielt an Kinder und erhebt wissentlich keine Daten
von Kindern – die App erhebt überhaupt keine Daten.

## 14. Änderungen dieser Erklärung

Ändert sich die Funktionsweise der App, passe ich diese Erklärung an. Die
jeweils gültige Fassung findest du unter der Adresse, die im App Store bei
Cindy hinterlegt ist; das Datum oben nennt den Stand.
