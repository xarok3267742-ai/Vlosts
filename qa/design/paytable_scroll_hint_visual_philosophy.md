# Paytable Scroll Hint

The paytable scroll hint should behave like cabinet hardware, not like a UI sticker covering payout values. Its only job is to show that more image-backed rows are available below; it must never compete with the 3x, 4x, or 5x payout columns.

The hint remains a decorative image layer, outside accessibility traversal. It sits just beyond the right edge of the payout cells, using the modal's side glass as its visual lane. Native scrollbars stay hidden so the screen still feels like a real slot paytable rather than a system list.

2026-07-01 SM_G975F QA note:
The previous placement overlapped the 5x column on Roman Reels. The corrected placement keeps the full row width intact while moving the hint into an unclipped side rail.
