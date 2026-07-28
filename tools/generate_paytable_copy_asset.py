#!/usr/bin/env python3
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

from vslot_asset_fonts import load_font, verify_font
from vslot_asset_toolchain import verify_asset_toolchain


ROOT = Path(__file__).resolve().parents[1]
LINES = (
    "ЛИНИИ: НЕПРЕРЫВНО СЛЕВА НАПРАВО",
    "НАЧИНАЯ С 1-ГО БАРАБАНА",
    "ПЛАТИТ ТОЛЬКО ОДНА ЛУЧШАЯ",
    "КОМБИНАЦИЯ НА ЛИНИИ",
    "ВЫПЛАТА = МНОЖИТЕЛЬ × СТАВКА НА ЛИНИЮ",
    "ОБЩАЯ СТАВКА = СТАВКА НА ЛИНИЮ × АКТИВНЫЕ ЛИНИИ",
)
OUTPUTS = (
    (ROOT / "app/src/main/res/drawable-nodpi/label_paytable_bet_explanation.webp", (1200, 480), 62),
    (ROOT / "app/src/main/res/drawable-land-nodpi/label_paytable_bet_explanation.webp", (1200, 420), 58),
)
LINE_FILLS = (
    (255, 243, 255, 255),
    (211, 247, 255, 255),
    (255, 218, 119, 255),
    (255, 243, 255, 255),
    (231, 217, 255, 255),
    (157, 241, 255, 255),
)


def font(size: int) -> ImageFont.FreeTypeFont:
    return load_font(size, weight=800, width=88)


def fitted_font(draw: ImageDraw.ImageDraw, size: tuple[int, int], maximum: int) -> ImageFont.FreeTypeFont:
    max_width = size[0] - 64
    max_height = size[1] - 24
    for text_size in range(maximum, 19, -1):
        candidate = font(text_size)
        boxes = [draw.textbbox((0, 0), line, font=candidate, stroke_width=2) for line in LINES]
        widths = [box[2] - box[0] for box in boxes]
        heights = [box[3] - box[1] for box in boxes]
        if max(widths) <= max_width and sum(heights) + 12 * (len(LINES) - 1) <= max_height:
            return candidate
    raise SystemExit(f"Copy does not fit output size {size}")


def render(output: Path, size: tuple[int, int], maximum_font_size: int) -> None:
    image = Image.new("RGBA", size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    selected_font = fitted_font(draw, size, maximum_font_size)
    boxes = [draw.textbbox((0, 0), line, font=selected_font, stroke_width=2) for line in LINES]
    heights = [box[3] - box[1] for box in boxes]
    line_gap = 12
    top = (size[1] - sum(heights) - line_gap * (len(LINES) - 1)) / 2

    for line, box, height, fill in zip(LINES, boxes, heights, LINE_FILLS):
        width = box[2] - box[0]
        x = (size[0] - width) / 2 - box[0]
        y = top - box[1]
        draw.text((x + 3, y + 3), line, font=selected_font, fill=(0, 0, 0, 190), stroke_width=2)
        draw.text((x, y), line, font=selected_font, fill=fill, stroke_width=2, stroke_fill=(32, 17, 58, 230))
        top += height + line_gap

    output.parent.mkdir(parents=True, exist_ok=True)
    image.save(output, "WEBP", lossless=True, method=6)
    print(f"{output.relative_to(ROOT)} {size[0]}x{size[1]}")


def main() -> None:
    verify_asset_toolchain()
    verify_font()
    for output, size, maximum_font_size in OUTPUTS:
        render(output, size, maximum_font_size)


if __name__ == "__main__":
    main()
