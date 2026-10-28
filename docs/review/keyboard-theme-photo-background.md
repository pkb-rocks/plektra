# One photo across the keyboard

This contribution adds one optional image backdrop to a keyboard theme. It is independent of the photo colour sampler: that feature extracts solid colours, whereas this feature retains and displays the image itself.

## User flow

Open **Keyboard Theme**, select the hardware or software preview, and choose **Use a photo across the keyboard** in Colours. Android supplies the image picker. The app stores its own bounded PNG copy, so the theme does not depend on a continuing gallery permission or the original photo remaining at the selected URI.

The image covers the keyboard surface and shares one crop across nested rows. Existing text, icons, dividers and geometry remain in use. The original solid key colours stay in the saved theme; **Remove photo**, choosing a solid background, or resetting the background removes the image and restores those colours. Hardware and software theme assignments remain separate.

Transfer photo themes with the full application ZIP backup. Clipboard theme JSON contains colours only and the screen explains this distinction. It cannot transfer image files.

## Implementation boundaries

- One whole-keyboard image, using cover rendering. No crop editor, individual key photos, text outlines/bold controls or rotation changes are included.
- The retained source PNG is at most 1024 pixels on its longest edge; the runtime PNG is at most 512. Assets are addressed by the hash of their encoded bytes.
- Export uses the references in the frozen preference snapshot, including saved themes and drafts, and excludes unreferenced images. No image garbage collector is included.
- ZIP restore checks image names, sizes, decoded bounds, checksums and incoming references before applying settings. It stages images before preferences notify renderers and rolls back images/cache alongside settings if a later step fails.
- A partial restore preserves existing immutable image assets. Older backups without an image component retain their previous behaviour.

## Verification record

The broad local run passed 129 tests, including real PNG decoding, shared surface rendering, changed drawing bounds, import ownership, theme conversion, existing AOSP behaviour and actual ZIP round trips/rollback. After a final AOSP drawing fix, all 40 affected tests passed, including a new regression check that key drawing must not paint an opaque background over the image. Fifteen additional existing geometry tests passed. Two existing native graphics tests could not start on this Windows host because the cached Robolectric native runtime has no Windows DLL; they must run on a supported host and are not counted as passing.

The final Android lint task completed with the upstream configuration after the import/drawing corrections. This does not mean that the repository is free of existing lint findings.

Device checks use an isolated package and a generated four-colour image. Hardware and software image selection now work independently. Removing the software photo preserves the original solid palette and geometry while leaving the hardware theme unchanged. The device stores one shared runtime PNG (512 × 320) and one source PNG (800 × 500); their filename checksums and four reference colours were verified.

Two defects found on device were repaired before recording: local Kotlin callback references could compare equal while capturing different hardware/software targets, and the AOSP key renderer painted its old solid background over its image drawable. Import requests now retain their originating editor, the editor composition is keyed by target/draft/tab, and key drawing leaves the photo visible.

Live checks confirmed image coverage in ordinary physical-keyboard mode, compact mode and the on-screen keyboard. A 23.8-second recording joins three native takes in an isolated sample application using only the generated four-colour image and an empty example text field. Mode changes are cut out; English captions identify each mode. The status bar, audio and metadata are removed. All 639 recorded frames were compared, and their five distinct safe display states were visually reviewed. The device owner approved this exact video for publication. The usual keyboard, selected subtype/history, accessibility settings and temporary notification setting were restored exactly after recording.
