# Reel Anticipation Beams

Purpose: make free-spins/scatter tease feel closer to a real slot reel without changing payout math.

Reference: `https://pixi-reels.schmooky.dev/demos/` for the visual idea of staggered reel stops, blur during motion, and anticipation before a bonus-relevant stop.

Implementation:
- Five imagegen-derived WebP overlays live in `drawable-nodpi`.
- The layer is decorative and sits above motion streaks, below brake clamps and window masks.
- The beam only pulses when `scatterChase` is true, so normal spins stay readable.
- The effect is finite, cancellable, and cleaned up when the spin preview stops or the fragment view is destroyed.

Imagegen source:
- `qa/source/vslot_reel_anticipation_beams_imagegen.png`
- `qa/screenshots/reel_anticipation_beams_contact_sheet.png`
- `tools/slice_imagegen_reel_anticipation_beams.py`

Prompt summary:
Five premium vertical reel anticipation light-beam overlays on a flat `#00ff00` chroma-key background, one per slot theme: violet crystals, Roman gold laurel, neon cyan-magenta electric trails, pharaoh gold turquoise sun rays, and ocean pearl aqua bubbles. No text, no numbers, no currency, no watermark.
