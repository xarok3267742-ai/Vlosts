# Slam Stop Cue Visual Philosophy

Generated on 2026-07-06 with the project imagegen pipeline.

Source and QA evidence:
- `qa/source/vslot_slam_stop_cue_imagegen.png`
- `app/src/main/res/drawable-nodpi/slam_stop_cue.webp`
- `qa/screenshots/slam_stop_cue_contact_sheet.png`
- `qa/source/vslot_theme_slam_stop_cues_imagegen.png`
- `app/src/main/res/drawable-nodpi/slam_stop_cue_violet.webp`
- `app/src/main/res/drawable-nodpi/slam_stop_cue_roman.webp`
- `app/src/main/res/drawable-nodpi/slam_stop_cue_neon.webp`
- `app/src/main/res/drawable-nodpi/slam_stop_cue_pharaoh.webp`
- `app/src/main/res/drawable-nodpi/slam_stop_cue_ocean.webp`
- `qa/screenshots/theme_slam_stop_cues_contact_sheet.png`

Design intent:
- The cue is a mechanical stop-ring image that appears only while a manual spin can be slam-stopped.
- Each slot theme uses its own stop-ring material so the manual second-tap cue stays consistent with that cabinet.
- It contains no text, currency, cashout, prize, or coin language; it is a decorative control affordance only.
- The layer is non-clickable, non-focusable, and ignored by accessibility so the actual spin button remains the only action target.
- The visual sits above the spin button and below the press impact flash, keeping the hierarchy clear during a second tap.

Implementation notes:
- The cue pulses and rotates gently during manual spins.
- Autospin does not show the cue because autospin taps remain locked while reels are moving.
- The asset is 512x512 WebP with transparent background and is used through `ImageView`.
- The themed assets are produced by `tools/slice_imagegen_theme_slam_stop_cues.py` and remain a visual-only layer; the actual accessible action target is still the spin button.
