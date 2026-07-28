# Result Modal Panels Visual Philosophy

`result_modal_panel_*_premium.webp` assets are dedicated 900x420 imagegen raster panels for the post-spin result dialog.

The result dialog appears after normal wins, bonus/free-spin triggers, and losses, so it must feel like the same slot cabinet the player just used. Each slot theme gets its own frame language: Violet uses amethyst cabinet lights, Roman uses marble and laurel trim, Neon uses circuit glass, Pharaoh uses gold/turquoise temple trim, and Ocean uses pearl/aqua shell details.

Rules:
- render through `ImageView`, not XML shapes or text widgets;
- preserve 900x420 geometry and alpha corners;
- keep the central title/body/win amount area dark and readable;
- keep the lower-center close button area clear;
- never bake visible text, numbers, real-money symbols, cashout imagery, banknotes, or jackpot copy into the panel.

Reference preview: `qa/screenshots/result_modal_panels_premium_contact_sheet.png`.
