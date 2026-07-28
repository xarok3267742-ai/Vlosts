# Settings Modal Panel Visual Philosophy

`settings_modal_backplate.webp`, `settings_modal_panel_premium.webp`, and `settings_modal_panel_landscape_premium.webp` are dedicated raster layers for the Settings screen.

The Settings panel is much taller than the small generic modal background. Stretching `modal_panel.webp` created a visible generic split and made the screen feel less like a finished cabinet surface. The premium portrait panel keeps separate zones for the app/version area, the three command buttons, push status, and the compliance strip without baking any visible text into the background.

Landscape uses its own wide `settings_modal_panel_landscape_premium.webp` instead of stretching the portrait panel. This keeps the two-column settings layout readable and prevents rails from distorting across the Russian image labels.

Rules:
- render both layers through `ImageView`;
- keep the 1000x1500 portrait geometry aligned with `settings_control_console_glow.webp`;
- keep the 1500x620 landscape geometry aligned to the two-column layout;
- keep all copy, buttons, status labels, and compliance claims in separate image assets;
- do not replace these layers with `modal_panel.webp` or `modal_panel_backplate.png`;
- avoid high-contrast details behind labels and buttons.

Reference previews:
- `qa/source/vslot_settings_modal_panel_premium_imagegen.png`
- `qa/source/vslot_settings_modal_panel_landscape_premium_imagegen.png`
- `qa/screenshots/settings_modal_panel_premium_contact_sheet.png`
- `qa/screenshots/settings_modal_panel_portrait_mock_full.png`
- `qa/screenshots/settings_modal_panel_landscape_mock_full.png`
