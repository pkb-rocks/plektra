# Clicks Communicator preview

Official frontal Smoke render from the [Clicks Communicator configurator](https://www.clicksphone.com/products/communicator).

- Original: https://cdn.shopify.com/s/files/1/0768/8362/4251/files/communicator-smoke-2.png?v=1789058029
- Retrieved: 2026-11-09
- SHA-256: `ab2fc48e45a9b19605d0b19035cd6e7b96a45947f6de05ba8bf0ba867c0244cd`
- Permission: the maintainer confirmed permission to show the upcoming device and replace the official image’s screen content in the implementation conversation.

The comparison places the Communicator between the Titan 2 Elite and Pixel 7a, with an Upcoming label, on the same physical width scale (78.6 mm). All keyboard-bar upper edges share one horizontal reference; device names share one baseline.

The Communicator uses exactly the same original 1076 × 1200 Notes backdrop (`raw/titan-2-elite/hero-full.png`), status bar (first 104 pixels of `raw/titan-2-elite/r6-full.png`), and complete native keyboard export (`raw/titan-2-elite/r6-full-ime.png`) as the Titan 2 Elite in the comparison. Original suggestions, controls and rounded LED contours are retained. The supplied user screenshot is a visual reference only. This is an upcoming-device preview; Communicator hardware has not been tested.

The official render is cropped at x=611, y=384 to 698 × 1152 pixels, retaining the hardware silhouette. Its display opening is x=51, y=51, width=596, height=663 with rounded corners and the original camera preserved. Generated frame, mask and geometry files live under `frames/`.

The ordinary showcase renderer prepares the Communicator automatically:

```sh
npm --prefix docs/showcase run render
npm --prefix docs/showcase run verify
node ../pkb-rocks.github.io/tools/build-plektra-assets.mjs .
```

To regenerate the frame and standalone PNG only:

```sh
node docs/showcase/sources/render-clicks-preview.cjs
```

The standalone image is `docs/showcase/exports/clicks-communicator-preview.png`. The website uses the shared `01-devices` artwork in both themes.
