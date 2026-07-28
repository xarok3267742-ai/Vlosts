# Slot Level Session Meter Visual Philosophy

Generated on 2026-07-06 with the project imagegen pipeline.

Source and QA evidence:
- `qa/source/vslot_slot_level_meter_imagegen.png`
- `app/src/main/res/drawable-nodpi/slot_level_session_panel.webp`
- `qa/screenshots/slot_level_session_meter_contact_sheet.png`

Design intent:
- The meter keeps player level and XP visible inside the slot cabinet, not only on the home screen.
- The image is a mechanical glass HUD with a level socket and XP rail; the actual values are rendered with bitmap digits and dynamic image fill.
- It has no text, cash, coin, prize, trophy, or cashout language, so it stays in the social-casino entertainment frame.
- The layer sits in the cabinet marquee area above the reel window and does not reduce spin controls or reel hit areas.

Implementation notes:
- `SlotFragment` binds the meter from `PlayerState.levelXp`, `playerLevel`, `xpInCurrentLevel`, and `xpForCurrentLevel`.
- XP changes after spins trigger a short image-based pulse; level-ups use a stronger digit/cap pulse.
- Portrait and landscape XML use the same WebP panel with orientation-specific dimensions.
