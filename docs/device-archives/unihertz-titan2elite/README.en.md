English | [Deutsch](README.md)

# Unihertz Titan 2 Elite

This directory collects reference material on the Unihertz Titan 2 Elite. As long as no test device is available, photos and the dimensions derived from them are explicitly only approximations and not measurements taken on the device. A debug export from a final, shipped device is available for device detection.

## Display

According to the manufacturer, the device uses a 4.03-inch AMOLED with 1080 × 1200 pixels and 401 ppi. The reference photos show strongly rounded lower display corners. In the normalized 1080 × 1200 template, the visible arc corresponds approximately to a radius of 96 px. The [SVG template](titan2elite-display-overlay.svg) marks this arc and the 16 px guide lines for the bottom Pastiera row, determined in the emulator at 400 dpi.

A final, shipped device (Titan 2 Elite_V02.00.04) reports a radius of 100 px at all four corners via `WindowInsets.getRoundedCorner`. When the option is enabled, Pastiera rounds the lower corners of the entire IME chrome with an outline clip using the reported radius, so that the status bar background follows the display arc. The row contents deliberately stay across the full display width and are not indented. If the reported radius is missing, a 50 dp fallback derived on the device is used.

## Emulator acceptance

The API 36 test AVD uses 1080 × 1200 pixels. The stock emulator cannot hide the additional IME caption bar the way the Unihertz system option does. For comparable screenshots, the raw height is therefore increased by the Android dimension `navigation_bar_frame_height` of 48 dp and then cropped at y = 1200. This corresponds to 108 px at 360 dpi, 120 px at 400 dpi and 132 px at 440 dpi. The effective 1080 × 1200 capture thus ends where the Pastiera content ends on the device with the "Hide IME Caption Bar" option enabled.

The acceptance matrix combines 360, 400 and 440 dpi, each with the font scales 85%, 100% and 115%.

## Reproducible theme geometry matrix

The built-in hardware themes can be re-run against the compensated 1080 x 1200 viewport with:

```bash
scripts/capture-titan2elite-theme-geometry.sh emulator-5560
```

The script selects each theme through the normal settings UI, focuses the Pastiera test field with
Maestro, captures the 1080 x 1320 stock AVD and crops off the computed 48 dp / 120 px caption bar
compensation. The images therefore check in particular different heights, roundings, paddings and gaps, without
introducing a debug-only path for writing settings.

## Modifier LEDs

With the rounding option enabled, the LED segments follow concentric arcs along the lower display corners. The two rows keep their colors and states; their segment widths are distributed along the contour. The LED area reserves at least the height of the larger corner radius, so that the outer segments can visibly run upward. Without the rounding option, the previous flat presentation is kept.

The LED geometry uses normalized X/Y coordinates as well as width and height per segment. The previous
single-row layout remains the default profile. The Titan 2 Elite profile, in contrast, models two physical key rows:
Alt on the left and Sym on the right at the top, Shift coupled to both outer lower positions, and Ctrl at the bottom right above
the Fn key located further inward. The T2E bars use the wide sixth-zones visible in the photo
instead of the narrower keycap widths, so that they stay readable at the rounded display corners. Several segments
may represent the same logical state; this allows a later device profile to change positions or
key widths without adapting the state or rendering logic.

## Device detection

The final Titan 2 Elite reports, like a Titan 2, among other things `model=Titan 2`, `device=Titan_2`, `product=Titan_2_EEA` and a Titan 2 fingerprint. Reliable distinguishing features are `board=G72BoardV1` and a `build_display` that begins with `Titan 2 Elite`. A raw build fingerprint alone must therefore control neither device detection nor a cross-device settings import.

## References

- [Pastiera status bar on a Titan 2 Elite](2026-07-20-pastiera-statusbar-reference.png), photo sent in on 20 July 2026, 1176 × 1416 pixels, SHA-256 `4278b3599317acc733f532eb2f572602de49410f1eb6a759280e1ce764c7ea21`
- [Manufacturer page with technical data](https://www.unihertz.com/en-de/products/titan-2-elite)
