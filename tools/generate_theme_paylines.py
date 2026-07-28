#!/usr/bin/env python3
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter

from vslot_asset_toolchain import verify_asset_toolchain


ROOT = Path(__file__).resolve().parents[1]
DRAWABLE = ROOT / "app" / "src" / "main" / "res" / "drawable-nodpi"


@dataclass(frozen=True)
class PaylineTheme:
    suffix: str
    shadow: tuple[int, int, int]
    primary: tuple[int, int, int]
    secondary: tuple[int, int, int]
    accent: tuple[int, int, int]


THEMES = (
    PaylineTheme("neon", (6, 8, 34), (30, 231, 255), (255, 42, 190), (255, 234, 78)),
    PaylineTheme("pharaoh", (46, 25, 8), (255, 204, 63), (33, 210, 188), (255, 126, 42)),
    PaylineTheme("ocean", (4, 32, 52), (99, 229, 255), (186, 255, 247), (255, 218, 92)),
)


def mix(a: tuple[int, int, int], b: tuple[int, int, int], t: float) -> tuple[int, int, int]:
    t = max(0.0, min(1.0, t))
    return tuple(int(a[i] * (1.0 - t) + b[i] * t) for i in range(3))


def rgba(color: tuple[int, int, int], alpha: int = 255) -> tuple[int, int, int, int]:
    return color[0], color[1], color[2], alpha


def theme_color(theme: PaylineTheme, luma: int, x_ratio: float, y_ratio: float) -> tuple[int, int, int]:
    diagonal = (x_ratio * 0.58 + y_ratio * 0.42)
    if luma < 66:
        return mix(theme.shadow, theme.primary, 0.28 + diagonal * 0.18)
    if luma < 138:
        return mix(theme.primary, theme.secondary, 0.18 + diagonal * 0.46)
    if luma < 210:
        return mix(theme.secondary, theme.accent, 0.1 + diagonal * 0.45)
    return mix(theme.accent, (255, 255, 255), 0.32)


def colorize(source: Image.Image, theme: PaylineTheme) -> Image.Image:
    source = source.convert("RGBA")
    out = Image.new("RGBA", source.size, (0, 0, 0, 0))
    src = source.load()
    dst = out.load()
    max_x = max(1, source.width - 1)
    max_y = max(1, source.height - 1)

    for y in range(source.height):
        y_ratio = y / max_y
        for x in range(source.width):
            r, g, b, a = src[x, y]
            if a == 0:
                continue
            luma = int(r * 0.299 + g * 0.587 + b * 0.114)
            themed = theme_color(theme, luma, x / max_x, y_ratio)
            keep_source = 0.22 if luma < 150 else 0.38
            nr = int(themed[0] * (1.0 - keep_source) + r * keep_source)
            ng = int(themed[1] * (1.0 - keep_source) + g * keep_source)
            nb = int(themed[2] * (1.0 - keep_source) + b * keep_source)
            dst[x, y] = (nr, ng, nb, a)
    return out


def add_outer_energy(img: Image.Image, theme: PaylineTheme, strong: bool) -> Image.Image:
    alpha = img.getchannel("A")
    blur_radius = 5 if strong else 3
    glow_alpha = alpha.filter(ImageFilter.GaussianBlur(blur_radius)).point(
        lambda p: min(220 if strong else 165, int(p * (1.22 if strong else 0.82)))
    )
    glow_color = Image.new("RGBA", img.size, rgba(theme.primary))
    glow = Image.new("RGBA", img.size, (0, 0, 0, 0))
    glow.alpha_composite(glow_color)
    glow.putalpha(glow_alpha)

    accent_alpha = alpha.filter(ImageFilter.GaussianBlur(1)).point(lambda p: min(210, int(p * 0.55)))
    accent = Image.new("RGBA", img.size, rgba(theme.accent))
    accent.putalpha(accent_alpha)

    out = Image.new("RGBA", img.size, (0, 0, 0, 0))
    out.alpha_composite(glow)
    out.alpha_composite(img)
    out.alpha_composite(accent)
    if strong:
        out.alpha_composite(scanline_sparks(img.size, theme))
    return out


def scanline_sparks(size: tuple[int, int], theme: PaylineTheme) -> Image.Image:
    width, height = size
    sparks = Image.new("RGBA", size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(sparks)
    line_count = 7
    for index in range(line_count):
        y = int(height * (0.18 + index * 0.105))
        alpha = 32 if index % 2 == 0 else 22
        draw.line((width * 0.08, y, width * 0.92, y), fill=rgba(theme.secondary, alpha), width=max(1, height // 420))
    return sparks.filter(ImageFilter.GaussianBlur(0.45))


def write_asset(source_name: str, output_name: str, theme: PaylineTheme, strong: bool) -> None:
    source = Image.open(DRAWABLE / source_name).convert("RGBA")
    themed = add_outer_energy(colorize(source, theme), theme, strong=strong)
    themed.save(DRAWABLE / output_name, "WEBP", quality=94, method=6)
    print(output_name)


def main() -> None:
    verify_asset_toolchain()
    for theme in THEMES:
        for index in range(1, 11):
            write_asset(
                f"payline_markers_overlay_active_{index}.webp",
                f"payline_markers_overlay_{theme.suffix}_active_{index}.webp",
                theme,
                strong=True,
            )
            write_asset(
                f"payline_win_{index}.webp",
                f"payline_win_{theme.suffix}_{index}.webp",
                theme,
                strong=True,
            )


if __name__ == "__main__":
    main()
