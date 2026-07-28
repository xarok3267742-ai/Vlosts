#!/usr/bin/env python3
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

from vslot_asset_fonts import load_font, verify_font
from vslot_asset_toolchain import verify_asset_toolchain


ROOT = Path(__file__).resolve().parents[1]
DRAWABLE = ROOT / "app/src/main/res/drawable-nodpi"
def render_label(text: str, output_name: str, size: tuple[int, int], font_size: int) -> None:
    image = Image.new("RGBA", size, (0, 0, 0, 0))
    font = load_font(font_size, weight=800, width=88)
    draw = ImageDraw.Draw(image)
    box = draw.textbbox((0, 0), text, font=font, stroke_width=2)
    x = (size[0] - (box[2] - box[0])) / 2 - box[0]
    y = (size[1] - (box[3] - box[1])) / 2 - box[1]
    draw.text(
        (x + 2, y + 3),
        text,
        font=font,
        fill=(0, 0, 0, 190),
        stroke_width=2,
        stroke_fill=(0, 0, 0, 190),
    )
    draw.text(
        (x, y),
        text,
        font=font,
        fill=(255, 229, 126, 255),
        stroke_width=2,
        stroke_fill=(47, 20, 77, 255),
    )
    image.save(DRAWABLE / output_name, "WEBP", lossless=True, method=6)


def main() -> None:
    verify_asset_toolchain()
    verify_font()
    DRAWABLE.mkdir(parents=True, exist_ok=True)
    render_label("ЛИЦЕНЗИИ", "label_third_party_notices.webp", (620, 82), 54)


if __name__ == "__main__":
    main()
