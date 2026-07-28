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
    primary: tuple[int, int, int]
    secondary: tuple[int, int, int]
    accent: tuple[int, int, int]


THEMES = (
    Theme("", (102, 241, 255), (182, 77, 255), (255, 226, 83)),
    Theme("roman", (255, 197, 76), (167, 86, 255), (255, 235, 137)),
    Theme("neon", (38, 232, 255), (255, 51, 188), (255, 232, 82)),
    Theme("pharaoh", (255, 204, 72), (20, 209, 189), (255, 126, 41)),
    Theme("ocean", (103, 231, 255), (184, 255, 247), (255, 216, 96)),
)


def rgba(color: tuple[int, int, int], alpha: int) -> tuple[int, int, int, int]:
    return color[0], color[1], color[2], alpha


def asset_name(suffix: str) -> str:
    return f"free_spins_rail_charge_{suffix}.webp" if suffix else "free_spins_rail_charge.webp"


def create_asset(theme: Theme) -> Image.Image:
    width, height = 420, 120
    img = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    glow = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    draw = ImageDraw.Draw(glow)

    draw.rounded_rectangle(
        (20, 18, width - 20, height - 18),
        radius=44,
        outline=rgba(theme.primary, 170),
        width=7,
    )
    draw.rounded_rectangle(
        (38, 31, width - 38, height - 31),
        radius=31,
        outline=rgba(theme.secondary, 124),
        width=4,
    )
    draw.arc((44, 12, width - 44, height + 52), 198, 340, fill=rgba(theme.accent, 178), width=6)
    draw.arc((72, -30, width - 72, height - 6), 18, 166, fill=rgba(theme.primary, 116), width=4)

    for x, y, radius, alpha in (
        (56, 60, 9, 220),
        (86, 33, 5, 150),
        (124, 86, 4, 132),
        (width - 56, 60, 9, 220),
        (width - 92, 33, 5, 150),
        (width - 126, 86, 4, 132),
        (width // 2, 20, 4, 145),
        (width // 2 + 42, 98, 4, 120),
    ):
        draw.ellipse((x - radius, y - radius, x + radius, y + radius), fill=rgba(theme.accent, alpha))

    for x in (108, 158, 212, 266, 318):
        draw.line((x - 8, 64, x + 8, 64), fill=rgba(theme.primary, 154), width=3)
        draw.line((x, 56, x, 72), fill=rgba(theme.primary, 126), width=3)

    blurred = glow.filter(ImageFilter.GaussianBlur(9))
    img.alpha_composite(blurred)
    img.alpha_composite(glow)
    return img


def main() -> None:
    verify_asset_toolchain()
    for theme in THEMES:
        image = create_asset(theme)
        output = DRAWABLE / asset_name(theme.suffix)
        image.save(output, "WEBP", quality=94, method=6)
        print(output.name)


if __name__ == "__main__":
    main()
