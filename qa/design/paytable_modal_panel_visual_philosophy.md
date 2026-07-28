# Paytable Modal Panel Visual Philosophy

`paytable_modal_panel.webp` is a dedicated 900x560 raster backplane for the payout table.

The previous dialog stretched the generic 900x420 modal panel into a taller 560dp paytable surface. That made the background feel less like a real slot cabinet and left the same generic seam risk seen in other dialogs. The new panel is built at the paytable's native geometry, with darker row bays, restrained cabinet lamps, and a footer dock behind the close control.

Rules:
- render it through `ImageView`, not XML shape/text decoration;
- keep the native 900x560 geometry;
- keep all copy, odds, symbols, and multipliers in separate image/bitmap layers;
- do not replace it with `modal_panel.webp`;
- avoid large decorative blobs, baked text, and high-contrast details behind payout rows.

Reference preview: `qa/screenshots/paytable_modal_panel_before_after.png`.
