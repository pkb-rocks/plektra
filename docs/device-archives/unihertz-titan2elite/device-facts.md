[English](device-facts.en.md) | Deutsch

# Unihertz Titan 2 Elite: Gerätefakten für Plektra-Entwickler

Diese Seite sammelt konkrete Fakten zum Unihertz Titan 2 Elite, die beim Testen von Plektra an einem echten Gerät beobachtet wurden. Sie ergänzt das [README](README.md) dieses Verzeichnisses, das die Displaygeometrie und die Geräteerkennung beschreibt.

## Herkunft

- Alle Beobachtungen stammen von einem Serien-Titan-2-Elite mit QWERTY-Layout und wurden am 14.11.2026 mit logcat, `getevent -lt`, `dumpsys`, `getprop` und `settings` sowie einer kleinen Key-Probe-Testapp und dem Debug-Logging der App gemacht.
- Der Firmwarestand wurde zu diesen Beobachtungen nicht festgehalten. Das README nennt `Titan 2 Elite_V02.00.04` für ein final ausgeliefertes Gerät; diese Seite geht von einem Stand dieser Generation aus, bestätigt es aber nicht. Herstellerverhalten kann sich mit der Firmware ändern, deshalb gilt jeder Fakt als „an diesem Gerät an diesem Datum gesehen“.
- Zu jedem Fakt steht, wie er ermittelt wurde. „Abgeleitet“ heißt: aus gepaarten Beobachtungen geschlossen, nicht aus einer Spezifikation gelesen.

## 1. Physische Tasten und Eingabegeräte

1. Tastenkappen und Scancodes (Kernel-Scancodes laut `getevent -lt`, 14.11.2026):

   | Aufdruck | Position | Scancode | Hinweise |
   | --- | --- | --- | --- |
   | Enter (⏎) | | 28 | |
   | Shift (⇧), rechts | rechte Seite der Tastatur | 54 | |
   | Shift (⇧), links | untere linke Ecke | 42 | |
   | Fn | | 251 | `FUNC3` |
   | Sym | | 253 | `AGUI_SYM` |
   | ○ (Kreis) | | 102 | `KEY_HOME`; die Herstellerschicht sieht Keycode 407, siehe Abschnitt 2.6 |
   | Zurück | | 158 | die aufgedruckten Tasten △ und □ sind üblicherweise Zurück und Zuletzt verwendet; nicht Taste für Taste geprüft |
   | Zuletzt verwendet (Recent) | | 580 | kommt roh als `KEY_APPSELECT`; die Herstellerschicht macht oberhalb des Kernels daraus Tab (Keycode 61), die Taste liegt nicht am Tastaturgerät |
   | Orange Seitentaste | Gehäuseseite | 249 | Eventgerät `ff_key`; die Herstellereinstellungen nennen sie „Func1“ |
   | Lautstärke | | 115 / 114 | `gpio-keys` |
   | Power | | 116 | `ff_key` |

2. Eingabegeräte (`getevent -i`): event5 „TitanKey“ (die Tasten), event4 „touchPad“ (die Berührungsfläche der Tastatur), event6 „fts_ts“ (der Bildschirm), event7 „ff_key“, event0 „gpio-keys“ (Lautstärke), event1 „mtk-pmic-keys“, event3 „hall_sensor“. Ein auf den Tasten ruhender Finger erzeugt `BTN_TOUCH` auf dem touchPad-Gerät.
3. Die Leertaste erscheint in `getevent` nie auf dem Tastatur-Eingabegerät. Sie ist zugleich der Fingerabdrucksensor (Herstellerpaket `com.agui.spacebarkey`). Außerhalb eines Textfelds verließ ein Druck darauf die Vordergrund-App (Rechner) und zeigte die Launcher-Seite, von der die App gestartet worden war; das ist mehr als „Zurück“ tut. In Tests außerhalb von Textfeldern die Leertaste nicht als Steuertaste verwenden.
4. Synthetische Touches. Während einer längeren Tastenfolge wurde `adb shell input tap` auf dem Bildschirm manchmal nicht registriert (drei Fehlversuche), während das touchPad-Gerät Berührungen der aufliegenden Finger meldete. Die Finger zwischen den Tastendrücken abzuheben und es erneut zu versuchen (bis zu 15-mal im Abstand von 0,5 s) half (18 von 18 Taps). Die Ursache wurde nicht belegt.
5. Zeitstempel. `getevent -lt` gibt `CLOCK_MONOTONIC`-Zeit aus. Eine Umrechnung mit `/proc/uptime` (`CLOCK_BOOTTIME`) wich an diesem Gerät um rund 11 s ab. Um Tastenereignisse mit anderen Logereignissen abzugleichen, stattdessen die `InputReader`-Zeilen der Herstellerschicht im logcat verwenden.

## 2. Herstellerschicht für Tasten (`com.agui.shortcutsettings`)

Die Herstellereinstellungen (Einstellungen → Shortcut keys → Key setting) schreiben Tasten in der Firmware um, bevor eine App oder der IME sie sieht.

1. Ablageort. Was eine Taste jetzt tippt, steht in der Systemproperty `persist.sys.agui_programmable_<key>_key` (mit `getprop` lesbar; der Wert ist ein Android-Keycode). Die im Menü gewählte Funktion steht in `Settings.System` als `<key>_programmable_key_function` (manche Tasten haben zusätzlich `_enable`). Eine Taste ohne diese Property ist vermutlich nativ; das wurde an keiner unveränderten Werkstaste geprüft.
2. Änderungen wirken sofort. Wird eine Taste im Herstellerbildschirm geändert, aktualisieren sich Property und `_function`-Wert sofort, ohne Neustart (jeweils eine Taste nach der anderen mit `getprop` geprüft, 14.11.2026).
3. Funktionscodes (abgeleitet am 14.11.2026, indem der `_function`-Wert jeder Taste mit dem getippten Keycode gepaart wurde):

   | `_function` | Menüeintrag | getippter Keycode |
   | --- | --- | --- |
   | 1 | Ctrl key | 113 `CTRL_LEFT` |
   | 2 | Sym key | 63 `SYM` |
   | 3 | Back key | nicht festgehalten |
   | 4 | Media key | 85 `MEDIA_PLAY_PAUSE` |
   | 5 | Tab key | 61 `TAB` |
   | 6 | Home key | nicht festgehalten |
   | 7 | Shift key | 59 `SHIFT_LEFT` |
   | 8 | Alt key | 58 `ALT_RIGHT` |

   Weitere Menüeinträge, deren Codes unbekannt sind: None, Magic key, Meta key, Power key, Comma, Period, Number 0, Letter key. Die Liste stammt aus den `edit_key_*`-Strings der Hersteller-APK (`aapt2 dump`).
4. Die zugewiesene Funktion bestimmt den Keycode, nicht die physische Taste. „Alt key“ tippt immer `ALT_RIGHT` (58) und „Shift key“ immer `SHIFT_LEFT` (59), gleich welcher physischen Taste sie zugewiesen sind. Insbesondere kommt die rechte Shift-Taste, wenn ihr „Shift key“ zugewiesen ist, als `KEYCODE_SHIFT_LEFT` mit Scancode 54 an. Der Scancode übersteht die Umschreibung, eine App, die die Tasten unterscheiden muss, kann also darauf prüfen.
5. Tasten, die der Herstellerbildschirm auflistet: Left shift, Right shift, Left alt, Right alt (der String existiert), Fn, Sym, Func1, Func2, Home, Back, Recent, Space, PTT, Camping lamp, Subscreen control. Nicht alle davon gibt es an diesem Gerät.
6. Home (○) läuft über die Shortcut-Schicht des Herstellers (`A85ShortcutFunction` in `com.agui.shortcutsettings`), nicht direkt zu Android. Ein kurzer Druck ist Home, ein langer Druck (etwa 0,5 s) ist die Übersicht (`KEYCODE_APP_SWITCH`), ein Doppeldruck ist Zurück. Wegen des Doppeldruck-Fensters wird Home erst injiziert, wenn es sich schließt, also etwa 0,5 bis 0,7 s nach dem Loslassen von ○ (beobachtet am 14.11.2026).
7. Praktische Folge für Plektra: Die Herstellerschicht bestimmt, was der IME empfängt. Der Tutorial-Hinweis des READMEs, Fn als Ctrl wirken zu lassen, wird in dieser Schicht umgesetzt („Ctrl key“, Funktion 1), nicht in der App.

## 3. Tastaturbeleuchtung und Konfigurationsänderungen

Das ist das überraschendste am Gerät gefundene Verhalten, und es betrifft jeden Test, der vom Fokus abhängt.

1. **Eine Taste, die die Tastaturbeleuchtung weckt, ändert die Konfiguration und startet die Vordergrund-Activity neu.** Belege aus logcat (main, system, events) für einen Druck auf die rechte Shift-Taste im Dialog „Choose theme“ des Rechners:
   1. `InputReader` loggt `aguiSetProgrammableKey` für Scancode 54 (umgeschrieben auf 59).
   2. 9 ms später `KeyboardLightController: pressKeyToPowerOn`.
   3. 30 ms danach `ActivityTaskManager: Config changes=30` (`keyboard | keyboardHidden`).
   4. Dann `wm_relaunch_resume_activity … Calculator,30`.
2. Die neu gestartete Activity baut ihren Dialog neu auf, der Fokus springt daher auf das Standardelement des Dialogs zurück. Eine App, die `configChanges="keyboard|keyboardHidden"` nicht deklariert, verliert so Fokus- und Dialogzustand. Ein IME sieht dasselbe Ereignis als `onConfigurationChanged` mit Diff 0x30. Das Hinzufügen eines Eingabegeräts (zum Beispiel einer uinput-Testtastatur) bewirkt dasselbe.
3. Es funktioniert auch in die andere Richtung. Auf einen Druck der Recent-Taste (Herstellerbelegung Tab, Scancode 580, nicht am Tastaturgerät) folgte innerhalb von 40 ms `Config changes=30` auf `-keyb/v/h` (Tastatur NOKEYS, Hardware-Tastatur verborgen) und ein weiterer Neustart. Der nächste Druck auf die rechte Shift-Taste schaltete zurück auf `qwerty/v/v` und startete erneut neu. Wechselt man Recent-Taste und Tastaturtasten ab, kann die App also bei jedem Druck neu starten. Die Tastatur vorher zu wecken verhindert das nicht.
4. Ein erster Test im Standard-Rechner sah wie ein Fokusfehler aus: Bei fokussiertem „Cancel“ (mit Tab erreicht) setzten rechte Shift-Taste und Enter den Fokus jeweils auf das erste Element zurück, statt „Cancel“ auszulösen. Der IME war nicht beteiligt (`dumpsys input_method`: `mServedView=null`, `mInputShown=false`). Ursache war der oben beschriebene Neustart.
5. Hinweis zum Testen. Tests des Tastaturfokus brauchen eine App, die `keyboard|keyboardHidden` selbst behandelt. `dumpsys package` zeigt `configChanges` an diesem Gerät nicht an: die APK ziehen (`adb pull` vom Pfad aus `pm path <pkg>`), `android:configChanges` mit `aapt2 dump xmltree --file AndroidManifest.xml` lesen und prüfen, dass die Bits 0x30 gesetzt sind. Ein Drittanbieter-Editor, dessen Hauptactivity `configChanges` 0xdb0 deklariert (beide Bits enthalten), sieht die `Config changes=30`-Wechsel bei Recent und bei beleuchtungsweckenden Drücken weiterhin, wird aber nicht neu gestartet, nur der Launcher. Die ausprobierten Standard-Apps (Rechner, Keep, Uhr, Kontakte, Telefon, Dateien) hatten keine konfigurationssichere Activity; die Standard-Einstellungen haben einige (Startseite, WLAN- und Netzwerkdialoge). Der Dialogtest in Abschnitt 4.1 wurde auf diese Weise gemacht.

## 4. Verhalten von IME und Editoren

1. **Dialoge ohne Textfeld umgehen den IME.** Ein AlertDialog ohne Texteingabe-View setzt `FLAG_ALT_FOCUSABLE_IM` (AOSP `AlertController`), sein Fenster hat also keinen IME-Fokus. `ImeFocusController` reicht Tasten dann direkt an die App durch (`dumpsys input_method` zeigt `mServedView=null`; die dort aufgeführte `fallbackInputConnection` ist nur der registrierte Fallback des Clients, keine IME-Sitzung). Das ist Android-Design, keine Geräteeinstellung. Beobachtet am 14.11.2026 im Radiolisten-Dialog eines Drittanbieter-Editors (Cancel/OK, kein Textfeld) bei fokussiertem „Cancel“: Eine Taste, die ein Remap auf IME-Ebene in Enter verwandelt hätte, bewirkte nichts. Die Herstellerschicht hatte sie als `KEYCODE_SHIFT_LEFT` (Scancode 54) geliefert, und der IME loggte dazu nichts.
   Folge: **Jede Tastenverarbeitung auf IME-Ebene (Remaps, Shortcuts, Trackpad-Gesten) kann in solchen Dialogen nicht wirken.** Die Taste wirkt als das, worauf die Herstellerschicht sie abbildet. Dort einzugreifen bräuchte einen AccessibilityService mit Tastenfilter (konsumieren) plus Injektion, wie Key Mapper es tut.
2. **Der IME-Start ist an diesem Gerät nicht auf Texteditoren beschränkt.** `config_preventImeStartupUnlessTextEditor` ergibt `false` (`adb shell cmd overlay lookup android android:bool/config_preventImeStartupUnlessTextEditor`, mit angewendeten Overlays). Android hält die IME-Sitzung deshalb auch in gewöhnlichen Nicht-Text-Fenstern; nur Fenster mit `FLAG_ALT_FOCUSABLE_IM` (AlertDialogs ohne Text, Listen-Popups) umgehen den IME. Ermittelt am 14.11.2026.
3. Modifier-Taps (mit echten Tasten und Standardeinstellungen beobachtet, 14.11.2026): Ein kurzer Tipp auf Alt aktiviert in der App ein One-Shot-Alt. Am linken Bildschirmrand erscheint eine blaue, abgerundete Linie (vermutlich die Modifier-Anzeige der App), der nächste Buchstabe tippt sein Alt-Symbol (`a` wird auf dem QWERTY-Layout zu `*`), und die Linie verschwindet. Wird Alt gehalten, währenddessen kurz Shift getippt und dann Alt losgelassen, ist das Ergebnis dasselbe wie bei einem Alt-Tipp, weil der Shift-Tipp als Modifier-Druck zählt.

## 5. Gehaltene Tasten und `restartInput`

Ergebnisse von Tests mit einer Key-Probe-App, die `restartInput` auf Abruf auslöst, und von Fokuswechseln (14.11.2026). Sie sind als Android- und Geräteverhalten beschrieben; die Behandlung in der App wurde mit echten Tasten an diesem Gerät getestet.

1. Eine Taste, die zwischen dem `restartInput` des Editors und dem `onStartInput(restarting=true)` des IME losgelassen wird, wird an eine inaktive Verbindung zugestellt. Das App-Log zeigt dann `sendKeyEvent on inactive InputConnection`. An diesem Gerät liegt diese Lücke bei der Probe mit 100 ms Restart-Verzögerung etwa 150 bis 160 ms nach dem Druck (ein Tipp von rund 100 ms löst vor dem Neustart aus, einer von rund 260 ms nach dem Start). Die Lücke von Hand zu treffen ist unzuverlässig: viele Drücke (10 bis 15) machen und die Treffer anhand von logcat und `getevent -lt` heraussuchen.
2. Ohne Behandlung sah die Probe die Taste dauerhaft als gehalten (`held: A=[SHIFT_RIGHT]`). Ein IME, der gehaltene Modifier verfolgt, muss deshalb ein abgebrochenes `UP` über die neue Verbindung erneut senden. Mit dieser Behandlung erscheint dieselbe Warnung, das abgebrochene `UP` wird über die neue Verbindung zugestellt, und die Probe zeigt nichts Gehaltenes.
3. Wird die Verbindung ersetzt, während eine Shift-Taste gehalten wird, sieht die App dieses Shift als `UP … CANCELED` (Flags 0x24). Der Shift-Zustand des IME blieb unversehrt. Bei einer Überlappung (Taste A runter, Taste B runter, A hoch, B hoch) über ein `restartInput` hinweg blieb in 7 von 7 gültigen Durchläufen nichts gehalten; das `UP` der Nicht-Modifier-Taste kam mit Meta-Zustand 0x81 an, nicht abgebrochen.
4. Gehaltene Shift-Taste und heruntergezogene Benachrichtigungsleiste: Die App erhält `UP SHIFT_RIGHT … CANCELED`, nichts bleibt gehalten, und die nächsten Tasten tippen normal.
5. Eine gehaltene Taste, während ○ Home gedrückt und dann auf dem Launcher losgelassen wird: Es erschien kein Plektra-Shortcut-Blatt, und nach der Rückkehr in die App war der nächste Buchstabe klein.
6. Eine gehaltene Taste (die wiederholt Zeilenumbrüche erzeugt) beim Wechsel in eine andere App per Recents-Geste, dann losgelassen: Die andere App (Chrome, das schon in den Recents sein muss) bekam nichts, und der nächste Buchstabe wurde klein getippt.

## 6. Logging

1. Die Systemproperty `log.tag` ist an diesem Gerät `I`. Jedes `Log.d` einer App wird deshalb beim Schreiben verworfen und erreicht logcat nie, auch nicht die Tastenzustellungs-Spur der App und Debug-Logs von Test-Apps.
2. Vor einer Aufzeichnung die Stufe je Tag anheben. Die Einstellung hält bis zum Neustart:

   ```bash
   adb shell setprop log.tag.<DeinTag> D
   ```

   Der Input-Method-Service von Plektra loggt unter dem Tag `PlektraInputMethod`.
3. Für Zeitanalysen sind die `InputReader`-Zeilen der Herstellerschicht im logcat verlässlicher als umgerechnete `getevent`-Zeitstempel (Abschnitt 1.5).

## 7. App-Speicher auf dem Gerät

Alle Pfade betreffen das eigene Datenverzeichnis der App. Debug-Builds sind debuggable, daher funktioniert `run-as`:

```bash
adb exec-out run-as rocks.pkb.plektra cat shared_prefs/plektra_prefs.xml
adb shell run-as rocks.pkb.plektra sh -c 'cd files && find .'
```

Den Paketnamen des getesteten Builds verwenden (die offizielle Application-ID ist `rocks.pkb.plektra`; der IME-Service ist `rocks.pkb.plektra.inputmethod.PhysicalKeyboardInputMethodService`, siehe `settings get secure default_input_method`). Niemals `am force-stop` auf die App ausführen oder ihre Activity mit `am start -S` starten, solange der IME ausgewählt ist: Das beendet den IME, und Android wechselt zu einer anderen Tastatur.

1. `shared_prefs/plektra_prefs.xml` ist die Haupt-Einstellungsdatei; `shared_prefs/recent_emojis_prefs.xml` enthält die zuletzt verwendeten Emoji. Die Device-Protected-Kopie `/data/user_de/0/<Paket>/shared_prefs/direct_boot_keyboard_config.xml` enthält Layout, Modifier-Latches, Währungssymbol und die Profil-Überschreibung für den Direct Boot.
2. `files/` enthält `ctrl_key_mappings.json` (Ctrl-Navigationsbelegungen), `user_defaults.json` (die Nutzerwortliste), `dictionaries_serialized/custom/`, `keyboard_layouts/` und `theme-images/` (durch eine Wiederherstellung angelegt, kann leer sein).
3. Backups sind ZIP-Dateien namens `plektra-backup-YYYYMMDD-HHMM.zip`. Am Testgerät lagen sie unter `/storage/emulated/0/Documents/Plektra/`; der Ordner ist der, den der Dateiwähler vorgab. Das ZIP enthält `prefs/plektra_prefs.json`, `files/*`, `theme-images/manifest.json` und `backup_meta.json`.

## 8. Offene Fragen

1. Der Werksstandard jeder Herstellerbelegung ist nicht festgehalten. Insbesondere sollte an einem auf Werkseinstellungen zurückgesetzten Gerät bestätigt werden, ob eine Taste ohne `persist.sys.agui_programmable_*`-Property nativ ist.
2. Die Funktionscodes für Back, Home und die unbenannten Menüeinträge (Abschnitt 2.3) sind unbekannt.
3. Die Ursache der fehlgeschlagenen synthetischen Taps (Abschnitt 1.4) ist nicht belegt.
4. Das Verhalten auf anderen Firmwareständen ist ungetestet. Wer einen Fakt auf einem anderen Stand wiederholen kann, ergänzt Stand und Datum daneben.
