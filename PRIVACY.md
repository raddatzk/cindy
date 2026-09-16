# Privacy Policy · Datenschutzerklärung — Cindy

[English](#english) · [Deutsch](#deutsch)

## English

**Last updated: September 16, 2026**

The English and the German version say the same thing. Where they differ in a
point of law, the German version applies.

### 1. Controller

Kevin Raddatz
Email: cindy@raddatz.me

### 2. The short version

Cindy is available for iPhone and for Android. This policy covers both; where
they differ, the section says so.

Cindy has no server. There is no user account, no sign-in and no service the
app sends data to. The app requests no network access of any kind – on Android
it does not even hold the internet permission. Everything it records stays on
your device – and, if you choose, in Apple Health or Health Connect as well
(see section 5). It contains no analytics, advertising or tracking libraries.

### 3. The camera

Cindy counts reps by analysing the picture from the front camera. The app asks
for permission for this the first time it starts.

The analysis runs **entirely on the device** – on iPhone in Apple's Vision
framework, on Android in Google's open-source MediaPipe library, whose models
ship inside the app. Each camera frame is reduced to a few numbers – how large
your face appears, where your shoulders are, how bright the picture is – from
which the app detects movement. The frames themselves are
**neither stored nor transmitted**; they exist only for the fraction of a second
in memory in which they are processed. No video or photo file is created.

You can revoke the permission in the system settings at any time. Without camera
access the app cannot be used in any meaningful way, because counting is its only
function.

Legal basis: Art. 6(1)(a) GDPR (consent).

### 4. Data on your device

Stored locally:

- your calibration profile, i.e. the measured thresholds of your movement
- your workout history: date, duration, rounds, reps
- your customised workout plan
- app settings (language, appearance)

This data lives exclusively in the app's own storage on your device. It is not
synchronised and not transmitted; I receive none of it.

### 5. Apple Health and Health Connect

On iPhone this is Apple Health, on Android Health Connect. Both work the same
way for Cindy; "Health" below means whichever your device uses.

If you wish – turned on and off in the app's settings under "Sync to Apple
Health" or "Sync to Health Connect" – Cindy writes finished workouts to Health: start and duration,
the individual rounds as segments, and score, round count and plan as additional
details. If your body weight is recorded in Health, an estimated active energy
is added; without a weight the app writes no energy. On Android the details
are stored in the workout's notes, as Health Connect has no separate field for
them.

Exactly four values are read: your **body weight** (for the energy estimate) and
**sleep analysis**, **resting heart rate** and **heart rate variability** – only
for the readiness display, which tells you whether today is a good day to train.
This evaluation happens entirely on the device; the values are neither stored
nor shared. Everything else in Health stays invisible to the app. You allow each
value – written or read – individually in the Health dialog, and can revoke it
at any time: on iPhone under "Settings → Apps → Health → Data Access", on
Android in Health Connect under "App permissions". On Android Cindy also asks
to read in the background, so the reminder (section 6) can take your latest
values into account, and to read data older than 30 days, for your personal
baseline. Both are optional. With the switch off, Cindy does not access Health
at all.

Apple Health and Health Connect live on your device. If you have turned on
iCloud for Health, **Apple** synchronises this data between your devices; if
another app you use syncs Health Connect, that app does. That is their service,
not mine; I have no access to it. Cindy itself still sends nothing.

Health data is a special category of personal data. The legal basis is your
explicit consent under Art. 9(2)(a) GDPR, which you give in the Health dialog.

### 6. Reminders

If you wish, Cindy reminds you of your next workout – turned on in the app's
settings under "Remind me". For this the app asks once for permission to send
notifications.

The reminder is a **local notification**: your device schedules and shows it
itself. There is no push service, no server and no device identifier sent
anywhere. When it arrives is calculated by the app on the device – from how hard
your last workout was, your readiness estimate and the time of day you usually
train.

So that the time can still move when you are not opening the app, Cindy
occasionally wakes briefly in the background (iOS "Background App Refresh", on
Android a scheduled background task). This only recalculates; nothing is
transmitted. You can turn this off in the
system settings.

Legal basis: Art. 6(1)(a) GDPR (consent).

### 7. Recording mode for troubleshooting

If you wish, the app can record what its signal processing sees – turned on in
the app's settings under "Record workouts", and also on the diagnostics screen.
Such a recording is a plain table of numbers (CSV): timestamps, signal values,
the state of the counter. It contains **no images and no sound**.

The files stay on your device, where they are listed, shared or deleted – you
decide whether to pass one on, for example to report a counting error to me. The
app sends nothing on its own.

### 8. Sound

Cindy counts audibly with short beeps. It does **not access the microphone** and does not request microphone
permission.

### 9. No tracking, no analytics, no advertising

Cindy contains no analytics or advertising SDKs, no third-party crash reporter
and no advertising identifiers. There is no tracking, neither within the app nor
across other apps or websites – on iPhone in the sense of the App Tracking
Transparency framework as well.

The Android app deliberately does without Google Play services and does not
hold the internet permission, so the libraries it contains cannot send
diagnostics either.

### 10. Reports via Apple and Google

If you have agreed in your device's system settings to share diagnostics and
usage data with app developers, Apple provides me with crash reports and
aggregated usage statistics. I receive this data only in the form prepared by
Apple; it contains no workout data and does not allow conclusions about
individual people. You can withdraw this consent at any time under "Settings →
Privacy & Security → Analytics & Improvements".

On Android the same applies to Google: if you share usage and diagnostics
with Google, Google Play gives me crash reports and aggregated statistics
about the app, prepared by Google and without workout data. You change this in
your device's Google settings under "Usage & diagnostics".

### 11. Retention and deletion

Since I receive no data from you, I store nothing either. You delete your data
yourself: deleting the app removes everything it created – calibration,
history, plan, settings and any recordings. Individual recordings can be removed
in the app's settings.

Whatever has already been written to Apple Health or Health Connect belongs to
Health from then on and remains after the app is deleted. You remove those
entries there: in the Health app under "Browse → Activity → Workouts" or via
"Sources → Cindy", in Health Connect under "App permissions → Cindy → Delete
app data".

### 12. Your rights

Under the GDPR you have the right of access (Art. 15), rectification (Art. 16),
erasure (Art. 17), restriction of processing (Art. 18), data portability
(Art. 20) and objection (Art. 21), as well as the right to withdraw consent you
have given at any time.

In practice a request for access to me comes to nothing, because I hold no
personal data about you. Please get in touch at cindy@raddatz.me anyway if you
have questions.

You can also lodge a complaint with a data protection supervisory authority.
The authority where you live is responsible.

### 13. Children

Cindy is not directed at children and does not knowingly collect data from
children – the app collects no data at all.

### 14. Changes to this policy

If the way the app works changes, I update this policy. The version in force is
always at the address given for Cindy in the App Store and on Google Play; the
date above states when it was last updated.

---

## Deutsch

**Stand: 16. September 2026**

### 1. Verantwortlicher

Kevin Raddatz
E-Mail: cindy@raddatz.me

### 2. Das Wichtigste zuerst

Cindy gibt es für das iPhone und für Android. Diese Erklärung gilt für beide;
wo sie sich unterscheiden, steht es im jeweiligen Abschnitt.

Cindy kommt ohne Server aus. Es gibt kein Nutzerkonto, keine Anmeldung und
keinen Dienst, an den die App Daten sendet. Die App fordert keinerlei
Netzwerkzugriff an – unter Android besitzt sie nicht einmal die
Internet-Berechtigung. Alles, was sie erfasst, bleibt auf deinem Gerät – wenn
du es möchtest, zusätzlich in Apple Health oder Health Connect (siehe
Abschnitt 5). Sie enthält keine Analyse-, Werbe- oder Tracking-Bibliotheken.

### 3. Die Kamera

Cindy zählt Wiederholungen, indem sie das Bild der Frontkamera auswertet.
Dafür fragt die App beim ersten Start um Erlaubnis.

Die Auswertung läuft **vollständig auf dem Gerät** – auf dem iPhone in Apples
Vision-Framework, unter Android in Googles quelloffener Bibliothek MediaPipe,
deren Modelle in der App mitgeliefert werden. Aus jedem Kamerabild werden
wenige Zahlen gewonnen – wie groß dein Gesicht erscheint, wo deine Schultern
sind, wie hell das Bild ist –, aus denen die App Bewegung erkennt. Die Bilder
selbst werden **weder gespeichert noch übertragen**; sie existieren nur für
den Bruchteil einer Sekunde im Arbeitsspeicher, in dem sie verarbeitet werden.
Es entsteht keine Video- oder Fotodatei.

Die Erlaubnis kannst du in den Systemeinstellungen jederzeit widerrufen. Ohne
Kamerazugriff lässt sich die App nicht sinnvoll nutzen, da das Zählen ihre
einzige Funktion ist.

Rechtsgrundlage: Art. 6 Abs. 1 lit. a DSGVO (Einwilligung).

### 4. Daten auf deinem Gerät

Lokal gespeichert werden:

- dein Kalibrierungsprofil, also die gemessenen Schwellwerte deiner Bewegung
- dein Workout-Verlauf: Datum, Dauer, Runden, Wiederholungen
- dein angepasster Workout-Plan
- Einstellungen der App (Sprache, Erscheinungsbild)

Diese Daten liegen ausschließlich im Speicherbereich der App auf deinem Gerät.
Sie werden nicht synchronisiert und nicht gesichert übertragen; ich erhalte
davon nichts.

### 5. Apple Health und Health Connect

Auf dem iPhone ist das Apple Health, unter Android Health Connect. Für Cindy
funktionieren beide gleich; „Health" meint unten das, was dein Gerät verwendet.

Auf Wunsch – abzuschalten und einzuschalten in den Einstellungen unter „Mit
Apple Health synchronisieren" bzw. „Mit Health Connect synchronisieren" –
schreibt Cindy beendete Workouts in Health: Beginn und Dauer, die einzelnen Runden als Abschnitte sowie Score,
Rundenzahl und Plan als Zusatzangaben. Ist dein Körpergewicht in Health
hinterlegt, kommt eine geschätzte Aktivitätsenergie dazu; ohne Gewicht
schreibt die App keine Energie. Unter Android stehen die Zusatzangaben in den
Notizen des Workouts, weil Health Connect dafür kein eigenes Feld hat.

Gelesen werden genau vier Werte: dein **Körpergewicht** (für die
Energieschätzung) sowie **Schlafanalyse**, **Ruhepuls** und
**Herzfrequenzvariabilität** – ausschließlich für die Bereitschafts-Anzeige,
die dir sagt,
ob heute ein guter Trainingstag ist. Diese Auswertung passiert vollständig auf
dem Gerät; die Werte werden weder gespeichert noch weitergegeben. Alles andere
in Health bleibt für die App unsichtbar. Jeden Wert – geschrieben wie gelesen –
erlaubst du im Health-Dialog einzeln und kannst es jederzeit widerrufen: auf
dem iPhone unter „Einstellungen → Apps → Health → Datenzugriff", unter Android
in Health Connect unter „App-Berechtigungen". Unter Android fragt Cindy
zusätzlich, ob sie im Hintergrund lesen darf, damit die Erinnerung
(Abschnitt 6) deine aktuellen Werte berücksichtigen kann, und ob sie Daten
lesen darf, die älter als 30 Tage sind, für deinen persönlichen Vergleichswert.
Beides ist freiwillig. Ist der Schalter aus, greift Cindy überhaupt nicht auf
Health zu.

Apple Health und Health Connect liegen auf deinem Gerät. Wenn du iCloud für
Health aktiviert hast, synchronisiert **Apple** diese Daten zwischen deinen
Geräten; synchronisiert eine andere App, die du nutzt, Health Connect, tut das
diese App. Das ist deren Dienst, nicht meiner; ich habe darauf keinen Zugriff.
Cindy selbst sendet weiterhin nichts.

Gesundheitsdaten sind eine besondere Kategorie personenbezogener Daten.
Rechtsgrundlage ist deine ausdrückliche Einwilligung nach Art. 9 Abs. 2
lit. a DSGVO, die du im Health-Dialog erteilst.

### 6. Erinnerungen

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
Wort (iOS „Hintergrundaktualisierung", unter Android eine geplante
Hintergrundaufgabe). Dabei wird nur neu gerechnet; es wird nichts übertragen. Du kannst das in den Systemeinstellungen abschalten.

Rechtsgrundlage: Art. 6 Abs. 1 lit. a DSGVO (Einwilligung).

### 7. Aufnahmemodus für die Fehlersuche

Die App kann auf Wunsch mitschreiben, was ihre Signalverarbeitung sieht –
zuschaltbar in den Einstellungen unter „Workouts mitschneiden", außerdem im
Diagnosebereich. Eine solche Aufnahme ist eine reine Zahlentabelle (CSV):
Zeitstempel, Signalwerte, Zustand des Zählers. Sie enthält **keine Bilder und
keinen Ton**.

Die Dateien bleiben auf deinem Gerät und werden dort aufgelistet, geteilt oder
gelöscht – du entscheidest, ob du eine davon weitergibst, etwa um mir einen
Zählfehler zu melden. Die App verschickt nichts von allein.

### 8. Ton

Cindy zählt mit kurzen Pieptönen hörbar mit. Sie greift dafür **nicht auf das Mikrofon zu** und fordert auch keine
Mikrofonberechtigung an.

### 9. Kein Tracking, keine Analyse, keine Werbung

Cindy enthält keine Analyse- oder Werbe-SDKs, keinen Absturzmelder eines
Drittanbieters und keine Werbe-Identifikatoren. Es findet kein Tracking statt,
weder innerhalb der App noch über andere Apps oder Websites hinweg – auf dem
iPhone auch nicht im Sinne des App-Tracking-Transparency-Rahmens.

Die Android-App verzichtet bewusst auf die Google-Play-Dienste und besitzt
keine Internet-Berechtigung, sodass auch die enthaltenen Bibliotheken keine
Diagnosedaten versenden können.

### 10. Berichte über Apple und Google

Wenn du in den Systemeinstellungen deines Geräts zugestimmt hast, Diagnose-
und Nutzungsdaten mit App-Entwicklern zu teilen, stellt Apple mir
Absturzberichte und aggregierte Nutzungsstatistiken bereit. Diese Daten
erhalte ich ausschließlich in der von Apple aufbereiteten Form; sie enthalten
keine Trainingsdaten und lassen keinen Rückschluss auf einzelne Personen zu.
Die Zustimmung kannst du jederzeit unter „Einstellungen → Datenschutz &
Sicherheit → Analyse & Verbesserungen" widerrufen.

Unter Android gilt dasselbe für Google: Teilst du Nutzungs- und Diagnosedaten
mit Google, stellt mir Google Play Absturzberichte und aggregierte Statistiken
zur App bereit, von Google aufbereitet und ohne Trainingsdaten. Das änderst du
in den Google-Einstellungen deines Geräts unter „Nutzung und Diagnose".

### 11. Speicherdauer und Löschen

Da ich keine Daten von dir erhalte, speichere ich auch nichts. Deine Daten
löschst du selbst: Die App zu löschen entfernt alles, was sie angelegt hat –
Kalibrierung, Verlauf, Plan, Einstellungen und etwaige Aufnahmen. Einzelne
Aufnahmen lassen sich in den Einstellungen der App entfernen.

Was bereits in Apple Health oder Health Connect geschrieben wurde, gehört ab
dann Health und bleibt auch nach dem Löschen der App erhalten. Diese Einträge
entfernst du dort: in der Health-App unter „Durchsuchen → Aktivität →
Trainings" oder über „Quellen → Cindy", in Health Connect unter
„App-Berechtigungen → Cindy → App-Daten löschen".

### 12. Deine Rechte

Dir stehen nach der DSGVO die Rechte auf Auskunft (Art. 15), Berichtigung
(Art. 16), Löschung (Art. 17), Einschränkung der Verarbeitung (Art. 18),
Datenübertragbarkeit (Art. 20) und Widerspruch (Art. 21) zu, ebenso das Recht,
eine erteilte Einwilligung jederzeit zu widerrufen.

In der Praxis läuft eine Auskunftsanfrage an mich ins Leere, weil bei mir
keine personenbezogenen Daten über dich vorliegen. Melde dich trotzdem gern
unter cindy@raddatz.me, wenn du Fragen hast.

Du kannst dich außerdem bei einer Datenschutz-Aufsichtsbehörde beschweren.
Zuständig ist die Behörde deines Wohnsitzes.

### 13. Kinder

Cindy richtet sich nicht gezielt an Kinder und erhebt wissentlich keine Daten
von Kindern – die App erhebt überhaupt keine Daten.

### 14. Änderungen dieser Erklärung

Ändert sich die Funktionsweise der App, passe ich diese Erklärung an. Die
jeweils gültige Fassung findest du unter der Adresse, die im App Store und bei
Google Play für Cindy hinterlegt ist; das Datum oben nennt den Stand.
