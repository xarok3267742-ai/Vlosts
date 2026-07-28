# Home Daily Bonus Countdown Rail

The home cooldown timer uses a dedicated image rail instead of reusing a line-count badge. The rail keeps two readable zones on 360dp devices: a small V token and a separated digit well for `HH:MM:SS`.

The layout keeps the timer image-based while giving the bitmap digits a fixed 110dp area. The large daily-bonus strip still carries the Russian wait status, while the compact right rail shows only the timer so it does not cover or compete with the main status copy.

The `daily_bonus_countdown_charge.webp` overlay sits behind the rail as a low-alpha charge signal. It is decorative, excluded from accessibility, and is driven by the existing countdown tick instead of an infinite animation.
