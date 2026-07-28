# V Slot app icon

Generated with the built-in OpenAI image generation tool and integrated on 2026-07-20.

## Generation brief

Use case: logo-brand

Asset type: premium Android slot-game launcher and Google Play icon

Primary request: Create a square V Slot app icon with a large sculpted V as the unmistakable focal point. Use polished violet metal, warm gold trim, three cyan slot-reel facets across the center, and a small crystal accent at the lower point. Keep the silhouette bold and readable at launcher size, with no text, coins, money, casino chips, watermark, outer border, or rounded-corner mask. Place it on a clean near-black background with restrained premium lighting and keep the important mark inside the adaptive-icon safe area.

## Files

- `v-slot-icon-master-v2.png`: unmodified 1254 x 1254 generated source.
- `v-slot-icon-512-v2.png`: 512 x 512 32-bit RGBA Google Play listing asset, below the 1024 KB upload limit.
- `../../../app/src/main/res/drawable-nodpi/app_icon_art_v2.png`: 432 x 432 RGB launcher artwork with additional mask-safe padding.
- `../../../app/src/main/res/drawable-nodpi/app_logo_mark_v2.png`: 1254 x 1254 RGBA runtime splash mark extracted from the checked-in imagegen chroma source at `../../../qa/source/vslot_app_logo_mark_chroma_imagegen.png`.

The store icon, feature graphic, and padded adaptive-icon bitmap are deterministic exports. Regenerate and verify them from the retained masters with:

```bash
python3 tools/export_store_graphics.py
python3 tools/export_store_graphics.py --check
```

The Android adaptive icon XML and Android 12 system splash use the padded launcher artwork. The custom portrait and landscape splash screens use the transparent runtime mark. The existing monochrome vector-compatible layer remains active for themed icons.

## Google Play feature graphic

Generated with the built-in OpenAI image generation tool on 2026-07-20 using the production icon and a captured violet-slot screen as visual references.

The brief requests truthful wide key art for the simulated slot game, keeps all important symbols in the central safe area, and excludes text, calls to action, payout amounts, real currency, people, store badges, and watermarks.

- `v-slot-feature-graphic-master-v1.png`: unmodified 1795 x 876 generated source.
- `v-slot-feature-graphic-1024x500-v1.png`: centered 1024 x 500 24-bit RGB Google Play feature graphic with no alpha channel.
