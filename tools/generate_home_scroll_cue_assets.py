#!/usr/bin/env python3
from __future__ import annotations

from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter

from vslot_asset_toolchain import verify_asset_toolchain


ROOT = Path(__file__).resolve().parents[1]
DRAWABLE = ROOT / "app" / "src" / "main" / "res" / "drawable-nodpi"


def rgba(color: tuple[int, int, int], alpha: int) -> tuple[int, int, int, int]:
    return color[0], color[1], color[2], alpha


def make_bottom_veil() -> Image.Image:
    width, height = 1080, 300
    img = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    px = img.load()
    for y in range(height):
        t = y / (height - 1)
        alpha = int((t**1.75) * 236)
        for x in range(width):
            edge = min(x, width - 1 - x) / (width / 2)
            side_glow = int((1 - edge) * 24)
            px[x, y] = (5, 8, 28, min(255, alpha + side_glow))

    glow = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    draw = ImageDraw.Draw(glow)
    for offset, color, alpha in (
        (52, (65, 231, 255), 72),
        (74, (255, 214, 86), 68),
        (98, (185, 87, 255), 58),
    ):
        draw.arc(
            (-width * 0.08, offset, width * 1.08, height + offset * 1.3),
            start=184,
            end=356,
            fill=rgba(color, alpha),
            width=5,
        )
    draw.line((0, height - 62, width, height - 88), fill=rgba((60, 231, 255), 42), width=4)
    draw.line((80, height - 38, width - 80, height - 58), fill=rgba((255, 219, 92), 54), width=3)
    img.alpha_composite(glow.filter(ImageFilter.GaussianBlur(7)))
    img.alpha_composite(glow)
    return img


def make_right_veil() -> Image.Image:
    width, height = 250, 980
    img = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    px = img.load()
    for x in range(width):
        t = x / (width - 1)
        alpha = int((t**1.65) * 230)
        for y in range(height):
            vertical_glow = int((1 - abs((y / height) - 0.5) * 2) * 22)
            px[x, y] = (5, 8, 28, min(255, alpha + vertical_glow))

    glow = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    draw = ImageDraw.Draw(glow)
    for x, color, alpha in (
        (width - 82, (65, 231, 255), 78),
        (width - 54, (255, 214, 86), 70),
        (width - 28, (185, 87, 255), 58),
    ):
        draw.arc(
            (x - 230, 20, x + 230, height - 20),
            start=272,
            end=88,
            fill=rgba(color, alpha),
            width=5,
        )
    draw.line((width - 72, 70, width - 42, height - 70), fill=rgba((60, 231, 255), 42), width=4)
    draw.line((width - 40, 120, width - 18, height - 120), fill=rgba((255, 219, 92), 52), width=3)
    img.alpha_composite(glow.filter(ImageFilter.GaussianBlur(7)))
    img.alpha_composite(glow)
    return img


def main() -> None:
    verify_asset_toolchain()
    DRAWABLE.mkdir(parents=True, exist_ok=True)
    assets = {
        "home_scroll_bottom_veil.webp": make_bottom_veil(),
        "home_scroll_right_veil.webp": make_right_veil(),
    }
    for name, image in assets.items():
        image.save(DRAWABLE / name, "WEBP", quality=94, method=6)
        print(name)


if __name__ == "__main__":
    main()
