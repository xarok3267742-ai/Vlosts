# Daily Bonus Modal Panel Visual Philosophy

`daily_bonus_modal_panel_premium.webp` is a dedicated 900x420 imagegen raster panel for the daily bonus claim and cooldown dialog.

The daily bonus dialog is a repeated retention surface, so it should feel like part of the slot cabinet instead of a generic alert. The panel keeps the verified modal silhouette and alpha edges, then adds a restrained gold reward rail and subtle cabinet lights behind the existing badge, body image, button, and countdown digits.

Rules:
- render it through `ImageView`, not XML shape/text decoration;
- preserve 900x420 geometry and alpha edges;
- do not replace it with `modal_panel.webp`;
- keep all visible copy in separate image labels or bitmap digits;
- avoid baked text, large glow blobs, and layout-dependent details.

Reference preview: `qa/screenshots/daily_bonus_modal_panel_premium_contact_sheet.png`.
