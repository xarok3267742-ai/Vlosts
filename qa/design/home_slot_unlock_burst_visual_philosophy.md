# Home Slot Unlock Burst

Purpose: make the level system feel tangible when a new slot opens. A newly unlocked slot should read as an event, not just as a missing lock overlay.

Implementation:
- The unlock burst is a generated bitmap overlay, not text or XML decoration.
- Portrait uses `home_slot_unlock_burst.webp`; landscape uses `home_slot_unlock_burst_land.webp`.
- The layer sits above the locked pulse and below the lock overlay in XML. At runtime the lock overlay is hidden before the burst starts, so the unlock effect lands on the newly playable card.
- `HomeFragment` compares the previous observed player level with the current level and only animates slots returned by `SlotUnlockRules.slotsUnlockedBetween`.
- First Home bind does not replay unlock effects for already-open slots.
- Animations are finite, respect disabled system animators, and are cleaned up on stop/destroy.

Imagegen source:
- `qa/source/vslot_home_slot_unlock_burst_imagegen.png`
- `qa/screenshots/home_slot_unlock_burst_contact_sheet.png`
- `tools/slice_imagegen_home_slot_unlock_burst.py`

Prompt summary:
Two-panel premium slot-card unlock celebration overlay on flat `#00ff00`: one wide card burst and one tall carousel burst. Open lock, jewel gate, violet/cyan/gold rays, small gem particles, no text, no numbers, no currency, no real-money symbols.
