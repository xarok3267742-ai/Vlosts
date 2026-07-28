#!/usr/bin/env python3
from __future__ import annotations

from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

from vslot_asset_fonts import load_font, verify_font
from vslot_asset_toolchain import verify_asset_toolchain


ROOT = Path(__file__).resolve().parents[1]
DRAWABLE = ROOT / "app/src/main/res/drawable-nodpi"
ASSETS = (
    ("title_auto_spin.webp", "АВТОСПИН", (900, 150), 104, (255, 232, 107, 255)),
    ("label_auto_spin_choose.webp", "ВЫБЕРИТЕ КОЛИЧЕСТВО", (1100, 120), 68, (190, 245, 255, 255)),
    ("label_auto_spin_stop.webp", "СТОП", (600, 180), 122, (255, 248, 255, 255)),
)


def fitted_font(draw: ImageDraw.ImageDraw, text: str, size: tuple[int, int], maximum: int) -> ImageFont.FreeTypeFont:
    for font_size in range(maximum, 19, -1):
        candidate = load_font(font_size, weight=800, width=88)
        box = draw.textbbox((0, 0), text, font=candidate, stroke_width=3)
        if box[2] - box[0] <= size[0] - 32 and box[3] - box[1] <= size[1] - 20:
            return candidate
    raise SystemExit(f"Text does not fit: {text}")


def render(name: str, text: str, size: tuple[int, int], maximum: int, fill: tuple[int, int, int, int]) -> None:
    image = Image.new("RGBA", size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    selected_font = fitted_font(draw, text, size, maximum)
    box = draw.textbbox((0, 0), text, font=selected_font, stroke_width=3)
    x = (size[0] - (box[2] - box[0])) / 2 - box[0]
    y = (size[1] - (box[3] - box[1])) / 2 - box[1]
    draw.text((x + 4, y + 5), text, font=selected_font, fill=(0, 0, 0, 190), stroke_width=4)
    draw.text((x, y), text, font=selected_font, fill=fill, stroke_width=3, stroke_fill=(77, 26, 112, 255))
    output = DRAWABLE / name
    output.parent.mkdir(parents=True, exist_ok=True)
    image.save(output, "WEBP", lossless=True, method=6)
    print(output.relative_to(ROOT))


def main() -> None:
    verify_asset_toolchain()
    verify_font()
    for asset in ASSETS:
        render(*asset)


if __name__ == "__main__":
    main()
