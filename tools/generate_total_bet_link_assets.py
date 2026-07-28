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
    Theme("", (103, 239, 255), (184, 76, 255), (255, 228, 82)),
    Theme("roman", (255, 197, 76), (171, 90, 255), (255, 235, 137)),
    Theme("neon", (38, 232, 255), (255, 51, 188), (255, 232, 82)),
    Theme("pharaoh", (255, 204, 72), (20, 209, 189), (255, 126, 41)),
    Theme("ocean", (103, 231, 255), (184, 255, 247), (255, 216, 96)),
)


def rgba(color: tuple[int, int, int], alpha: int) -> tuple[int, int, int, int]:
    return color[0], color[1], color[2], alpha


def asset_name(suffix: str) -> str:
    return f"total_bet_link_pulse_{suffix}.webp" if suffix else "total_bet_link_pulse.webp"


def create_asset(theme: Theme) -> Image.Image:
    width, height = 760, 260
    image = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    strokes = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    draw = ImageDraw.Draw(strokes)

    left = 62
    right = width - 62
    mid = width // 2
    upper_y = 54
    lower_y = height - 56

    draw.rounded_rectangle(
        (left, upper_y - 22, right, upper_y + 22),
        radius=22,
        outline=rgba(theme.primary, 108),
        width=4,
    )
    draw.rounded_rectangle(
        (left + 18, lower_y - 24, right - 18, lower_y + 24),
        radius=24,
        outline=rgba(theme.accent, 134),
        width=5,
    )

    for offset, color, alpha, stroke_width in (
        (0, theme.primary, 180, 7),
        (14, theme.secondary, 112, 5),
        (-14, theme.accent, 92, 4),
    ):
        draw.line(
            (
                left + 84,
                upper_y + offset,
                mid - 74,
                height // 2 - 8,
                mid + 74,
                height // 2 - 8,
                right - 84,
                lower_y + offset,
            ),
            fill=rgba(color, alpha),
            width=stroke_width,
            joint="curve",
        )

    for x, y, radius, color, alpha in (
        (left + 36, upper_y, 9, theme.accent, 220),
        (right - 36, upper_y, 9, theme.accent, 220),
        (mid, height // 2 - 8, 13, theme.primary, 226),
        (left + 62, lower_y, 10, theme.primary, 198),
        (right - 62, lower_y, 10, theme.primary, 198),
        (mid - 122, height // 2 + 40, 5, theme.secondary, 166),
        (mid + 122, height // 2 + 40, 5, theme.secondary, 166),
    ):
        draw.ellipse((x - radius, y - radius, x + radius, y + radius), fill=rgba(color, alpha))

    for x in range(120, width - 120, 72):
        draw.line((x - 11, lower_y + 38, x + 11, lower_y + 38), fill=rgba(theme.accent, 92), width=3)
        draw.line((x, lower_y + 28, x, lower_y + 48), fill=rgba(theme.primary, 76), width=3)

    glow = strokes.filter(ImageFilter.GaussianBlur(10))
    halo = strokes.filter(ImageFilter.GaussianBlur(21))
    image.alpha_composite(halo)
    image.alpha_composite(glow)
    image.alpha_composite(strokes)
    return image


def main() -> None:
    verify_asset_toolchain()
    for theme in THEMES:
        output = DRAWABLE / asset_name(theme.suffix)
        create_asset(theme).save(output, "WEBP", quality=94, method=6)
        print(output.name)


if __name__ == "__main__":
    main()
