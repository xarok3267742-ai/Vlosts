#!/usr/bin/env python3
from __future__ import annotations

from dataclasses import dataclass
from math import cos, pi, sin
from pathlib import Path
from random import Random

from PIL import Image, ImageDraw, ImageFilter

from vslot_asset_toolchain import verify_asset_toolchain


ROOT = Path(__file__).resolve().parents[1]
DRAWABLE = ROOT / "app" / "src" / "main" / "res" / "drawable-nodpi"
SIZE = (1440, 2560)


@dataclass(frozen=True)
class BgTheme:
    output: str
    top: tuple[int, int, int]
    mid: tuple[int, int, int]
    bottom: tuple[int, int, int]
    primary: tuple[int, int, int]
    secondary: tuple[int, int, int]
    accent: tuple[int, int, int]


THEMES = {
    "neon": BgTheme(
        output="nn_bg.webp",
        top=(12, 16, 50),
        mid=(20, 52, 105),
        bottom=(11, 13, 45),
        primary=(31, 225, 255),
        secondary=(255, 52, 187),
        accent=(255, 222, 87),
    ),
    "pharaoh": BgTheme(
        output="pg_bg.webp",
        top=(43, 25, 14),
        mid=(115, 76, 23),
        bottom=(31, 19, 12),
        primary=(255, 199, 62),
        secondary=(17, 207, 187),
        accent=(255, 126, 41),
    ),
    "ocean": BgTheme(
        output="op_bg.webp",
        top=(7, 37, 67),
        mid=(27, 127, 151),
        bottom=(4, 48, 70),
        primary=(104, 229, 255),
        secondary=(255, 179, 229),
        accent=(255, 215, 94),
    ),
}


def rgba(color: tuple[int, int, int], alpha: int = 255) -> tuple[int, int, int, int]:
    return color[0], color[1], color[2], alpha


def mix(a: tuple[int, int, int], b: tuple[int, int, int], t: float) -> tuple[int, int, int]:
    return tuple(int(a[i] * (1 - t) + b[i] * t) for i in range(3))


def vertical_gradient(theme: BgTheme) -> Image.Image:
    width, height = SIZE
    img = Image.new("RGBA", SIZE, rgba(theme.mid))
    draw = ImageDraw.Draw(img)
    for y in range(height):
        t = y / (height - 1)
        if t < 0.48:
            color = mix(theme.top, theme.mid, t / 0.48)
        else:
            color = mix(theme.mid, theme.bottom, (t - 0.48) / 0.52)
        draw.line((0, y, width, y), fill=rgba(color))
    return img


def add_glow(base: Image.Image, bbox: tuple[int, int, int, int], color: tuple[int, int, int], alpha: int, blur: int) -> None:
    layer = Image.new("RGBA", SIZE, (0, 0, 0, 0))
    ImageDraw.Draw(layer).ellipse(bbox, fill=rgba(color, alpha))
    base.alpha_composite(layer.filter(ImageFilter.GaussianBlur(blur)))


def add_starfield(base: Image.Image, theme: BgTheme, seed: int) -> None:
    rng = Random(seed)
    draw = ImageDraw.Draw(base)
    width, height = SIZE
    for _ in range(210):
        x = rng.randrange(34, width - 34)
        y = rng.randrange(28, height - 28)
        r = rng.choice((1, 1, 2, 2, 3))
        color = theme.primary if rng.random() < 0.45 else (255, 246, 210)
        alpha = rng.randrange(58, 150)
        draw.ellipse((x - r, y - r, x + r, y + r), fill=rgba(color, alpha))
    for _ in range(34):
        x = rng.randrange(60, width - 60)
        y = rng.randrange(80, height - 80)
        size = rng.randrange(10, 28)
        color = theme.accent if rng.random() < 0.35 else theme.primary
        draw.line((x - size, y, x + size, y), fill=rgba(color, 70), width=2)
        draw.line((x, y - size, x, y + size), fill=rgba(color, 70), width=2)


def add_vignette(base: Image.Image, strength: int = 132) -> None:
    width, height = SIZE
    mask = Image.new("L", SIZE, 0)
    px = mask.load()
    cx, cy = width / 2, height / 2
    max_dist = ((cx * cx) + (cy * cy)) ** 0.5
    for y in range(height):
        for x in range(width):
            dx = (x - cx) * 1.08
            dy = (y - cy) * 0.88
            t = min(1.0, ((dx * dx + dy * dy) ** 0.5) / max_dist)
            px[x, y] = int((t ** 1.85) * strength)
    layer = Image.new("RGBA", SIZE, (0, 0, 0, 0))
    layer.putalpha(mask)
    base.alpha_composite(layer)


def add_neon_details(base: Image.Image, theme: BgTheme) -> None:
    draw = ImageDraw.Draw(base)
    for i in range(12):
        y = 340 + i * 142
        draw.line((-120, y, 1560, y - 240), fill=rgba(theme.primary, 34), width=3)
    for i in range(9):
        x = 110 + i * 152
        draw.line((x, 0, x - 330, 2560), fill=rgba(theme.secondary, 22), width=2)
    for x in (90, 280, 1110, 1320):
        draw.rounded_rectangle((x, 1440, x + 84, 2290), radius=18, outline=rgba(theme.primary, 54), width=5)
        for y in range(1490, 2240, 92):
            draw.rectangle((x + 18, y, x + 66, y + 34), fill=rgba(theme.secondary, 36))


def add_pharaoh_details(base: Image.Image, theme: BgTheme) -> None:
    draw = ImageDraw.Draw(base)
    for x, scale in ((86, 1.0), (1106, 0.88)):
        h = int(760 * scale)
        y0 = 1420
        draw.polygon(
            [(x, y0 + h), (x + int(190 * scale), y0), (x + int(380 * scale), y0 + h)],
            fill=rgba((92, 52, 20), 72),
            outline=rgba(theme.accent, 82),
        )
        draw.line((x + int(190 * scale), y0 + 24, x + int(190 * scale), y0 + h - 30), fill=rgba(theme.primary, 52), width=4)
    for i in range(14):
        x = -80 + i * 116
        draw.polygon(
            [(x, 2320), (x + 58, 2195), (x + 116, 2320)],
            outline=rgba(theme.primary, 46),
            fill=rgba((87, 47, 16), 42),
        )
    for i in range(7):
        y = 220 + i * 206
        draw.arc((-120, y, 1540, y + 520), 190, 350, fill=rgba(theme.secondary, 40), width=4)


def add_ocean_details(base: Image.Image, theme: BgTheme) -> None:
    draw = ImageDraw.Draw(base)
    for i in range(14):
        y = 250 + i * 148
        points = []
        for x in range(-80, 1521, 48):
            wave = sin((x / 130) + i * 0.72) * 24
            points.append((x, int(y + wave)))
        draw.line(points, fill=rgba(theme.primary, 38), width=4)
    for i in range(8):
        x = 120 + i * 170
        draw.arc((x - 130, 1380, x + 220, 2290), 206, 336, fill=rgba(theme.secondary, 42), width=5)
    for i in range(16):
        cx = 80 + i * 92
        cy = 1840 + int(sin(i * 0.9) * 130)
        r = 18 + (i % 4) * 7
        draw.ellipse((cx - r, cy - r, cx + r, cy + r), outline=rgba((230, 255, 255), 70), width=3)


def add_coin_orbs(base: Image.Image, theme: BgTheme, seed: int) -> None:
    rng = Random(seed)
    draw = ImageDraw.Draw(base)
    for _ in range(18):
        r = rng.randrange(26, 74)
        x = rng.randrange(-30, SIZE[0] - r)
        y = rng.randrange(70, SIZE[1] - 80)
        fill = rgba(theme.accent, rng.randrange(36, 76))
        outline = rgba((255, 248, 190), rng.randrange(58, 116))
        draw.ellipse((x, y, x + r, y + r), fill=fill, outline=outline, width=max(2, r // 18))
        draw.ellipse((x + r * 0.22, y + r * 0.18, x + r * 0.55, y + r * 0.45), fill=rgba((255, 255, 255), 28))


def build(theme_name: str, theme: BgTheme) -> Image.Image:
    img = vertical_gradient(theme)
    add_glow(img, (-400, -250, 720, 920), theme.primary, 84, 90)
    add_glow(img, (620, 160, 1780, 1180), theme.secondary, 62, 110)
    add_glow(img, (-260, 1540, 620, 2760), theme.accent, 52, 105)
    add_glow(img, (760, 1520, 1760, 2740), theme.primary, 48, 120)
    add_starfield(img, theme, seed=1100 + len(theme.output))
    if theme_name == "neon":
        add_neon_details(img, theme)
    elif theme_name == "pharaoh":
        add_pharaoh_details(img, theme)
    elif theme_name == "ocean":
        add_ocean_details(img, theme)
    add_coin_orbs(img, theme, seed=2700 + len(theme.output))
    add_vignette(img, 112)
    return img


def main() -> None:
    verify_asset_toolchain()
    for name, theme in THEMES.items():
        image = build(name, theme)
        image.save(DRAWABLE / theme.output, "WEBP", quality=94, method=6)
        print(f"{theme.output}: {image.size}")


if __name__ == "__main__":
    main()
