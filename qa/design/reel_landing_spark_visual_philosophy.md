# Reel Landing Spark Visual Philosophy

The reel landing spark is a short image-only impact pass that appears when a reel column finishes its physical stop. It should read as cabinet light, glass shimmer, and a compact brake-impact burst, not as a payout claim or a new symbol.

The layer belongs above motion streaks and anticipation beams but below brake clamps, window masks, reel glass, paylines, and win halos. It must disappear quickly enough that losing spins stay clean and readable while still making every stop feel mechanical and deliberate.

The asset is generated from `qa/source/vslot_reel_landing_spark_imagegen.png`, processed into a transparent `reel_landing_spark.webp`, and verified through `qa/screenshots/reel_landing_spark_contact_sheet.png`.

The themed landing sparks are generated from `qa/source/vslot_theme_reel_landing_sparks_imagegen.png`, processed by `tools/slice_imagegen_theme_reel_landing_sparks.py`, and verified through `qa/screenshots/theme_reel_landing_sparks_contact_sheet.png`. Each slot must use its own overlay: violet crystal, Roman gold/laurel, neon electric, pharaoh gold/teal, and ocean aqua/pearl. These overlays must stay decorative, text-free, and free of cash, coins, prize, or cashout imagery.
