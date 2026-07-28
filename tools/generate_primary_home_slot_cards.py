#!/usr/bin/env python3
from __future__ import annotations

import generate_new_slot_assets as slot_assets


PRIMARY_HOME_CARD_THEMES = {
    "violet": slot_assets.Palette(
        prefix="vf",
        card_name="slot_card_violet_fortune",
        title="ФИОЛЕТОВАЯ ФОРТУНА",
        primary=(177, 86, 255),
        secondary=(255, 65, 186),
        accent=(255, 220, 105),
        dark=(18, 8, 48),
    ),
    "roman": slot_assets.Palette(
        prefix="rr",
        card_name="slot_card_roman_reels",
        title="РИМСКИЕ БАРАБАНЫ",
        primary=(255, 205, 115),
        secondary=(213, 92, 62),
        accent=(255, 239, 174),
        dark=(38, 17, 20),
    ),
}

PRIMARY_HOME_CARD_SYMBOLS = {
    "violet": [
        "vf_symbol_v_wild.webp",
        "vf_symbol_diamond.webp",
        "vf_symbol_ruby.webp",
        "vf_symbol_coin.webp",
        "vf_symbol_crown.webp",
    ],
    "roman": [
        "rr_symbol_v_wild.webp",
        "rr_symbol_laurel.webp",
        "rr_symbol_shield.webp",
        "rr_symbol_coin.webp",
        "rr_symbol_crown.webp",
    ],
}


def main() -> None:
    original_palettes = dict(slot_assets.PALETTES)
    original_card_symbols = dict(slot_assets.CARD_SYMBOLS)
    try:
        slot_assets.PALETTES.update(PRIMARY_HOME_CARD_THEMES)
        slot_assets.CARD_SYMBOLS.update(PRIMARY_HOME_CARD_SYMBOLS)
        for theme, palette in PRIMARY_HOME_CARD_THEMES.items():
            slot_assets.save_webp(
                slot_assets.draw_card(theme, pressed=False),
                f"{palette.card_name}_default.webp",
                quality=93,
            )
            slot_assets.save_webp(
                slot_assets.draw_card(theme, pressed=True),
                f"{palette.card_name}_pressed.webp",
                quality=92,
            )
            print(f"{palette.card_name}_default.webp")
            print(f"{palette.card_name}_pressed.webp")
    finally:
        slot_assets.PALETTES.clear()
        slot_assets.PALETTES.update(original_palettes)
        slot_assets.CARD_SYMBOLS.clear()
        slot_assets.CARD_SYMBOLS.update(original_card_symbols)


if __name__ == "__main__":
    main()
