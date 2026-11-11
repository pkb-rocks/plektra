# Sample keyboard-theme colours from a photo

This contribution adds **Pick from photo** to the existing keyboard-theme colour picker. Choose an image with Android's photo picker, tap a point, inspect the sampled colour and hexadecimal value, then choose **Use colour**. The existing colour wheel and final **Apply** action remain available. Cancelling does not commit a new theme colour.

Only the resulting colour is stored in the theme. Existing theme JSON export/import and ZIP backup handle it without a new preference, schema or archive asset. The source photo is held temporarily for this colour dialog; it is not copied to private storage or included in backups. No broad photo-library permission is requested.

## Sampling behaviour

- Images are decoded off the UI thread as software sRGB previews, at most 1024 pixels on the longest edge, preserving aspect ratio.
- Encoded input is limited to 16 MiB. Unsupported, truncated, inaccessible or oversized images report an error without replacing the existing colour.
- Sampling averages a local patch, weighted by alpha. Fully transparent patches cannot supply a colour. Results are opaque theme colours.
- Letterboxed areas outside the photograph are ignored.
- Sampling uses the resized preview, so it is intended for choosing a palette rather than measuring exact original-image pixels.
- The temporary photo selection is not restored after activity recreation. The already committed theme remains unaffected; choose the photo again to continue sampling.

## Review boundary

This is independent of the personal-word-learning and launcher PRs. It starts from official Plektra main. It does not add photo backgrounds, texture storage, cropping, centre guides, outline/bold rendering, theme rotation or app appearance colours. Those remain separate contributions. In particular, a selected colour is not a replacement for the future image-background mode.

English and the ten existing translated locales have dedicated labels. Existing geometry, Normal/Slim priorities and hardware/software theme assignment are preserved.

## Validation

57 focused tests pass, including 9 photo-sampling tests. They cover aspect-preserving bounds, letterbox coordinate mapping, alpha-aware averaging, bounded input and an actual export-clear-restore ZIP cycle for sampled colours. Existing theme parsing, assignment and backup-contract tests are included in the local check.

The isolated StableDebug APK compiles and its signing certificate is verified. Android StableDebug lint passes; the new sampler receives one non-blocking KTX style suggestion. Device validation on a Titan 2 verified Android photo selection, exact orange/blue samples, confirmation in the existing picker and persisted hardware-theme background. See device-demo.md. The exact sanitized English recording was approved for GitHub publication; upload is pending. The separate review APK did not replace daily Plektra/Pastiera installations.
