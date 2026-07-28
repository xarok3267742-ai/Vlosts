# Low Coins Modal Panel Visual Philosophy

`low_coins_modal_panel_premium.webp` is a dedicated 900x420 imagegen raster panel for the low-coins rescue dialog.

The low-coins dialog is part of the slot loop, so it should feel like a cabinet rescue state rather than a generic alert. The panel keeps a dark readable center for the existing Russian image labels, adds amber warning lamps and virtual coin tray details on the sides, and leaves the lower center clear for the claim/OK button.

Rules:
- render it through `ImageView`, not XML shape/text decoration;
- preserve 900x420 geometry and alpha corners;
- keep all visible copy in separate image labels or bitmap digits;
- avoid real-money symbols, banknotes, cashout imagery, and jackpot copy;
- keep center text and button zones visually quiet.

Reference preview: `qa/screenshots/low_coins_modal_panel_premium_contact_sheet.png`.
