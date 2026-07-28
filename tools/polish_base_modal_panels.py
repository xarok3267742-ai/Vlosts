#!/usr/bin/env python3
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from random import Random

from PIL import Image, ImageDraw, ImageFilter


ROOT = Path(__file__).resolve().parents[1]
DRAWABLE = ROOT / "app" / "src" / "main" / "res" / "drawable-nodpi"


@dataclass(frozen=True)
class PanelPalette:
    name: str
    primary: tuple[int, int, int]
    secondary: tuple[int, int, int]
    accent: tuple[int, int, int]
    shadow: tuple[int, int, int]


PANELS = (
    PanelPalette("result_modal_panel", (118, 235, 255), (174, 86, 255), (255, 219, 91), (6, 8, 32)),
    PanelPalette("disclaimer_modal_panel", (255, 213, 104), (119, 232, 255), (188, 96, 255), (12, 9, 34)),
    PanelPalette("social_rules_modal_panel", (104, 232, 255), (255, 210, 86), (170, 92, 255), (8, 15, 38)),
)


def rgba(color: tuple[int, int, int], alpha: int) -> tuple[int, int, int, int]:
    return color[0], color[1], color[2], alpha


def rounded_mask(size: tuple[int, int], radius: int, inset: int) -> Image.Image:
    mask = Image.new("L", size, 0)
    draw = ImageDraw.Draw(mask)
    draw.rounded_rectangle(
        (inset, inset, size[0] - inset - 1, size[1] - inset - 1),
        radius=radius,
        fill=255,
    )
    return mask


def glow_layer(size: tuple[int, int], mask: Image.Image, color: tuple[int, int, int], blur: int, alpha: int) -> Image.Image:
    glow = Image.new("RGBA", size, rgba(color, alpha))
    glow.putalpha(mask.filter(ImageFilter.GaussianBlur(blur)).point(lambda value: min(alpha, value * alpha // 180)))
    return glow


def draw_lattice(size: tuple[int, int], palette: PanelPalette) -> Image.Image:
    width, height = size
    layer = Image.new("RGBA", size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(layer)
    rng = Random(palette.name)

    for offset in range(-height, width, 72):
        draw.line(
            (offset, height * 0.18, offset + height * 0.72, height * 0.86),
            fill=rgba(palette.primary, 30),
            width=2,
        )
        draw.line(
            (offset, height * 0.82, offset + height * 0.58, height * 0.16),
            fill=rgba(palette.secondary, 24),
            width=2,
        )

    for y_ratio, alpha in ((0.22, 78), (0.5, 46), (0.78, 66)):
        y = int(height * y_ratio)
        draw.line((86, y, width - 86, y), fill=rgba(palette.primary, alpha), width=3)
        draw.line((126, y + 10, width - 126, y + 10), fill=rgba(palette.accent, alpha // 2), width=2)

    for index in range(34):
        x = rng.randint(78, width - 78)
        y = rng.randint(62, height - 62)
        radius = rng.choice((2, 3, 4, 5))
        color = palette.accent if index % 4 == 0 else palette.primary
        draw.ellipse((x - radius, y - radius, x + radius, y + radius), fill=rgba(color, rng.randint(62, 126)))

    return layer.filter(ImageFilter.GaussianBlur(0.35))


def draw_rim(size: tuple[int, int], palette: PanelPalette) -> Image.Image:
    width, height = size
    layer = Image.new("RGBA", size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(layer)
    outer = (42, 36, width - 42, height - 36)
    inner = (64, 58, width - 64, height - 58)
    radius = 58

    draw.rounded_rectangle(outer, radius=radius, outline=rgba(palette.primary, 220), width=7)
    draw.rounded_rectangle(inner, radius=radius - 14, outline=rgba((255, 255, 255), 82), width=3)
    draw.rounded_rectangle((88, 84, width - 88, 142), radius=26, outline=rgba(palette.accent, 112), width=3)
    draw.rounded_rectangle((102, height - 142, width - 102, height - 84), radius=26, outline=rgba(palette.secondary, 84), width=3)

    for x in (92, width - 92):
        draw.rounded_rectangle((x - 20, 118, x + 20, height - 118), radius=18, outline=rgba(palette.primary, 124), width=4)
        for y in range(150, height - 120, 74):
            draw.ellipse((x - 11, y - 11, x + 11, y + 11), fill=rgba(palette.accent, 136))

    return layer


def draw_glass_sheen(size: tuple[int, int], palette: PanelPalette) -> Image.Image:
    width, height = size
    sheen = Image.new("RGBA", size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(sheen)
    draw.polygon(
        [
            (width * 0.04, height * 0.12),
            (width * 0.46, height * 0.12),
            (width * 0.28, height * 0.62),
            (width * 0.0, height * 0.74),
        ],
        fill=(255, 255, 255, 22),
    )
    draw.polygon(
        [
            (width * 0.62, height * 0.10),
            (width * 0.94, height * 0.10),
            (width * 0.78, height * 0.48),
            (width * 0.52, height * 0.70),
        ],
        fill=rgba(palette.primary, 18),
    )
    return sheen.filter(ImageFilter.GaussianBlur(1.2))


def polish_panel(palette: PanelPalette) -> Image.Image:
    source_path = DRAWABLE / f"{palette.name}.webp"
    source = Image.open(source_path).convert("RGBA")
    width, height = source.size
    mask = rounded_mask(source.size, radius=68, inset=34)

    base = Image.new("RGBA", source.size, (0, 0, 0, 0))
    base.alpha_composite(glow_layer(source.size, mask, palette.primary, blur=22, alpha=112))
    base.alpha_composite(glow_layer(source.size, mask, palette.secondary, blur=9, alpha=62))
    base.alpha_composite(source)
    base.alpha_composite(draw_lattice(source.size, palette))
    base.alpha_composite(draw_glass_sheen(source.size, palette))
    base.alpha_composite(draw_rim(source.size, palette).filter(ImageFilter.GaussianBlur(0.4)))

    edge = Image.new("RGBA", source.size, (0, 0, 0, 0))
    edge_draw = ImageDraw.Draw(edge)
    edge_draw.rounded_rectangle((36, 32, width - 36, height - 32), radius=66, outline=rgba(palette.accent, 114), width=2)
    edge_draw.rounded_rectangle((52, 48, width - 52, height - 48), radius=52, outline=rgba((255, 255, 255), 56), width=1)
    base.alpha_composite(edge)

    return base


def main() -> None:
    for palette in PANELS:
        output = polish_panel(palette)
        path = DRAWABLE / f"{palette.name}.webp"
        output.save(path, "WEBP", quality=96, method=6)
        print(path.name, path.stat().st_size)


if __name__ == "__main__":
    main()
