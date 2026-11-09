# One photo across the keyboard

This contribution adds one optional image backdrop to a keyboard theme. It retains and displays the image itself; the separately proposed photo colour sampler (#168) only extracts solid colours from a photo.

## User flow

Open **Keyboard Theme**, select the hardware or software preview, and use the **Background photo** card at the top of **Colours**. Android supplies the image picker. The app stores its own bounded PNG copy, so the theme does not depend on a continuing gallery permission or the original photo remaining at the selected URI.

The image covers the keyboard surface and shares one crop across nested rows. Existing text, icons, dividers and geometry remain in use. Keys, suggestions and buttons are drawn transparent while a photo is set, and the card says so. The original solid colours stay in the saved theme; **Remove photo**, choosing a solid background, or resetting the background removes the image and restores those colours. Hardware and software theme assignments remain separate. A finished import applies to the latest theme of the editor that started it; leaving that editor cancels it. The editor identity (draft, tab, page) and a picked image are saved state, so a result that arrives after Android recreated the settings activity still reaches the same editor, and an interrupted import resumes. The keyboard menu row repaints its part of the shared crop, so the variation row underneath does not show through.

If the image file is missing (for example after a restore without it) or cannot be decoded, renderers drop the photo and show every saved surface colour, including keys, suggestions and buttons. The IME and the settings previews re-apply the theme when that happens. Importing the same photo again rewrites the file under the same content hash, clears the failure and shows the photo again without restarting the process.

Transfer photo themes with the full application ZIP backup. Clipboard theme JSON contains colours and key settings only; the export dialog explains this when the theme has a photo, and pasted JSON with image references is imported without them. Android auto backup includes `theme-images/` so restored theme references keep their files.

## Implementation boundaries

- One whole-keyboard image, using cover rendering. No crop editor, individual key photos, text outlines/bold controls or rotation changes are included.
- The retained source PNG is at most 1024 pixels on its longest edge; the runtime PNG is at most 512. Assets are addressed by the hash of their encoded bytes.
- Export uses the references in the frozen preference snapshot, including saved themes and drafts, and excludes unreferenced images. A missing or damaged local image is skipped and logged instead of blocking the whole backup.
- Each import first removes unreferenced images older than ten minutes; newer files may belong to an import or restore whose preference is not yet committed.
- ZIP restore checks image names, sizes, decoded bounds and checksums before applying settings. A reference without an image is logged and falls back to the theme colours. It stages images before preferences notify renderers and rolls back images/cache alongside settings if a later step fails.
- A partial restore preserves existing immutable image assets. Older backups without an image component retain their previous behaviour.

## Verification record (contributor)

The broad local run passed 129 tests, including real PNG decoding, shared surface rendering, changed drawing bounds, import ownership, theme conversion, existing AOSP behaviour and actual ZIP round trips/rollback. After a final AOSP drawing fix, all 40 affected tests passed, including a new regression check that key drawing must not paint an opaque background over the image. Fifteen additional existing geometry tests passed. Two existing native graphics tests could not start on this Windows host because the cached Robolectric native runtime has no Windows DLL; they must run on a supported host and are not counted as passing.

The final Android lint task completed with the upstream configuration after the import/drawing corrections. This does not mean that the repository is free of existing lint findings.

Device checks use an isolated package and a generated four-colour image. Hardware and software image selection now work independently. Removing the software photo preserves the original solid palette and geometry while leaving the hardware theme unchanged. The device stores one shared runtime PNG (512 × 320) and one source PNG (800 × 500); their filename checksums and four reference colours were verified.

Two defects found on device were repaired before recording: local Kotlin callback references could compare equal while capturing different hardware/software targets, and the AOSP key renderer painted its old solid background over its image drawable. Import requests now retain their originating editor, the editor composition is keyed by target/draft/tab, and key drawing leaves the photo visible.

Live checks confirmed image coverage in ordinary physical-keyboard mode, compact mode and the on-screen keyboard. A 23.8-second recording joins three native takes in an isolated sample application using only the generated four-colour image and an empty example text field. Mode changes are cut out; English captions identify each mode. The status bar, audio and metadata are removed. All 639 recorded frames were compared, and their five distinct safe display states were visually reviewed. The device owner approved this exact video for publication. The usual keyboard, selected subtype/history, accessibility settings and temporary notification setting were restored exactly after recording.

## Verification record (second review round)

- Unit tests: the full `testStableDebugUnitTest` run passes. New regressions: missing and damaged photos render every saved surface colour (also after a reload and in newly created views); a failed asset followed by a re-import with the same hash loads again; the menu overlay paints the shared crop at its offset; the real settings activity delivers a photo picked before activity recreation to the open draft or to the software theme only, and drops a result for an editor that was left.
- Lint: no new errors against `ci/lint-errors.json`.
- Device (Galaxy Z Fold6, unfolded, generated four-field image only): picker result after a forced configuration relaunch reaches the hardware editor; hardware and software photos are independent; live software keyboard, emoji picker and clipboard view show one continuous backdrop; menu row no longer shows the variation row through it; with the image file removed the live keyboard shows the saved colours (`#3A3A3C` keys, `#171717` suggestions), and re-importing the same photo shows it again in the same process. Settings were restored to their previous preference file afterwards.
