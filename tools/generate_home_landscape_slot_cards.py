#!/usr/bin/env python3
from __future__ import annotations

from math import sin

from PIL import Image, ImageDraw, ImageFilter

import generate_new_slot_assets as slot_assets
import generate_primary_home_slot_cards as primary_home


LANDSCAPE_HOME_CARD_THEMES = {
    **primary_home.PRIMARY_HOME_CARD_THEMES,
    **slot_assets.PALETTES,
}

LANDSCAPE_HOME_CARD_SYMBOLS = {
    **primary_home.PRIMARY_HOME_CARD_SYMBOLS,
    **slot_assets.CARD_SYMBOLS,
}


def brighter(color: tuple[int, int, int], amount: int) -> tuple[int, int, int]:
    return tuple(min(255, channel + amount) for channel in color)


def darker(color: tuple[int, int, int], amount: int) -> tuple[int, int, int]:
    return tuple(max(0, channel - amount) for channel in color)


def draw_symbol_grid(img: Image.Image, theme: str, palette: slot_assets.Palette) -> None:
    draw = ImageDraw.Draw(img)
    reel = (54, 206, 666, 648)
    draw.rounded_rectangle(reel, radius=34, fill=(7, 9, 34, 232), outline=slot_assets.rgba(palette.primary, 245), width=6)
    draw.rounded_rectangle((74, 228, 646, 626), radius=26, outline=slot_assets.rgba((255, 255, 255), 80), width=2)

    symbols = LANDSCAPE_HOME_CARD_SYMBOLS[theme]
    cell_w = 102
    cell_h = 108
    gap_x = 10
    gap_y = 13
    start_x = 84
    start_y = 248
    for row in range(3):
        for col in range(5):
            x0 = start_x + col * (cell_w + gap_x)
            y0 = start_y + row * (cell_h + gap_y)
            cell = (x0, y0, x0 + cell_w, y0 + cell_h)
            fill = (19, 21, 56, 226) if row == 1 else (14, 16, 44, 212)
            outline = slot_assets.rgba(palette.accent if row == 1 else palette.primary, 110)
            draw.rounded_rectangle(cell, radius=16, fill=fill, outline=outline, width=2)
            symbol_name = symbols[(col + row * 2) % len(symbols)]
            symbol = Image.open(slot_assets.DRAWABLE / symbol_name).convert("RGBA")
            symbol.thumbnail((104, 104), Image.Resampling.LANCZOS)
            slot_assets.paste_center(img, symbol, (x0 + cell_w // 2, y0 + cell_h // 2 + 1))

    for x in (194, 306, 418, 530):
        draw.line((x, 232, x, 622), fill=slot_assets.rgba(palette.primary, 74), width=2)
    draw.line((84, 369, 636, 369), fill=slot_assets.rgba(palette.accent, 92), width=3)


def draw_feature_chip(draw: ImageDraw.ImageDraw, box: tuple[int, int, int, int], text: str, palette: slot_assets.Palette) -> None:
    draw.rounded_rectangle(
        box,
        radius=24,
        fill=slot_assets.rgba(darker(palette.dark, 4), 214),
        outline=slot_assets.rgba(palette.accent, 180),
        width=3,
    )
    slot_assets.draw_centered_text(
        draw,
        text,
        (box[0] + 10, box[1] + 4, box[2] - 10, box[3] - 4),
        max_size=31,
        min_size=20,
        fill=brighter(palette.accent, 8),
        stroke=darker(palette.dark, 2),
    )


def draw_landscape_card(theme: str, pressed: bool = False) -> Image.Image:
    palette = LANDSCAPE_HOME_CARD_THEMES[theme]
    w, h = 720, 980
    img = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    mask = slot_assets.rounded_panel_mask((w, h), 56)

    bg = slot_assets.vertical_gradient((w, h), brighter(palette.dark, 42), palette.dark, 255)
    glow = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    glow_draw = ImageDraw.Draw(glow)
    glow_draw.ellipse((-220, 96, 420, 720), fill=slot_assets.rgba(palette.primary, 82))
    glow_draw.ellipse((320, -190, 940, 430), fill=slot_assets.rgba(palette.secondary, 62))
    glow_draw.ellipse((160, 610, 830, 1200), fill=slot_assets.rgba(palette.accent, 46))
    bg.alpha_composite(glow.filter(ImageFilter.GaussianBlur(24)))
    bg.putalpha(mask)
    img.alpha_composite(bg)

    draw = ImageDraw.Draw(img)
    draw.rounded_rectangle((20, 20, w - 20, h - 20), radius=50, outline=slot_assets.rgba(palette.accent, 248), width=8)
    draw.rounded_rectangle((38, 38, w - 38, h - 38), radius=40, outline=slot_assets.rgba((255, 255, 255), 96), width=3)

    for i in range(46):
        x = 42 + (i * 139) % 636
        y = 62 + (i * 211) % 820
        alpha = 80 + (i % 4) * 34
        r = 3 + (i % 3)
        draw.ellipse((x, y, x + r, y + r), fill=slot_assets.rgba(palette.accent if i % 2 else palette.primary, alpha))

    draw.rounded_rectangle((246, 50, 474, 103), radius=25, fill=slot_assets.rgba(palette.secondary, 204), outline=slot_assets.rgba(palette.accent, 224), width=3)
    slot_assets.draw_centered_text(draw, "V SLOT", (258, 54, 462, 99), max_size=38, min_size=24, fill=(255, 248, 226), stroke=palette.dark)

    slot_assets.draw_centered_text(
        draw,
        palette.title,
        (48, 110, w - 48, 190),
        max_size=58,
        min_size=34,
        fill=(255, 248, 226),
        stroke=darker(palette.dark, 2),
    )

    draw_symbol_grid(img, theme, palette)

    draw.rounded_rectangle((74, 678, 646, 744), radius=30, fill=slot_assets.rgba((10, 12, 36), 212), outline=slot_assets.rgba(palette.primary, 180), width=3)
    draw_feature_chip(draw, (94, 690, 254, 734), "10 ЛИНИЙ", palette)
    draw_feature_chip(draw, (280, 690, 440, 734), "ВАЙЛД", palette)
    draw_feature_chip(draw, (466, 690, 626, 734), "ФРИСПИНЫ", palette)

    draw.rounded_rectangle((166, 794, 554, 900), radius=50, fill=slot_assets.rgba(palette.secondary, 216), outline=slot_assets.rgba(palette.accent, 255), width=7)
    draw.rounded_rectangle((184, 808, 536, 886), radius=40, outline=slot_assets.rgba((255, 255, 255), 88), width=2)
    slot_assets.draw_centered_text(draw, "ИГРАТЬ", (194, 815, 526, 883), max_size=58, min_size=36, fill=(255, 248, 226), stroke=palette.dark)

    for i in range(24):
        x = 68 + i * 25
        y = 930 + int(sin(i * 0.8) * 7)
        draw.ellipse((x, y, x + 8, y + 8), fill=slot_assets.rgba(palette.accent if i % 2 else palette.primary, 166))

    if pressed:
        shade = Image.new("RGBA", (w, h), (0, 0, 0, 72))
        shade.putalpha(mask.point(lambda p: min(p, 72)))
        img.alpha_composite(shade)
        img = img.transform((w, h), Image.Transform.AFFINE, (1, 0, 0, 0, 1, 7), resample=Image.Resampling.BICUBIC)
    return img


def main() -> None:
    for theme, palette in LANDSCAPE_HOME_CARD_THEMES.items():
        slot_assets.save_webp(
            draw_landscape_card(theme, pressed=False),
            f"{palette.card_name}_land_default.webp",
            quality=93,
        )
        slot_assets.save_webp(
            draw_landscape_card(theme, pressed=True),
            f"{palette.card_name}_land_pressed.webp",
            quality=92,
        )
        print(f"{palette.card_name}_land_default.webp")
        print(f"{palette.card_name}_land_pressed.webp")


if __name__ == "__main__":
    main()
