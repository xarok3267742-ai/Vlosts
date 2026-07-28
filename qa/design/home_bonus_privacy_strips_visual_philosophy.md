# Home Bonus And Privacy Strips

The home utility strips should feel like integrated machine hardware, not leftover menu bars. They need to sit between the hero slot cards with the same degree of visual craft: layered glass, bevelled metal, illuminated coin rails, and calm center zones that keep Russian image copy readable. The result should look meticulously crafted and intentionally quiet, as if a senior game UI artist refined every highlight and shadow.

The daily bonus strip is a reward vault ticket. In the ready state it should glow with gold and cyan energy, making the player understand that a harmless virtual coin reward is available. In the wait state it becomes cooler and sealed, with a clear image-rendered Russian status message and no CTA confusion. It must never imply real money, payout, withdrawal, or prize value.

The privacy strip is a secure document console. It should carry a restrained glass-and-metal language with small shield/document cues, enough to feel premium but not louder than the game entries. The visible label remains a separate image overlay, so the base button must provide structure, depth, and focus state without embedding duplicate text.

`btn_privacy_default_premium.webp` and `btn_privacy_pressed_premium.webp` are the shared command base for the home privacy strip and Settings command rows. They replace the older flat base while keeping the Russian labels as separate image overlays.

Both strips must remain image-first Android assets. Copy can exist only as bitmap label artwork or accessibility strings, and all layout tap targets must stay stable at 360dp. The assets should harmonize with the new slot cards while preserving the home screen rhythm.

2026-07-01 device QA update:

- The privacy strip is now the clickable/focusable accessibility target itself, with selector artwork and label artwork inside it using duplicated parent state. This keeps the whole visible machine-console strip tappable on SM_G975F instead of relying on a nested button hit area near the Android navigation edge.
- Home now reserves the same immersive bottom safe area used by Slot and Settings. The privacy strip and social disclaimer must both remain inside the app frame and accessibility tree, not visually drift into the Android navigation edge.

QA evidence:
- `qa/source/vslot_btn_privacy_default_premium_imagegen.png`
- `qa/source/vslot_btn_privacy_pressed_premium_imagegen.png`
- `qa/screenshots/privacy_command_buttons_premium_contact_sheet.png`
