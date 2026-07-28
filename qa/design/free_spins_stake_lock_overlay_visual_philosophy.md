# Free Spins Stake Lock Overlay Visual Philosophy

Free spins intentionally consume a persisted free-spin balance instead of debiting coins. While this mode is active, bet and line controls are disabled. The player needs a strong visual cue that the controls are frozen by the feature mode, not broken.

Implementation notes:
- Source overlays are generated with built-in imagegen on removable chroma-key backgrounds.
- `tools/slice_imagegen_free_spins_stake_lock_overlay.py` removes the key, exports five theme-colored variants for portrait and landscape, and writes a contact sheet.
- The overlay sits above the bet/line steppers only while `freeSpins > 0`.
- It is decorative: no text, no currency symbols, no accessibility node, and no touch target.
- The animation is a finite entrance pulse, then a stable locked state, with cleanup in `onDestroyView`.

Reference preview:
- `qa/screenshots/free_spins_stake_lock_overlay_contact_sheet.png`
