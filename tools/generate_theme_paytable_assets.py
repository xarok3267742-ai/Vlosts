#!/usr/bin/env python3
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter

from vslot_asset_toolchain import verify_asset_toolchain


ROOT = Path(__file__).resolve().parents[1]
DRAWABLE = ROOT / "app" / "src" / "main" / "res" / "drawable-nodpi"


@dataclass(frozen=True)
class PaytableTheme:
    suffix: str
    dark: tuple[int, int, int]
    primary: tuple[int, int, int]
    secondary: tuple[int, int, int]
    accent: tuple[int, int, int]


THEMES = (
    PaytableTheme("neon", (7, 10, 36), (30, 229, 255), (255, 48, 190), (255, 231, 82)),
    PaytableTheme("pharaoh", (42, 23, 9), (255, 202, 62), (20, 207, 186), (255, 119, 36)),
    PaytableTheme("ocean", (3, 28, 50), (99, 229, 255), (184, 255, 247), (255, 216, 96)),
)

BYTE_EXACT_SOURCE_TO_OUTPUT = {
    "paytable_row_panel.webp": "paytable_row_panel",
    "paytable_payline_guide.webp": "paytable_payline_guide",
    "paytable_odds_header_glow.webp": "paytable_odds_header_glow",
}


def rgba(color: tuple[int, int, int], alpha: int = 255) -> tuple[int, int, int, int]:
    return color[0], color[1], color[2], alpha


def mix(a: tuple[int, int, int], b: tuple[int, int, int], t: float) -> tuple[int, int, int]:
    t = max(0.0, min(1.0, t))
    return tuple(int(a[i] * (1 - t) + b[i] * t) for i in range(3))


def ramp(theme: PaytableTheme, luma: int, x_ratio: float, y_ratio: float) -> tuple[int, int, int]:
    t = luma / 255.0
    diagonal = x_ratio * 0.46 + y_ratio * 0.54
    if t < 0.4:
        return mix(theme.dark, theme.primary, t / 0.4 * (0.56 + diagonal * 0.16))
    if t < 0.76:
        return mix(theme.primary, theme.secondary, (t - 0.4) / 0.36 * 0.74)
    return mix(theme.secondary, theme.accent, (t - 0.76) / 0.24)


def colorize(source: Image.Image, theme: PaytableTheme, intensity: float) -> Image.Image:
    src = source.convert("RGBA")
    out = Image.new("RGBA", src.size, (0, 0, 0, 0))
    src_px = src.load()
    out_px = out.load()
    max_x = max(1, src.width - 1)
    max_y = max(1, src.height - 1)

    for y in range(src.height):
        y_ratio = y / max_y
        for x in range(src.width):
            r, g, b, a = src_px[x, y]
            if a == 0:
                continue
            luma = int(r * 0.299 + g * 0.587 + b * 0.114)
            themed = ramp(theme, luma, x / max_x, y_ratio)
            keep = 1.0 - intensity
            if luma > 212:
                keep += 0.16
            nr = int(themed[0] * intensity + r * keep)
            ng = int(themed[1] * intensity + g * keep)
            nb = int(themed[2] * intensity + b * keep)
            out_px[x, y] = (min(255, nr), min(255, ng), min(255, nb), a)
    return out


def alpha_glow(img: Image.Image, color: tuple[int, int, int], blur: int, alpha: int) -> Image.Image:
    glow = Image.new("RGBA", img.size, rgba(color, 255))
    glow.putalpha(img.getchannel("A").filter(ImageFilter.GaussianBlur(blur)).point(lambda p: min(alpha, p * alpha // 180)))
    return glow


def draw_lattice(img: Image.Image, theme: PaytableTheme) -> Image.Image:
    width, height = img.size
    overlay = Image.new("RGBA", img.size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(overlay)
    step = max(44, width // 18)
    for offset in range(-height, width, step):
        draw.line((offset, height * 0.05, offset + height * 0.62, height * 0.95), fill=rgba(theme.primary, 34), width=max(2, width // 260))
        draw.line((offset, height * 0.95, offset + height * 0.62, height * 0.05), fill=rgba(theme.secondary, 28), width=max(2, width // 300))
    for y in (height * 0.18, height * 0.5, height * 0.82):
        draw.line((width * 0.06, y, width * 0.94, y), fill=rgba(theme.accent, 36), width=max(2, height // 120))
    glow = overlay.filter(ImageFilter.GaussianBlur(3))
    out = Image.new("RGBA", img.size, (0, 0, 0, 0))
    out.alpha_composite(alpha_glow(img, theme.primary, max(8, height // 26), 112))
    out.alpha_composite(img)
    out.alpha_composite(glow)
    out.alpha_composite(overlay)
    return out


def draw_row_panel(img: Image.Image, theme: PaytableTheme) -> Image.Image:
    width, height = img.size
    overlay = Image.new("RGBA", img.size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(overlay)
    radius = max(16, height // 3)
    stroke = max(3, height // 28)
    draw.rounded_rectangle(
        (width * 0.018, height * 0.12, width * 0.982, height * 0.88),
        radius=radius,
        outline=rgba(theme.primary, 96),
        width=stroke,
    )
    draw.line((width * 0.05, height * 0.23, width * 0.95, height * 0.23), fill=rgba(theme.accent, 72), width=max(2, height // 34))
    for x in (width * 0.065, width * 0.935):
        draw.ellipse(
            (x - height * 0.11, height * 0.34, x + height * 0.11, height * 0.56),
            fill=rgba(theme.secondary, 120),
            outline=rgba(theme.accent, 132),
            width=max(2, height // 42),
        )
    out = Image.new("RGBA", img.size, (0, 0, 0, 0))
    out.alpha_composite(alpha_glow(img, theme.primary, max(5, height // 15), 92))
    out.alpha_composite(img)
    out.alpha_composite(overlay.filter(ImageFilter.GaussianBlur(1)))
    out.alpha_composite(overlay)
    return out


def draw_payline_guide(img: Image.Image, theme: PaytableTheme) -> Image.Image:
    width, height = img.size
    overlay = Image.new("RGBA", img.size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(overlay)
    line_width = max(3, height // 42)
    draw.line((width * 0.06, height * 0.16, width * 0.94, height * 0.16), fill=rgba(theme.primary, 72), width=line_width)
    draw.line((width * 0.08, height * 0.84, width * 0.92, height * 0.84), fill=rgba(theme.accent, 76), width=line_width)
    for index in range(10):
        x = width * (0.16 + index * 0.075)
        y = height * (0.2 + (index % 3) * 0.18)
        draw.ellipse((x - 5, y - 5, x + 5, y + 5), fill=rgba(theme.secondary if index % 2 else theme.primary, 150))
    out = Image.new("RGBA", img.size, (0, 0, 0, 0))
    out.alpha_composite(alpha_glow(img, theme.primary, max(6, height // 10), 108))
    out.alpha_composite(img)
    out.alpha_composite(overlay.filter(ImageFilter.GaussianBlur(1)))
    out.alpha_composite(overlay)
    return out


def draw_header_glow(img: Image.Image, theme: PaytableTheme) -> Image.Image:
    width, height = img.size
    overlay = Image.new("RGBA", img.size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(overlay)
    for index in range(6):
        y = height * (0.2 + index * 0.11)
        alpha = 52 if index % 2 == 0 else 34
        draw.line((width * 0.11, y, width * 0.89, y + height * 0.05), fill=rgba(theme.primary, alpha), width=max(2, height // 58))
    draw.arc(
        (width * 0.05, -height * 0.44, width * 0.95, height * 1.02),
        20,
        160,
        fill=rgba(theme.accent, 108),
        width=max(4, height // 32),
    )
    out = Image.new("RGBA", img.size, (0, 0, 0, 0))
    out.alpha_composite(alpha_glow(img, theme.primary, max(12, height // 11), 150))
    out.alpha_composite(img)
    out.alpha_composite(overlay.filter(ImageFilter.GaussianBlur(3)))
    out.alpha_composite(overlay)
    return out


def polish(source_name: str, output_stem: str, theme: PaytableTheme) -> Image.Image:
    source = Image.open(DRAWABLE / source_name).convert("RGBA")
    intensity = 0.78 if output_stem == "paytable_payline_guide" else 0.86
    themed = colorize(source, theme, intensity=intensity)
    if output_stem == "paytable_cabinet_lattice":
        return draw_lattice(themed, theme)
    if output_stem == "paytable_row_panel":
        return draw_row_panel(themed, theme)
    if output_stem == "paytable_payline_guide":
        return draw_payline_guide(themed, theme)
    if output_stem == "paytable_odds_header_glow":
        return draw_header_glow(themed, theme)
    return themed


def main() -> None:
    verify_asset_toolchain()
    for theme in THEMES:
        for source_name, output_stem in BYTE_EXACT_SOURCE_TO_OUTPUT.items():
            output_name = f"{output_stem}_{theme.suffix}.webp"
            polish(source_name, output_stem, theme).save(DRAWABLE / output_name, "WEBP", quality=94, method=6)
            print(output_name)


if __name__ == "__main__":
    main()
