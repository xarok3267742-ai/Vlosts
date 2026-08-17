#!/usr/bin/env python3
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter

from vslot_asset_toolchain import verify_asset_toolchain


ROOT = Path(__file__).resolve().parents[1]
DRAWABLE = ROOT / "app" / "src" / "main" / "res" / "drawable-nodpi"


@dataclass(frozen=True)
class Theme:
    suffix: str
    dark: tuple[int, int, int]
    primary: tuple[int, int, int]
    secondary: tuple[int, int, int]
    accent: tuple[int, int, int]


THEMES = (
    Theme("", (12, 8, 36), (114, 239, 255), (190, 78, 255), (255, 226, 78)),
    Theme("roman", (42, 22, 9), (255, 198, 76), (170, 92, 255), (255, 236, 140)),
    Theme("neon", (7, 10, 36), (38, 232, 255), (255, 52, 188), (255, 232, 82)),
    Theme("pharaoh", (42, 23, 9), (255, 204, 72), (20, 209, 189), (255, 126, 41)),
    Theme("ocean", (3, 28, 50), (103, 231, 255), (184, 255, 247), (255, 216, 96)),
)


def rgba(color: tuple[int, int, int], alpha: int) -> tuple[int, int, int, int]:
    return color[0], color[1], color[2], alpha


def asset_name(suffix: str) -> str:
    return f"paytable_bonus_lane_{suffix}.webp" if suffix else "paytable_bonus_lane.webp"


def create_asset(theme: Theme) -> Image.Image:
    width, height = 760, 110
    image = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    layer = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    draw = ImageDraw.Draw(layer)

    draw.rounded_rectangle(
        (18, 16, width - 18, height - 16),
        radius=34,
        fill=rgba(theme.dark, 62),
        outline=rgba(theme.primary, 162),
        width=4,
    )
    draw.rounded_rectangle(
        (42, 28, width - 42, height - 28),
        radius=24,
        outline=rgba(theme.secondary, 108),
        width=3,
    )

    glow = layer.filter(ImageFilter.GaussianBlur(10))
    wide_glow = layer.filter(ImageFilter.GaussianBlur(20))
    image.alpha_composite(wide_glow)
    image.alpha_composite(glow)
    image.alpha_composite(layer)
    return image


def main() -> None:
    verify_asset_toolchain()
    for theme in THEMES:
        output = DRAWABLE / asset_name(theme.suffix)
        create_asset(theme).save(output, "WEBP", quality=94, method=6)
        print(output.name)


if __name__ == "__main__":
    main()
