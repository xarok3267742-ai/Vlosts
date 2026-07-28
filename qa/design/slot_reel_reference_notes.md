# Slot Reel Reference Notes

Reference captured on 2026-07-06 from <https://pixi-reels.schmooky.dev/demos/>.

Captured evidence:
- `qa/screenshots/reference_pixi_reels/demos_index.png`
- `qa/screenshots/reference_pixi_reels/sprite_classic_page.png`
- `qa/screenshots/reference_pixi_reels/sprite_classic_spin_120ms.png`
- `qa/screenshots/reference_pixi_reels/sprite_classic_spin_670ms.png`
- `qa/screenshots/reference_pixi_reels/sprite_classic_stop.png`
- `qa/screenshots/reference_pixi_reels/scatter_fs_ready.png`
- `qa/screenshots/reference_pixi_reels/scatter_fs_spin_collapsed_200ms.png`
- `qa/screenshots/reference_pixi_reels/scatter_fs_spin_collapsed_1100ms.png`
- `qa/screenshots/reference_pixi_reels/scatter_fs_after_collapsed.png`

Implementation principles for V Slot:
- Keep each reel as a visible 5x3 cell column during spin; do not replace it with a generic scrolling strip.
- Swap symbol images to companion motion-blur variants on spin and back to crisp variants on stop, per reel.
- Stop reels sequentially so the last reel carries the tension instead of all columns snapping at once.
- Trigger free spins only after spin completion when 3+ scatters are visible.
- Use anticipation on near-miss states by slowing the last reel; never mutate the final result mid-spin.
- Keep secondary panels away from primary spin button hit areas; the reference demo's expanded cheat panel showed how easily an overlay can intercept the spin action.

2026-07-06 implementation update:
- Manual spins support slam-stop: tapping the image spin button again completes the reveal after a minimum reel-show time instead of waiting for the full timer.
- Manual slam-stop has a dedicated imagegen cue over the spin button while manual reels are spinning, so the second tap is visible without adding text or blocking the hit target.
- Autospin keeps the spin button disabled during active spins, so slam-stop remains a deliberate manual action and cannot fight the repeat loop.
- Reel pacing now reserves longer stagger/deceleration and stronger scatter anticipation so the final columns settle before results, closer to the Pixi Reels phase model.
