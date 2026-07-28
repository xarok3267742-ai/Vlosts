#!/usr/bin/env python3
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter

from vslot_asset_toolchain import verify_asset_toolchain


ROOT = Path(__file__).resolve().parents[1]
DRAWABLE = ROOT / "app" / "src" / "main" / "res" / "drawable-nodpi"


@dataclass(frozen=True)
class LabelTheme:
    suffix: str
    dark: tuple[int, int, int]
    primary: tuple[int, int, int]
    secondary: tuple[int, int, int]
    accent: tuple[int, int, int]


THEMES = (
    LabelTheme("neon", (8, 12, 40), (30, 229, 255), (255, 48, 190), (255, 231, 82)),
    LabelTheme("pharaoh", (44, 24, 10), (255, 199, 62), (18, 205, 185), (255, 119, 36)),
    LabelTheme("ocean", (4, 27, 48), (104, 229, 255), (187, 255, 247), (255, 216, 96)),
)

LABEL_SOURCES = {
    "label_bet": "label_bet.webp",
    "label_lines": "label_lines.webp",
    "label_total_bet": "label_total_bet.webp",
    "label_last_win": "label_last_win.webp",
}


def rgba(color: tuple[int, int, int], alpha: int = 255) -> tuple[int, int, int, int]:
    return color[0], color[1], color[2], alpha


def mix(a: tuple[int, int, int], b: tuple[int, int, int], t: float) -> tuple[int, int, int]:
    t = max(0.0, min(1.0, t))
    return tuple(int(a[i] * (1 - t) + b[i] * t) for i in range(3))


def label_color(theme: LabelTheme, luma: int) -> tuple[int, int, int]:
    t = luma / 255.0
    if t < 0.34:
        return mix(theme.dark, theme.primary, t / 0.34 * 0.65)
    if t < 0.7:
        return mix(theme.primary, theme.secondary, (t - 0.34) / 0.36)
    return mix(theme.secondary, theme.accent, (t - 0.7) / 0.3)


def colorize_label(source: Image.Image, theme: LabelTheme) -> Image.Image:
    source = source.convert("RGBA")
    out = Image.new("RGBA", source.size, (0, 0, 0, 0))
    src_px = source.load()
    out_px = out.load()
    for y in range(source.height):
        for x in range(source.width):
            r, g, b, a = src_px[x, y]
            if a == 0:
                continue
            luma = int(r * 0.299 + g * 0.587 + b * 0.114)
            themed = label_color(theme, luma)
            if luma > 210:
                keep = 0.64
            elif luma > 150:
                keep = 0.32
            else:
                keep = 0.12
            nr = int(themed[0] * (1 - keep) + r * keep)
            ng = int(themed[1] * (1 - keep) + g * keep)
            nb = int(themed[2] * (1 - keep) + b * keep)
            out_px[x, y] = (min(255, nr), min(255, ng), min(255, nb), a)
    return out


def add_label_glow(img: Image.Image, theme: LabelTheme) -> Image.Image:
    width, height = img.size
    glow = Image.new("RGBA", img.size, (0, 0, 0, 0))
    alpha = img.getchannel("A")
    glow.putalpha(alpha.filter(ImageFilter.GaussianBlur(max(3, height // 7))).point(lambda p: min(180, p)))
    color = Image.new("RGBA", img.size, rgba(theme.primary, 255))
    glow = Image.composite(color, glow, glow.getchannel("A"))
    out = Image.new("RGBA", img.size, (0, 0, 0, 0))
    out.alpha_composite(glow)
    out.alpha_composite(img)

    overlay = Image.new("RGBA", img.size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(overlay)
    draw.line((width * 0.08, height * 0.88, width * 0.92, height * 0.88), fill=rgba(theme.accent, 92), width=max(2, height // 18))
    out.alpha_composite(overlay.filter(ImageFilter.GaussianBlur(1)))
    return out


def main() -> None:
    verify_asset_toolchain()
    for theme in THEMES:
        for stem, source_name in LABEL_SOURCES.items():
            source = Image.open(DRAWABLE / source_name).convert("RGBA")
            output = add_label_glow(colorize_label(source, theme), theme)
            output_name = f"{stem}_{theme.suffix}.webp"
            output.save(DRAWABLE / output_name, "WEBP", quality=94, method=6)
            print(output_name)


if __name__ == "__main__":
    main()
