# Shared Top Bar Visual Philosophy

`top_bar_premium.webp` is the shared image chrome for Home, Slot, Settings, and Privacy headers. It replaces the old flat `top_bar.webp` while keeping all text, logos, buttons, balance digits, and accessibility labels as separate auditable assets.

Rules:
- keep the 1200x220 geometry so portrait and landscape headers can scale predictably;
- do not bake arrows, gears, readable labels, numbers, or logo text into the bar;
- preserve blank end-cap zones for separate 48dp `ImageButton` controls;
- keep the center channel dark enough for title image labels and slot title art;
- allow reuse as a compact balance strip without making the coin/digits unreadable.

QA evidence:
- `qa/source/vslot_top_bar_premium_imagegen.png`
- `qa/screenshots/top_bar_premium_contact_sheet.png`
