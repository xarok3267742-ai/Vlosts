#!/usr/bin/env python3
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter

from vslot_asset_toolchain import verify_asset_toolchain


ROOT = Path(__file__).resolve().parents[1]
DRAWABLE = ROOT / "app" / "src" / "main" / "res" / "drawable-nodpi"
SIZE = (960, 1020)


@dataclass(frozen=True)
class ApertureTheme:
    suffix: str
    shadow: tuple[int, int, int]
    metal: tuple[int, int, int]
    rim: tuple[int, int, int]
    glow: tuple[int, int, int]
    accent: tuple[int, int, int]


THEMES = (
    ApertureTheme(
        suffix="",
        shadow=(15, 7, 38),
        metal=(83, 41, 132),
        rim=(186, 126, 255),
        glow=(149, 79, 255),
        accent=(255, 215, 108),
    ),
    ApertureTheme(
        suffix="roman",
        shadow=(29, 13, 20),
        metal=(116, 62, 43),
        rim=(255, 205, 124),
        glow=(224, 110, 67),
        accent=(255, 238, 182),
    ),
    ApertureTheme(
        suffix="neon",
        shadow=(4, 10, 36),
        metal=(18, 74, 112),
        rim=(56, 232, 255),
        glow=(255, 62, 204),
        accent=(255, 230, 96),
    ),
    ApertureTheme(
        suffix="pharaoh",
        shadow=(40, 20, 5),
        metal=(146, 93, 23),
        rim=(255, 204, 67),
        glow=(44, 223, 203),
        accent=(255, 132, 36),
    ),
    ApertureTheme(
        suffix="ocean",
        shadow=(2, 25, 45),
        metal=(24, 100, 126),
        rim=(132, 239, 255),
        glow=(184, 255, 246),
        accent=(255, 219, 102),
    ),
)


def rgba(color: tuple[int, int, int], alpha: int) -> tuple[int, int, int, int]:
    return color[0], color[1], color[2], alpha


def rounded_rect_mask(size: tuple[int, int], radius: int, inset: int = 0) -> Image.Image:
    mask = Image.new("L", size, 0)
    draw = ImageDraw.Draw(mask)
    draw.rounded_rectangle(
        (inset, inset, size[0] - inset - 1, size[1] - inset - 1),
        radius=radius,
        fill=255,
    )
    return mask


def add_band(
    layer: Image.Image,
    box: tuple[int, int, int, int],
    fill: tuple[int, int, int, int],
    blur: int,
    radius: int,
) -> None:
    band = Image.new("RGBA", layer.size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(band)
    draw.rounded_rectangle(box, radius=radius, fill=fill)
    layer.alpha_composite(band.filter(ImageFilter.GaussianBlur(blur)))
    layer.alpha_composite(band)


def vertical_gradient(
    size: tuple[int, int],
    top: tuple[int, int, int, int],
    bottom: tuple[int, int, int, int],
) -> Image.Image:
    width, height = size
    img = Image.new("RGBA", size, (0, 0, 0, 0))
    px = img.load()
    for y in range(height):
        t = y / max(1, height - 1)
        color = tuple(int(top[i] * (1 - t) + bottom[i] * t) for i in range(4))
        for x in range(width):
            px[x, y] = color
    return img


def make_asset(theme: ApertureTheme) -> Image.Image:
    width, height = SIZE
    img = Image.new("RGBA", SIZE, (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)

    outer = (18, 18, width - 18, height - 18)
    inner = (54, 74, width - 54, height - 74)
    radius = 52

    # Main soft vignette for depth inside the reel window.
    shadow = Image.new("RGBA", SIZE, (0, 0, 0, 0))
    shadow_draw = ImageDraw.Draw(shadow)
    shadow_draw.rounded_rectangle(outer, radius=radius, fill=rgba(theme.shadow, 130))
    shadow_mask = rounded_rect_mask(SIZE, radius=radius, inset=18)
    inner_cut = rounded_rect_mask(SIZE, radius=radius - 16, inset=58)
    shadow_mask.paste(0, mask=inner_cut)
    shadow.putalpha(shadow_mask)
    img.alpha_composite(shadow.filter(ImageFilter.GaussianBlur(18)))

    # Upper and lower aperture lips imitate the cabinet edge over the spinning strips.
    add_band(img, (24, 18, width - 24, 164), rgba(theme.shadow, 122), 18, 44)
    add_band(img, (28, height - 166, width - 28, height - 20), rgba(theme.shadow, 128), 18, 44)
    add_band(img, (40, 40, width - 40, 104), rgba(theme.metal, 145), 10, 28)
    add_band(img, (40, height - 106, width - 40, height - 42), rgba(theme.metal, 150), 10, 28)

    # Side rails and reel separators add the machine feel without hiding symbols.
    for x in (40, width - 68):
        rail = Image.new("RGBA", SIZE, (0, 0, 0, 0))
        rail_draw = ImageDraw.Draw(rail)
        rail_draw.rounded_rectangle((x, 70, x + 28, height - 70), radius=16, fill=rgba(theme.metal, 126))
        rail_draw.line((x + 14, 92, x + 14, height - 92), fill=rgba(theme.rim, 118), width=3)
        img.alpha_composite(rail.filter(ImageFilter.GaussianBlur(3)))
        img.alpha_composite(rail)

    reel_left = 74
    reel_right = width - 74
    reel_width = reel_right - reel_left
    for index in range(1, 5):
        x = int(reel_left + reel_width * index / 5)
        draw.rounded_rectangle((x - 3, 82, x + 3, height - 82), radius=3, fill=rgba(theme.shadow, 92))
        draw.line((x - 1, 96, x - 1, height - 96), fill=rgba(theme.rim, 76), width=2)

    # Glass reflection and colored energy rim. Keep the middle mostly clear so symbols stay vivid.
    reflection = Image.new("RGBA", SIZE, (0, 0, 0, 0))
    reflection_draw = ImageDraw.Draw(reflection)
    reflection_draw.polygon(
        [
            (width * 0.13, height * 0.12),
            (width * 0.92, height * 0.12),
            (width * 0.72, height * 0.24),
            (width * 0.08, height * 0.2),
        ],
        fill=rgba((255, 255, 255), 14),
    )
    reflection_draw.polygon(
        [
            (width * 0.1, height * 0.78),
            (width * 0.86, height * 0.84),
            (width * 0.78, height * 0.9),
            (width * 0.16, height * 0.88),
        ],
        fill=rgba((255, 255, 255), 8),
    )
    reflection_mask = rounded_rect_mask(SIZE, radius=radius - 20, inset=64)
    reflection.putalpha(Image.composite(reflection.getchannel("A"), Image.new("L", SIZE, 0), reflection_mask))
    img.alpha_composite(reflection.filter(ImageFilter.GaussianBlur(2)))

    glow = Image.new("RGBA", SIZE, (0, 0, 0, 0))
    glow_draw = ImageDraw.Draw(glow)
    glow_draw.rounded_rectangle(inner, radius=radius - 12, outline=rgba(theme.glow, 116), width=5)
    glow_draw.rounded_rectangle((inner[0] + 24, inner[1] + 18, inner[2] - 24, inner[3] - 18), radius=32, outline=rgba(theme.accent, 44), width=2)
    img.alpha_composite(glow.filter(ImageFilter.GaussianBlur(11)))
    img.alpha_composite(glow)

    # Thin black edge preserves readability over bright symbol art.
    draw.rounded_rectangle(inner, radius=radius - 12, outline=(0, 0, 0, 120), width=4)
    return img


def main() -> None:
    verify_asset_toolchain()
    DRAWABLE.mkdir(parents=True, exist_ok=True)
    for theme in THEMES:
        suffix = f"_{theme.suffix}" if theme.suffix else ""
        path = DRAWABLE / f"reel_aperture_shadow{suffix}.webp"
        make_asset(theme).save(path, "WEBP", quality=94, method=6)
        print(path.name)


if __name__ == "__main__":
    main()
