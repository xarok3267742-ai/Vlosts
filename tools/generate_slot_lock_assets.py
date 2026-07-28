#!/usr/bin/env python3
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter

import generate_new_slot_assets as slot_assets


ROOT = Path(__file__).resolve().parents[1]
DRAWABLE = ROOT / "app" / "src" / "main" / "res" / "drawable-nodpi"


@dataclass(frozen=True)
class LockPalette:
    level: int
    primary: tuple[int, int, int]
    secondary: tuple[int, int, int]
    accent: tuple[int, int, int]
    shadow: tuple[int, int, int]


LOCK_PALETTES = (
    LockPalette(
        level=2,
        primary=(39, 230, 255),
        secondary=(255, 66, 194),
        accent=(255, 236, 118),
        shadow=(7, 10, 35),
    ),
    LockPalette(
        level=3,
        primary=(255, 207, 75),
        secondary=(36, 215, 196),
        accent=(255, 247, 172),
        shadow=(38, 18, 9),
    ),
    LockPalette(
        level=4,
        primary=(102, 231, 255),
        secondary=(255, 180, 229),
        accent=(255, 240, 154),
        shadow=(3, 24, 48),
    ),
)


def draw_lock(draw: ImageDraw.ImageDraw, x: int, y: int, palette: LockPalette) -> None:
    shackle = (x + 48, y + 0, x + 214, y + 176)
    draw.arc((shackle[0] + 8, shackle[1] + 14, shackle[2] - 8, shackle[3] + 48), 186, 354, fill=(0, 0, 0, 170), width=40)
    draw.arc(shackle, 186, 354, fill=slot_assets.rgba(palette.accent, 255), width=32)
    draw.arc((shackle[0] + 20, shackle[1] + 20, shackle[2] - 20, shackle[3] + 24), 188, 352, fill=slot_assets.rgba(palette.primary, 230), width=13)

    body = (x + 18, y + 122, x + 244, y + 296)
    draw.rounded_rectangle((body[0] + 9, body[1] + 12, body[2] + 9, body[3] + 12), radius=34, fill=(0, 0, 0, 145))
    draw.rounded_rectangle(body, radius=34, fill=slot_assets.rgba(palette.primary, 236), outline=slot_assets.rgba(palette.accent, 255), width=7)
    draw.rounded_rectangle((body[0] + 18, body[1] + 18, body[2] - 18, body[3] - 18), radius=24, outline=(255, 255, 255, 86), width=3)
    draw.rounded_rectangle((body[0] + 36, body[1] + 26, body[2] - 36, body[1] + 62), radius=16, fill=(255, 255, 255, 55))
    draw.ellipse((x + 106, y + 180, x + 156, y + 232), fill=slot_assets.rgba(palette.shadow, 245), outline=slot_assets.rgba(palette.accent, 210), width=4)
    draw.rounded_rectangle((x + 119, y + 220, x + 143, y + 268), radius=10, fill=slot_assets.rgba(palette.shadow, 245))


def draw_unlock_overlay(palette: LockPalette) -> Image.Image:
    w, h = 980, 620
    img = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    mask = slot_assets.rounded_panel_mask((w, h), 42)

    dark = slot_assets.vertical_gradient((w, h), (8, 9, 24), palette.shadow, 226)
    dark.putalpha(mask.point(lambda p: min(p, 236)))
    img.alpha_composite(dark)

    glow = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    glow_draw = ImageDraw.Draw(glow)
    glow_draw.ellipse((-80, 70, 490, 680), fill=slot_assets.rgba(palette.primary, 42))
    glow_draw.ellipse((500, -160, 1160, 440), fill=slot_assets.rgba(palette.secondary, 38))
    glow = glow.filter(ImageFilter.GaussianBlur(24))
    glow.putalpha(mask.point(lambda p: min(p, 124)))
    img.alpha_composite(glow)

    draw = ImageDraw.Draw(img)
    draw.rounded_rectangle((18, 18, w - 18, h - 18), radius=38, outline=slot_assets.rgba(palette.accent, 245), width=8)
    draw.rounded_rectangle((36, 36, w - 36, h - 36), radius=30, outline=(255, 255, 255, 84), width=3)

    for offset in range(-120, 920, 172):
        draw.line((offset, h + 18, offset + 188, -18), fill=slot_assets.rgba(palette.primary, 11), width=9)
        draw.line((offset + 58, h + 18, offset + 246, -18), fill=slot_assets.rgba(palette.secondary, 8), width=4)

    draw.rounded_rectangle(
        (104, 30, 876, 592),
        radius=62,
        fill=slot_assets.rgba((7, 9, 27), 224),
        outline=slot_assets.rgba(palette.accent, 126),
        width=3,
    )
    draw.rounded_rectangle(
        (132, 58, 848, 564),
        radius=52,
        outline=slot_assets.rgba((255, 255, 255), 46),
        width=2,
    )

    plate_shadow = (256, 78, 724, 216)
    draw.rounded_rectangle((plate_shadow[0] + 8, plate_shadow[1] + 12, plate_shadow[2] + 8, plate_shadow[3] + 12), radius=52, fill=(0, 0, 0, 150))
    draw.rounded_rectangle(plate_shadow, radius=52, fill=slot_assets.rgba((13, 15, 40), 226), outline=slot_assets.rgba(palette.accent, 245), width=6)
    draw.rounded_rectangle((288, 104, 692, 190), radius=38, outline=slot_assets.rgba(palette.primary, 190), width=3)
    slot_assets.draw_centered_text(
        draw,
        "ОТКРОЕТСЯ",
        (290, 92, 690, 150),
        fill=(255, 255, 255),
        stroke=palette.shadow,
        max_size=42,
        min_size=28,
    )
    slot_assets.draw_centered_text(
        draw,
        f"УРОВЕНЬ {palette.level}",
        (276, 138, 704, 208),
        fill=palette.accent,
        stroke=palette.shadow,
        max_size=58,
        min_size=36,
    )

    lock_layer = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    lock_draw = ImageDraw.Draw(lock_layer)
    draw_lock(lock_draw, 359, 214, palette)
    lock_glow = lock_layer.getchannel("A").filter(ImageFilter.GaussianBlur(18)).point(lambda p: p * 155 // 255)
    lock_glow_layer = Image.new("RGBA", (w, h), slot_assets.rgba(palette.primary, 255))
    lock_glow_layer.putalpha(lock_glow)
    img.alpha_composite(lock_glow_layer)
    img.alpha_composite(lock_layer)

    draw = ImageDraw.Draw(img)
    draw.rounded_rectangle((230, 526, 750, 584), radius=26, fill=slot_assets.rgba((8, 10, 28), 210), outline=slot_assets.rgba(palette.accent, 172), width=3)
    slot_assets.draw_centered_text(
        draw,
        "ИГРАЙТЕ, ЧТОБЫ ОТКРЫТЬ",
        (250, 532, 730, 578),
        fill=(255, 255, 255),
        stroke=palette.shadow,
        max_size=34,
        min_size=22,
    )
    return img


def main() -> None:
    for palette in LOCK_PALETTES:
        output = draw_unlock_overlay(palette)
        output.save(
            DRAWABLE / f"slot_card_lock_level_{palette.level}.webp",
            "WEBP",
            quality=94,
            method=6,
            lossless=False,
        )
        print(f"slot_card_lock_level_{palette.level}.webp")


if __name__ == "__main__":
    main()
