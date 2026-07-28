#!/usr/bin/env python3
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
import math

from PIL import Image, ImageDraw, ImageFilter

from vslot_asset_toolchain import verify_asset_toolchain


ROOT = Path(__file__).resolve().parents[1]
DRAWABLE = ROOT / "app" / "src" / "main" / "res" / "drawable-nodpi"
SIZE = (540, 220)


@dataclass(frozen=True)
class ImpactTheme:
    suffix: str
    shadow: tuple[int, int, int]
    primary: tuple[int, int, int]
    secondary: tuple[int, int, int]
    accent: tuple[int, int, int]


THEMES = (
    ImpactTheme("", (14, 7, 40), (168, 92, 255), (255, 210, 86), (255, 255, 230)),
    ImpactTheme("roman", (34, 14, 18), (224, 112, 58), (255, 204, 118), (255, 245, 210)),
    ImpactTheme("neon", (3, 10, 38), (46, 232, 255), (255, 58, 205), (255, 232, 82)),
    ImpactTheme("pharaoh", (44, 23, 6), (255, 199, 58), (38, 216, 193), (255, 136, 34)),
    ImpactTheme("ocean", (2, 25, 47), (118, 235, 255), (185, 255, 246), (255, 220, 98)),
)


def rgba(color: tuple[int, int, int], alpha: int) -> tuple[int, int, int, int]:
    return color[0], color[1], color[2], alpha


def make_asset(theme: ImpactTheme) -> Image.Image:
    width, height = SIZE
    cx = width / 2
    cy = height / 2
    img = Image.new("RGBA", SIZE, (0, 0, 0, 0))

    glow = Image.new("RGBA", SIZE, (0, 0, 0, 0))
    glow_draw = ImageDraw.Draw(glow)
    for index, alpha in enumerate((72, 48, 30)):
        inset_x = 28 + index * 34
        inset_y = 28 + index * 16
        glow_draw.ellipse(
            (inset_x, inset_y, width - inset_x, height - inset_y),
            outline=rgba(theme.primary if index % 2 == 0 else theme.secondary, alpha),
            width=22 - index * 5,
        )
    img.alpha_composite(glow.filter(ImageFilter.GaussianBlur(18)))

    rays = Image.new("RGBA", SIZE, (0, 0, 0, 0))
    ray_draw = ImageDraw.Draw(rays)
    for index in range(28):
        angle = (math.pi * 2 * index / 28.0) + (0.08 if index % 2 else 0)
        inner = 82 + (index % 3) * 7
        outer = 232 + (index % 4) * 18
        sx = cx + math.cos(angle) * inner
        sy = cy + math.sin(angle) * inner * 0.38
        ex = cx + math.cos(angle) * outer
        ey = cy + math.sin(angle) * outer * 0.42
        color = theme.secondary if index % 3 else theme.primary
        ray_draw.line((sx, sy, ex, ey), fill=rgba(color, 104 if index % 2 else 132), width=4)
    img.alpha_composite(rays.filter(ImageFilter.GaussianBlur(2)))

    ring = Image.new("RGBA", SIZE, (0, 0, 0, 0))
    ring_draw = ImageDraw.Draw(ring)
    ring_draw.ellipse((68, 46, width - 68, height - 46), outline=rgba(theme.accent, 226), width=8)
    ring_draw.ellipse((106, 66, width - 106, height - 66), outline=rgba(theme.primary, 176), width=5)
    ring_draw.rounded_rectangle((120, 74, width - 120, height - 74), radius=36, outline=rgba(theme.shadow, 70), width=5)
    img.alpha_composite(ring.filter(ImageFilter.GaussianBlur(5)))
    img.alpha_composite(ring)

    button_flare = Image.new("RGBA", SIZE, (0, 0, 0, 0))
    flare_draw = ImageDraw.Draw(button_flare)
    flare_draw.rounded_rectangle(
        (70, 58, width - 70, height - 58),
        radius=46,
        outline=rgba(theme.accent, 238),
        width=8,
    )
    flare_draw.rounded_rectangle(
        (98, 76, width - 98, height - 76),
        radius=30,
        outline=rgba(theme.primary, 196),
        width=5,
    )
    for offset, alpha, line_width, color in (
        (-18, 108, 5, theme.secondary),
        (0, 58, 4, theme.accent),
        (18, 96, 4, theme.primary),
    ):
        flare_draw.line((56, cy + offset, width - 56, cy + offset), fill=rgba(color, alpha), width=line_width)
    for x, y, radius in ((144, 76, 9), (396, 77, 8), (156, 145, 7), (384, 144, 8)):
        flare_draw.line((x - radius, y, x + radius, y), fill=rgba(theme.accent, 218), width=3)
        flare_draw.line((x, y - radius, x, y + radius), fill=rgba(theme.accent, 218), width=3)
    img.alpha_composite(button_flare.filter(ImageFilter.GaussianBlur(3)))
    img.alpha_composite(button_flare)

    sparks = Image.new("RGBA", SIZE, (0, 0, 0, 0))
    spark_draw = ImageDraw.Draw(sparks)
    for index in range(18):
        angle = math.pi * 2 * index / 18.0
        x = cx + math.cos(angle) * (150 + (index % 5) * 19)
        y = cy + math.sin(angle) * (54 + (index % 4) * 8)
        radius = 4 + (index % 4)
        spark_draw.ellipse((x - radius, y - radius, x + radius, y + radius), fill=rgba(theme.accent, 232))
    img.alpha_composite(sparks.filter(ImageFilter.GaussianBlur(1)))
    img.alpha_composite(sparks)
    return img


def main() -> None:
    verify_asset_toolchain()
    DRAWABLE.mkdir(parents=True, exist_ok=True)
    for theme in THEMES:
        suffix = f"_{theme.suffix}" if theme.suffix else ""
        path = DRAWABLE / f"spin_impact_flash{suffix}.webp"
        make_asset(theme).save(path, "WEBP", quality=94, method=6)
        print(path.name)


if __name__ == "__main__":
    main()
