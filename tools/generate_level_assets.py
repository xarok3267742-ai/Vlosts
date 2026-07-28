#!/usr/bin/env python3
from __future__ import annotations

from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

from vslot_asset_fonts import load_font, verify_font
from vslot_asset_toolchain import verify_asset_toolchain

ROOT = Path(__file__).resolve().parents[1]
DRAWABLE = ROOT / "app" / "src" / "main" / "res" / "drawable-nodpi"


def font(size: int) -> ImageFont.FreeTypeFont:
    return load_font(size, weight=700, width=82)


def rgba(color: tuple[int, int, int], alpha: int = 255) -> tuple[int, int, int, int]:
    return color[0], color[1], color[2], alpha


def save_webp(img: Image.Image, name: str, quality: int = 94) -> None:
    img.save(DRAWABLE / name, "WEBP", quality=quality, method=6, lossless=False)


def vertical_gradient(size: tuple[int, int], top: tuple[int, int, int], bottom: tuple[int, int, int]) -> Image.Image:
    w, h = size
    img = Image.new("RGBA", size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    for y in range(h):
        t = y / max(1, h - 1)
        color = tuple(int(top[i] * (1 - t) + bottom[i] * t) for i in range(3))
        draw.line((0, y, w, y), fill=rgba(color))
    return img


def draw_center_text(draw: ImageDraw.ImageDraw, text: str, box: tuple[int, int, int, int], size: int) -> None:
    selected = font(size)
    bbox = draw.textbbox((0, 0), text, font=selected, stroke_width=2)
    x = box[0] + (box[2] - box[0] - (bbox[2] - bbox[0])) // 2 - bbox[0]
    y = box[1] + (box[3] - box[1] - (bbox[3] - bbox[1])) // 2 - bbox[1]
    draw.text((x, y), text, font=selected, fill=(78, 44, 112, 255), stroke_width=2, stroke_fill=(255, 248, 192, 255))


def panel() -> Image.Image:
    img = Image.new("RGBA", (420, 118), (0, 0, 0, 0))
    shadow = Image.new("RGBA", img.size, (0, 0, 0, 0))
    shadow_draw = ImageDraw.Draw(shadow)
    shadow_draw.rounded_rectangle((10, 9, 410, 109), radius=25, fill=(0, 0, 0, 145))
    shadow = shadow.filter(ImageFilter.GaussianBlur(6))
    img.alpha_composite(shadow, (0, 3))

    body = vertical_gradient((420, 118), (18, 15, 54), (5, 5, 27))
    mask = Image.new("L", (420, 118), 0)
    ImageDraw.Draw(mask).rounded_rectangle((10, 8, 410, 108), radius=25, fill=255)
    body.putalpha(mask)
    img.alpha_composite(body)

    draw = ImageDraw.Draw(img)
    draw.rounded_rectangle((12, 10, 408, 106), radius=25, outline=(87, 231, 255, 255), width=5)
    draw.rounded_rectangle((25, 58, 395, 92), radius=18, fill=(14, 10, 37, 220), outline=(239, 205, 100, 255), width=3)
    draw.line((76, 54, 392, 54), fill=(231, 190, 88, 120), width=2)
    draw.line((33, 98, 386, 98), fill=(78, 229, 255, 70), width=2)

    draw.ellipse((17, 17, 79, 79), fill=(255, 218, 80, 255), outline=(255, 247, 176, 255), width=4)
    draw.ellipse((20, 20, 76, 76), outline=(127, 96, 42, 120), width=2)
    draw_center_text(draw, "LV", (19, 17, 77, 79), 24)

    highlight = Image.new("RGBA", img.size, (0, 0, 0, 0))
    hdraw = ImageDraw.Draw(highlight)
    hdraw.rounded_rectangle((20, 14, 400, 47), radius=18, fill=(255, 255, 255, 25))
    img.alpha_composite(highlight)
    return img


def fill() -> Image.Image:
    img = Image.new("RGBA", (360, 34), (0, 0, 0, 0))
    glow = Image.new("RGBA", img.size, (0, 0, 0, 0))
    glow_draw = ImageDraw.Draw(glow)
    glow_draw.rounded_rectangle((2, 5, 358, 30), radius=15, fill=(73, 233, 255, 150))
    glow = glow.filter(ImageFilter.GaussianBlur(4))
    img.alpha_composite(glow)

    bar = vertical_gradient((360, 34), (101, 239, 255), (53, 192, 220))
    mask = Image.new("L", (360, 34), 0)
    ImageDraw.Draw(mask).rounded_rectangle((2, 5, 358, 29), radius=14, fill=255)
    bar.putalpha(mask)
    img.alpha_composite(bar)
    draw = ImageDraw.Draw(img)
    draw.rounded_rectangle((3, 6, 357, 28), radius=13, outline=(189, 255, 255, 180), width=2)
    draw.line((18, 9, 340, 9), fill=(255, 255, 255, 90), width=2)
    return img


def track_glow() -> Image.Image:
    img = Image.new("RGBA", (360, 64), (0, 0, 0, 0))
    glow = Image.new("RGBA", img.size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(glow)
    draw.rounded_rectangle((8, 15, 352, 49), radius=19, fill=(73, 233, 255, 118))
    draw.rounded_rectangle((20, 20, 340, 44), radius=14, fill=(255, 216, 91, 52))
    glow = glow.filter(ImageFilter.GaussianBlur(7))
    img.alpha_composite(glow)

    draw = ImageDraw.Draw(img)
    draw.rounded_rectangle((8, 17, 352, 47), radius=16, outline=(101, 239, 255, 110), width=3)
    draw.line((28, 24, 332, 24), fill=(255, 255, 255, 64), width=2)
    draw.line((30, 43, 330, 43), fill=(255, 216, 91, 52), width=2)
    return img


def milestones() -> Image.Image:
    img = Image.new("RGBA", (360, 64), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    for index in range(6):
        x = int(18 + index * (324 / 5))
        dot = Image.new("RGBA", img.size, (0, 0, 0, 0))
        d = ImageDraw.Draw(dot)
        d.ellipse((x - 8, 24, x + 8, 40), fill=(255, 218, 80, 205), outline=(255, 250, 190, 220), width=2)
        d.ellipse((x - 4, 28, x + 4, 36), fill=(82, 47, 119, 180))
        img.alpha_composite(dot.filter(ImageFilter.GaussianBlur(0.25)))
        draw.line((x, 16, x, 48), fill=(255, 255, 255, 38), width=1)
    return img


def cap() -> Image.Image:
    img = Image.new("RGBA", (96, 96), (0, 0, 0, 0))
    glow = Image.new("RGBA", img.size, (0, 0, 0, 0))
    gdraw = ImageDraw.Draw(glow)
    gdraw.ellipse((18, 18, 78, 78), fill=(83, 237, 255, 155))
    glow = glow.filter(ImageFilter.GaussianBlur(9))
    img.alpha_composite(glow)

    draw = ImageDraw.Draw(img)
    draw.ellipse((24, 24, 72, 72), fill=(255, 218, 78, 255), outline=(255, 250, 190, 255), width=4)
    draw.ellipse((31, 31, 65, 65), fill=(27, 20, 70, 235), outline=(116, 239, 255, 210), width=3)
    draw.polygon(
        [(48, 20), (55, 38), (75, 38), (60, 51), (65, 72), (48, 59), (31, 72), (36, 51), (21, 38), (41, 38)],
        fill=(255, 241, 128, 238),
        outline=(255, 255, 226, 220),
    )
    draw.ellipse((42, 42, 54, 54), fill=(97, 234, 255, 245))
    return img


def pulse() -> Image.Image:
    img = Image.new("RGBA", (120, 120), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    draw.ellipse((24, 24, 96, 96), outline=(88, 238, 255, 118), width=5)
    draw.ellipse((34, 34, 86, 86), outline=(255, 220, 84, 90), width=4)
    draw.line((60, 10, 60, 32), fill=(255, 255, 255, 58), width=3)
    draw.line((60, 88, 60, 110), fill=(255, 255, 255, 44), width=3)
    draw.line((10, 60, 32, 60), fill=(255, 255, 255, 46), width=3)
    draw.line((88, 60, 110, 60), fill=(255, 255, 255, 46), width=3)
    return img.filter(ImageFilter.GaussianBlur(0.6))


def xp_readout_plate() -> Image.Image:
    img = Image.new("RGBA", (280, 76), (0, 0, 0, 0))
    glow = Image.new("RGBA", img.size, (0, 0, 0, 0))
    glow_draw = ImageDraw.Draw(glow)
    glow_draw.rounded_rectangle((12, 14, 268, 64), radius=24, fill=(70, 231, 255, 92))
    glow_draw.rounded_rectangle((30, 20, 250, 58), radius=19, fill=(255, 216, 88, 40))
    img.alpha_composite(glow.filter(ImageFilter.GaussianBlur(7)))

    body = vertical_gradient((280, 76), (25, 18, 65), (6, 7, 28))
    mask = Image.new("L", (280, 76), 0)
    ImageDraw.Draw(mask).rounded_rectangle((16, 14, 264, 62), radius=23, fill=235)
    body.putalpha(mask)
    img.alpha_composite(body)

    draw = ImageDraw.Draw(img)
    draw.rounded_rectangle((16, 14, 264, 62), radius=23, outline=(255, 219, 84, 224), width=3)
    draw.rounded_rectangle((21, 19, 259, 57), radius=19, outline=(94, 236, 255, 138), width=2)
    draw.line((40, 24, 240, 24), fill=(255, 255, 255, 58), width=2)
    draw.line((45, 57, 235, 57), fill=(79, 231, 255, 52), width=2)

    sparkle = Image.new("RGBA", img.size, (0, 0, 0, 0))
    sdraw = ImageDraw.Draw(sparkle)
    for x, y, radius, alpha in ((42, 38, 6, 100), (235, 37, 5, 88), (221, 23, 3, 76)):
        sdraw.line((x - radius, y, x + radius, y), fill=(255, 251, 190, alpha), width=2)
        sdraw.line((x, y - radius, x, y + radius), fill=(255, 251, 190, alpha), width=2)
    img.alpha_composite(sparkle.filter(ImageFilter.GaussianBlur(0.5)))
    return img


def main() -> None:
    verify_asset_toolchain()
    verify_font()
    save_webp(panel(), "level_progress_panel.webp")
    save_webp(fill(), "level_progress_fill.webp")
    save_webp(track_glow(), "level_progress_track_glow.webp")
    save_webp(milestones(), "level_progress_milestones.webp")
    save_webp(cap(), "level_progress_cap.webp")
    save_webp(pulse(), "level_progress_pulse.webp")
    save_webp(xp_readout_plate(), "home_xp_readout_plate.webp")


if __name__ == "__main__":
    main()
