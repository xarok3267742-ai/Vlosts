# Free Spins Mode Overlay

Free spins should feel like the machine entered a distinct bonus mode, not just like a small counter changed value. The overlay is a transparent reel-chamber layer that frames the symbols with teal rails, gold pins, and small FS medallions while leaving the center of every reel readable.

Violet Fortune and Roman Reels use separate image assets. Violet keeps a jewel-purple accent around the medallions; Roman shifts toward bronze and ceremonial teal so the mode belongs to that cabinet instead of looking pasted in from the other slot.

The layer is decorative because the persisted free spin balance already has Russian accessibility copy on the counter. It remains visible while `freeSpinsBalance > 0` and through an already reserved final free spin, then leaves only after that spin settles. It pulses gently like cabinet hardware and stays below spin blur, winning paylines, and glass so the actual game result remains visually authoritative.

2026-07-01 QA note:
The contact sheet checks both overlays on a dark background. The central symbol area must remain transparent, side rails should be readable at phone scale, and FS medallions must stay on the rim instead of covering primary symbols.
