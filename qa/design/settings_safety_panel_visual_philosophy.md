# Settings Safety Panel

The settings safety panel is a compliance console, not decoration. It must be immediately readable as a Russian-only social-casino disclosure: age restriction, no purchases, no payouts, and no cash value. The panel should look like a measured instrument in the same cabinet system as the rest of the app, with clear counters, calibrated rings, and restrained gold/cyan rails.

All visible wording must be rendered inside the bitmap as Russian image copy. No English labels, cashout implication, or real-money prize language should appear. The visual tone is premium and factual: enough glow to belong in the slot UI, but controlled enough to feel trustworthy.

`settings_safety_anchor.webp` is a decorative imagegen dock placed behind the safety panel in both portrait and landscape. It must never contain text, numbers, cash cues, prizes, or gambling CTAs; the readable compliance message stays only in `settings_safety_panel.webp`.

The asset is reproducible from `qa/source/vslot_settings_safety_anchor_imagegen.png` via `tools/slice_imagegen_settings_safety_anchor.py`. Visual QA lives at `qa/screenshots/settings_safety_anchor_contact_sheet.png` and must show the anchor supporting the panel without covering the Russian disclosure.
