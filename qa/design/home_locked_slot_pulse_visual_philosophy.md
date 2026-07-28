# Home Locked Slot Pulse Visual Philosophy

Locked Home cards should still feel like premium slot content, not disabled list items. The pulse overlay is a decorative image layer that adds a short unlock-energy response when a gated card is tapped, while keeping the actual Russian lock copy in the existing bitmap lock overlay.

Implementation notes:
- Source art is generated via built-in imagegen on a removable chroma-key background.
- `tools/slice_imagegen_home_locked_slot_pulse.py` removes the key, validates transparent corners, exports wide and tall WebP assets, and writes a contact sheet.
- Portrait cards use `home_locked_slot_pulse.webp`; landscape carousel cards use `home_locked_slot_pulse_land.webp` so the frame does not stretch.
- The pulse sits above card shine and below the lock overlay, with `importantForAccessibility="no"` and no text.
- The animation is finite: a low settled alpha plus a short tap pulse, respecting disabled system animators.

Reference preview:
- `qa/screenshots/home_locked_slot_pulse_contact_sheet.png`
