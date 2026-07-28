# Push Permission Modal Panel

Purpose: replace the generic modal bitmap with a dedicated premium imagegen push prompt panel that already reserves visual zones for the badge/title, body copy, and two CTA buttons.

Visual checks:
- The panel must read as one seamless slot-machine dialog surface, not a stretched generic card.
- The upper rail should frame the notification title and badge without fighting the text images.
- The middle bay must keep the Russian body image readable at 360dp width.
- The lower bay must align with the two image buttons and avoid visible seams behind the divider.
- Decorative lattice and signal burst layers stay above this panel, while all interactive/copy image layers stay above the effects.
- The premium panel uses a dark violet notification-signal cabinet surface with a clean center bay so the Russian image copy remains readable in portrait and landscape.
- Chroma-key edge cleanup must leave no green fringe around the transparent outer glow.

QA evidence:
- `qa/source/vslot_push_permission_modal_panel_premium_imagegen.png`
- `qa/screenshots/push_permission_modal_panel_premium_contact_sheet.png`
