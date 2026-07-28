# Settings Push Status Console Visual Philosophy

Generated on 2026-07-07 with the project imagegen pipeline.

Source and QA evidence:
- `qa/source/vslot_settings_push_status_console_imagegen.png`
- `app/src/main/res/drawable-nodpi/settings_push_status_console.webp`
- `app/src/main/res/drawable-nodpi/settings_push_status_signal_pulse.webp`
- `qa/screenshots/settings_push_status_console_contact_sheet.png`

Design intent:
- Push notification status should feel like part of the slot cabinet, not a plain settings row.
- The console asset has no text, numbers, money, coins, prizes, or cashout symbols; status copy remains in controlled Russian label images.
- The separate signal pulse gives the state change a compact image-based animation without adding new interaction targets.
- The accessibility description stays on the status container while console and signal layers are decorative.

Implementation notes:
- Portrait and landscape Settings layouts use the same WebP console with different row heights.
- `SettingsFragment` updates the Russian status label image and animates the signal pulse when the push state changes.
- Unconfigured push remains visually subdued, because release builds must not pretend AppMetrica/Firebase are configured.
