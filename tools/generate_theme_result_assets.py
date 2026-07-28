#!/usr/bin/env python3
from __future__ import annotations

from dataclasses import dataclass
from math import cos, sin
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter

from vslot_asset_toolchain import verify_asset_toolchain


ROOT = Path(__file__).resolve().parents[1]
DRAWABLE = ROOT / "app" / "src" / "main" / "res" / "drawable-nodpi"


@dataclass(frozen=True)
class ResultTheme:
    suffix: str
    dark: tuple[int, int, int]
    primary: tuple[int, int, int]
    secondary: tuple[int, int, int]
    accent: tuple[int, int, int]


THEMES = (
    ResultTheme("roman", (44, 16, 10), (244, 188, 62), (174, 48, 42), (255, 235, 134)),
    ResultTheme("neon", (7, 10, 38), (28, 229, 255), (255, 48, 190), (255, 232, 82)),
    ResultTheme("pharaoh", (42, 23, 9), (255, 202, 62), (20, 207, 186), (255, 119, 36)),
    ResultTheme("ocean", (3, 28, 50), (99, 229, 255), (184, 255, 247), (255, 216, 96)),
)

BYTE_EXACT_SOURCE_TO_OUTPUT = {
    "result_stage_lattice.webp": "result_stage_lattice",
    "result_win_payout_burst.webp": "result_win_payout_burst",
    "result_free_spins_award_panel.webp": "result_free_spins_award_panel",
}


def rgba(color: tuple[int, int, int], alpha: int = 255) -> tuple[int, int, int, int]:
    return color[0], color[1], color[2], alpha


def mix(a: tuple[int, int, int], b: tuple[int, int, int], t: float) -> tuple[int, int, int]:
    t = max(0.0, min(1.0, t))
    return tuple(int(a[i] * (1 - t) + b[i] * t) for i in range(3))


def ramp(theme: ResultTheme, luma: int, x_ratio: float, y_ratio: float) -> tuple[int, int, int]:
    t = luma / 255.0
    diagonal = x_ratio * 0.5 + y_ratio * 0.5
    if t < 0.38:
        return mix(theme.dark, theme.primary, t / 0.38 * (0.58 + diagonal * 0.14))
    if t < 0.76:
        return mix(theme.primary, theme.secondary, (t - 0.38) / 0.38 * 0.7)
    return mix(theme.secondary, theme.accent, (t - 0.76) / 0.24)


def colorize(source: Image.Image, theme: ResultTheme, intensity: float) -> Image.Image:
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
            if luma > 216:
                keep += 0.18
            nr = int(themed[0] * intensity + r * keep)
            ng = int(themed[1] * intensity + g * keep)
            nb = int(themed[2] * intensity + b * keep)
            out_px[x, y] = (min(255, nr), min(255, ng), min(255, nb), a)
    return out


def alpha_glow(img: Image.Image, color: tuple[int, int, int], blur: int, alpha: int) -> Image.Image:
    glow = Image.new("RGBA", img.size, rgba(color))
    glow.putalpha(img.getchannel("A").filter(ImageFilter.GaussianBlur(blur)).point(lambda p: min(alpha, p * alpha // 190)))
    return glow


def polish_modal_panel(img: Image.Image, theme: ResultTheme) -> Image.Image:
    width, height = img.size
    overlay = Image.new("RGBA", img.size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(overlay)
    stroke = max(4, min(width, height) // 90)
    draw.rounded_rectangle(
        (width * 0.055, height * 0.08, width * 0.945, height * 0.92),
        radius=max(30, height // 7),
        outline=rgba(theme.primary, 118),
        width=stroke,
    )
    draw.arc(
        (width * 0.08, -height * 0.18, width * 0.92, height * 0.58),
        18,
        162,
        fill=rgba(theme.accent, 96),
        width=stroke,
    )
    for x in (width * 0.15, width * 0.85):
        draw.ellipse(
            (x - height * 0.055, height * 0.42, x + height * 0.055, height * 0.55),
            fill=rgba(theme.secondary, 112),
            outline=rgba(theme.accent, 112),
            width=max(2, stroke // 2),
        )
    out = Image.new("RGBA", img.size, (0, 0, 0, 0))
    out.alpha_composite(alpha_glow(img, theme.primary, max(12, height // 18), 116))
    out.alpha_composite(img)
    out.alpha_composite(overlay.filter(ImageFilter.GaussianBlur(1)))
    out.alpha_composite(overlay)
    return out


def polish_stage_lattice(img: Image.Image, theme: ResultTheme) -> Image.Image:
    width, height = img.size
    overlay = Image.new("RGBA", img.size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(overlay)
    step = max(34, width // 18)
    for offset in range(-height, width, step):
        draw.line((offset, height * 0.08, offset + height * 0.56, height * 0.9), fill=rgba(theme.primary, 38), width=max(2, width // 260))
        draw.line((offset, height * 0.88, offset + height * 0.56, height * 0.1), fill=rgba(theme.secondary, 30), width=max(2, width // 300))
    draw.line((width * 0.13, height * 0.27, width * 0.87, height * 0.27), fill=rgba(theme.accent, 54), width=max(2, height // 80))
    draw.line((width * 0.15, height * 0.76, width * 0.85, height * 0.76), fill=rgba(theme.primary, 46), width=max(2, height // 92))
    out = Image.new("RGBA", img.size, (0, 0, 0, 0))
    out.alpha_composite(alpha_glow(img, theme.primary, max(9, height // 24), 108))
    out.alpha_composite(img)
    out.alpha_composite(overlay.filter(ImageFilter.GaussianBlur(2)))
    out.alpha_composite(overlay)
    return out


def polish_reward_burst(img: Image.Image, theme: ResultTheme) -> Image.Image:
    width, height = img.size
    overlay = Image.new("RGBA", img.size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(overlay)
    center = (width * 0.5, height * 0.42)
    for index in range(18):
        angle = index * 3.14159265 * 2 / 18
        length = width * (0.17 + (index % 4) * 0.018)
        x = center[0] + length * cos(angle)
        y = center[1] + length * sin(angle)
        color = theme.accent if index % 3 == 0 else theme.primary
        draw.line((center[0], center[1], x, y), fill=rgba(color, 58), width=max(3, width // 210))
    for index in range(9):
        x = width * (0.18 + index * 0.08)
        y = height * (0.24 + (index % 3) * 0.12)
        draw.ellipse((x - 5, y - 5, x + 5, y + 5), fill=rgba(theme.accent, 150))
    out = Image.new("RGBA", img.size, (0, 0, 0, 0))
    out.alpha_composite(alpha_glow(img, theme.accent, max(14, height // 20), 136))
    out.alpha_composite(img)
    out.alpha_composite(overlay.filter(ImageFilter.GaussianBlur(2)))
    out.alpha_composite(overlay)
    return out


def polish_free_spins_panel(img: Image.Image, theme: ResultTheme) -> Image.Image:
    width, height = img.size
    overlay = Image.new("RGBA", img.size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(overlay)
    stroke = max(3, height // 34)
    draw.rounded_rectangle(
        (width * 0.03, height * 0.12, width * 0.97, height * 0.88),
        radius=max(16, height // 4),
        outline=rgba(theme.primary, 120),
        width=stroke,
    )
    draw.line((width * 0.14, height * 0.24, width * 0.86, height * 0.24), fill=rgba(theme.accent, 82), width=stroke)
    draw.ellipse((width * 0.76, height * 0.24, width * 0.91, height * 0.72), fill=rgba(theme.secondary, 106), outline=rgba(theme.accent, 132), width=max(2, stroke // 2))
    out = Image.new("RGBA", img.size, (0, 0, 0, 0))
    out.alpha_composite(alpha_glow(img, theme.primary, max(8, height // 13), 118))
    out.alpha_composite(img)
    out.alpha_composite(overlay.filter(ImageFilter.GaussianBlur(1)))
    out.alpha_composite(overlay)
    return out


def polish(source_name: str, output_stem: str, theme: ResultTheme) -> Image.Image:
    source = Image.open(DRAWABLE / source_name).convert("RGBA")
    intensity = 0.78 if output_stem == "result_win_payout_burst" else 0.86
    themed = colorize(source, theme, intensity)
    if output_stem == "result_modal_panel":
        return polish_modal_panel(themed, theme)
    if output_stem == "result_stage_lattice":
        return polish_stage_lattice(themed, theme)
    if output_stem == "result_win_payout_burst":
        return polish_reward_burst(themed, theme)
    if output_stem == "result_free_spins_award_panel":
        return polish_free_spins_panel(themed, theme)
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
