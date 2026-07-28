#!/usr/bin/env python3
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
import math
import random

from PIL import Image, ImageDraw, ImageFilter

from vslot_asset_toolchain import verify_asset_toolchain


ROOT = Path(__file__).resolve().parents[1]
DRAWABLE = ROOT / "app" / "src" / "main" / "res" / "drawable-nodpi"
SIZE = (260, 1020)


@dataclass(frozen=True)
class BrakeTheme:
    suffix: str
    shadow: tuple[int, int, int]
    metal_dark: tuple[int, int, int]
    metal_mid: tuple[int, int, int]
    rim: tuple[int, int, int]
    glow: tuple[int, int, int]
    accent: tuple[int, int, int]


THEMES = (
    BrakeTheme(
        suffix="",
        shadow=(12, 6, 34),
        metal_dark=(54, 28, 94),
        metal_mid=(104, 55, 166),
        rim=(190, 130, 255),
        glow=(152, 78, 255),
        accent=(255, 216, 106),
    ),
    BrakeTheme(
        suffix="roman",
        shadow=(30, 13, 18),
        metal_dark=(77, 39, 32),
        metal_mid=(151, 82, 50),
        rim=(255, 202, 116),
        glow=(228, 107, 58),
        accent=(255, 238, 180),
    ),
    BrakeTheme(
        suffix="neon",
        shadow=(3, 8, 34),
        metal_dark=(8, 45, 74),
        metal_mid=(14, 122, 158),
        rim=(58, 232, 255),
        glow=(255, 62, 206),
        accent=(255, 228, 92),
    ),
    BrakeTheme(
        suffix="pharaoh",
        shadow=(42, 22, 6),
        metal_dark=(102, 62, 18),
        metal_mid=(189, 122, 32),
        rim=(255, 203, 63),
        glow=(36, 214, 190),
        accent=(255, 130, 38),
    ),
    BrakeTheme(
        suffix="ocean",
        shadow=(2, 23, 43),
        metal_dark=(8, 66, 92),
        metal_mid=(31, 130, 152),
        rim=(132, 238, 255),
        glow=(178, 255, 245),
        accent=(255, 218, 96),
    ),
)


def rgba(color: tuple[int, int, int], alpha: int) -> tuple[int, int, int, int]:
    return color[0], color[1], color[2], alpha


def mix(
    a: tuple[int, int, int],
    b: tuple[int, int, int],
    t: float,
) -> tuple[int, int, int]:
    t = max(0.0, min(1.0, t))
    return tuple(int(a[i] * (1 - t) + b[i] * t) for i in range(3))


def vertical_gradient(
    size: tuple[int, int],
    top: tuple[int, int, int, int],
    bottom: tuple[int, int, int, int],
) -> Image.Image:
    width, height = size
    img = Image.new("RGBA", size, (0, 0, 0, 0))
    px = img.load()
    for y in range(height):
        t = y / max(1, height - 1)
        color = tuple(int(top[i] * (1 - t) + bottom[i] * t) for i in range(4))
        for x in range(width):
            px[x, y] = color
    return img


def rounded_mask(size: tuple[int, int], box: tuple[int, int, int, int], radius: int) -> Image.Image:
    mask = Image.new("L", size, 0)
    draw = ImageDraw.Draw(mask)
    draw.rounded_rectangle(box, radius=radius, fill=255)
    return mask


def add_metal_band(
    img: Image.Image,
    theme: BrakeTheme,
    box: tuple[int, int, int, int],
    radius: int,
    flip: bool = False,
) -> None:
    width = box[2] - box[0]
    height = box[3] - box[1]
    base = vertical_gradient(
        (width, height),
        rgba(theme.metal_mid if not flip else theme.metal_dark, 232),
        rgba(theme.metal_dark if not flip else theme.metal_mid, 238),
    )
    band = Image.new("RGBA", img.size, (0, 0, 0, 0))
    mask = rounded_mask((width, height), (0, 0, width - 1, height - 1), radius)
    band.paste(base, box[:2], mask)

    band_draw = ImageDraw.Draw(band)
    band_draw.rounded_rectangle(box, radius=radius, outline=rgba(theme.rim, 178), width=3)
    band_draw.rounded_rectangle(
        (box[0] + 10, box[1] + 10, box[2] - 10, box[3] - 10),
        radius=max(8, radius - 10),
        outline=rgba(theme.accent, 76),
        width=2,
    )
    for index in range(5):
        x = box[0] + 18 + index * ((width - 36) / 4)
        band_draw.line(
            (x, box[1] + 11, x + (22 if not flip else -22), box[3] - 13),
            fill=rgba((255, 255, 255), 26),
            width=3,
        )
    img.alpha_composite(band.filter(ImageFilter.GaussianBlur(2)))
    img.alpha_composite(band)


def add_side_rail(img: Image.Image, theme: BrakeTheme, x: int) -> None:
    _, height = SIZE
    rail = Image.new("RGBA", SIZE, (0, 0, 0, 0))
    draw = ImageDraw.Draw(rail)
    draw.rounded_rectangle((x, 116, x + 26, height - 116), radius=16, fill=rgba(theme.metal_dark, 128))
    draw.line((x + 13, 132, x + 13, height - 132), fill=rgba(theme.rim, 96), width=2)
    draw.line((x + 4, 160, x + 4, height - 160), fill=rgba(theme.shadow, 108), width=4)
    img.alpha_composite(rail.filter(ImageFilter.GaussianBlur(5)))
    img.alpha_composite(rail)


def add_motion_streaks(img: Image.Image, theme: BrakeTheme) -> None:
    width, height = SIZE
    rng = random.Random(theme.suffix or "violet")
    streaks = Image.new("RGBA", SIZE, (0, 0, 0, 0))
    draw = ImageDraw.Draw(streaks)

    for index in range(34):
        y = rng.randint(190, height - 190)
        length = rng.randint(42, 112)
        x = rng.randint(-26, width - 34)
        alpha = rng.randint(14, 42)
        color = theme.rim if index % 3 else theme.accent
        draw.line((x, y, x + length, y + rng.randint(-18, 18)), fill=rgba(color, alpha), width=rng.randint(1, 3))

    for y in range(226, height - 224, 68):
        pulse = int(42 + 20 * math.sin(y / 52.0))
        draw.line((30, y, width - 30, y - 18), fill=rgba(theme.glow, pulse), width=2)

    img.alpha_composite(streaks.filter(ImageFilter.GaussianBlur(1)))


def make_asset(theme: BrakeTheme) -> Image.Image:
    width, height = SIZE
    img = Image.new("RGBA", SIZE, (0, 0, 0, 0))

    shadow = Image.new("RGBA", SIZE, (0, 0, 0, 0))
    shadow_draw = ImageDraw.Draw(shadow)
    shadow_draw.rounded_rectangle((16, 76, width - 16, height - 76), radius=34, fill=rgba(theme.shadow, 76))
    shadow_draw.rectangle((34, 210, width - 34, height - 210), fill=(0, 0, 0, 0))
    img.alpha_composite(shadow.filter(ImageFilter.GaussianBlur(16)))

    add_side_rail(img, theme, 14)
    add_side_rail(img, theme, width - 40)
    add_motion_streaks(img, theme)

    glow = Image.new("RGBA", SIZE, (0, 0, 0, 0))
    glow_draw = ImageDraw.Draw(glow)
    glow_draw.rounded_rectangle((24, 96, width - 24, height - 96), radius=28, outline=rgba(theme.glow, 96), width=5)
    glow_draw.rounded_rectangle((42, 150, width - 42, height - 150), radius=22, outline=rgba(theme.accent, 42), width=2)
    img.alpha_composite(glow.filter(ImageFilter.GaussianBlur(12)))

    add_metal_band(img, theme, (18, 34, width - 18, 168), 34)
    add_metal_band(img, theme, (18, height - 168, width - 18, height - 34), 34, flip=True)

    draw = ImageDraw.Draw(img)
    for y in (188, height - 188):
        draw.rounded_rectangle((32, y - 5, width - 32, y + 5), radius=5, fill=rgba(theme.shadow, 128))
        draw.line((46, y - 1, width - 46, y - 1), fill=rgba(theme.rim, 130), width=2)

    # Small readable highlights make the clamp feel like physical chrome while preserving the transparent center.
    for x in (74, width // 2, width - 74):
        draw.ellipse((x - 7, 70, x + 7, 84), fill=rgba(theme.accent, 176))
        draw.ellipse((x - 7, height - 84, x + 7, height - 70), fill=rgba(theme.accent, 168))
    return img


def main() -> None:
    verify_asset_toolchain()
    DRAWABLE.mkdir(parents=True, exist_ok=True)
    for theme in THEMES:
        suffix = f"_{theme.suffix}" if theme.suffix else ""
        path = DRAWABLE / f"reel_brake_clamp{suffix}.webp"
        make_asset(theme).save(path, "WEBP", quality=94, method=6)
        print(path.name)


if __name__ == "__main__":
    main()
