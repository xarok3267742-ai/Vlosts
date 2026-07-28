# Daily Bonus Countdown Charge

Source imagegen asset: `qa/source/vslot_daily_bonus_countdown_charge_imagegen.png`

Runtime asset: `app/src/main/res/drawable-nodpi/daily_bonus_countdown_charge.webp`

Visual QA: `qa/screenshots/daily_bonus_countdown_charge_contact_sheet.png`

The charge overlay is a decorative glow layer for the home daily bonus cooldown rail. It sits behind the existing rail and bitmap digits, adding a subtle slot-machine charge signal while preserving timer readability.

Compliance notes:
- No text, letters, numbers, logos, or watermarks in the asset.
- No coins, cash, payout, prize, or cashout imagery.
- The layer is decorative and excluded from accessibility traversal.
- Motion is driven by the existing countdown tick with finite state updates, not an infinite animator.
