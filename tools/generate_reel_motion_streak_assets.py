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
class StreakTheme:
    suffix: str
    shadow: tuple[int, int, int]
    primary: tuple[int, int, int]
    secondary: tuple[int, int, int]
    accent: tuple[int, int, int]


THEMES = (
    StreakTheme("", (14, 7, 40), (174, 83, 255), (255, 61, 187), (255, 219, 105)),
    StreakTheme("roman", (35, 17, 16), (255, 199, 108), (215, 91, 55), (255, 240, 177)),
    StreakTheme("neon", (3, 10, 34), (42, 229, 255), (255, 58, 205), (255, 227, 86)),
    StreakTheme("pharaoh", (42, 22, 6), (255, 202, 65), (43, 220, 198), (255, 130, 39)),
    StreakTheme("ocean", (2, 25, 45), (120, 235, 255), (255, 178, 227), (255, 219, 98)),
)


def rgba(color: tuple[int, int, int], alpha: int) -> tuple[int, int, int, int]:
    return color[0], color[1], color[2], alpha


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


def make_asset(theme: StreakTheme) -> Image.Image:
    width, height = SIZE
    rng = random.Random(theme.suffix or "violet")
    img = Image.new("RGBA", SIZE, (0, 0, 0, 0))

    veil = vertical_gradient(SIZE, rgba(theme.shadow, 6), rgba(theme.shadow, 34))
    img.alpha_composite(veil)

    streaks = Image.new("RGBA", SIZE, (0, 0, 0, 0))
    draw = ImageDraw.Draw(streaks)
    for index in range(62):
        x = rng.randint(-40, width - 18)
        y = rng.randint(-80, height + 80)
        length = rng.randint(120, 310)
        slant = rng.randint(-44, 58)
        alpha = rng.randint(28, 96)
        color = theme.primary if index % 3 == 0 else theme.secondary if index % 3 == 1 else theme.accent
        draw.line((x, y, x + slant, y + length), fill=rgba(color, alpha), width=rng.randint(2, 6))

    for y in range(-80, height + 120, 92):
        pulse = int(56 + 28 * math.sin(y / 48.0))
        draw.rounded_rectangle((24, y, width - 24, y + 22), radius=11, fill=rgba(theme.primary, pulse))
        draw.line((42, y + 26, width - 42, y + 8), fill=rgba(theme.accent, 74), width=3)

    img.alpha_composite(streaks.filter(ImageFilter.GaussianBlur(7)))
    img.alpha_composite(streaks.filter(ImageFilter.GaussianBlur(1)))

    rim = Image.new("RGBA", SIZE, (0, 0, 0, 0))
    rim_draw = ImageDraw.Draw(rim)
    rim_draw.rounded_rectangle((10, 10, width - 10, height - 10), radius=28, outline=rgba(theme.primary, 88), width=4)
    rim_draw.line((34, 0, 34, height), fill=rgba((255, 255, 255), 38), width=2)
    rim_draw.line((width - 34, 0, width - 34, height), fill=rgba(theme.accent, 48), width=2)
    img.alpha_composite(rim.filter(ImageFilter.GaussianBlur(5)))
    img.alpha_composite(rim)

    # Keep the asset transparent enough that symbols remain visible below the speed veil.
    alpha = img.getchannel("A").point(lambda value: min(value, 168))
    img.putalpha(alpha)
    return img


def main() -> None:
    verify_asset_toolchain()
    DRAWABLE.mkdir(parents=True, exist_ok=True)
    for theme in THEMES:
        suffix = f"_{theme.suffix}" if theme.suffix else ""
        path = DRAWABLE / f"reel_motion_streak{suffix}.webp"
        make_asset(theme).save(path, "WEBP", quality=94, method=6)
        print(path.name)


if __name__ == "__main__":
    main()
