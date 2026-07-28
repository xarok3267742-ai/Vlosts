#!/usr/bin/env python3
from dataclasses import dataclass
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

from vslot_asset_fonts import load_font, verify_font
from vslot_asset_toolchain import verify_asset_toolchain


ROOT = Path(__file__).resolve().parents[1]


@dataclass(frozen=True)
class Theme:
    output_name: str
    scatter_name: str
    primary: tuple[int, int, int, int]
    scatter: tuple[int, int, int, int]
    accent: tuple[int, int, int, int]
    stroke: tuple[int, int, int, int]


THEMES = (
    Theme("label_paytable_footer_violet", "ФИОЛЕТОВЫЙ КРИСТАЛЛ", (255, 238, 255, 255), (221, 167, 255, 255), (255, 190, 76, 255), (50, 20, 72, 245)),
    Theme("label_paytable_footer_roman", "ЗОЛОТОЙ ЩИТ", (255, 239, 190, 255), (255, 207, 91, 255), (255, 179, 72, 255), (69, 34, 23, 245)),
    Theme("label_paytable_footer_nn", "ГОЛОГРАФИЧЕСКАЯ ФИШКА", (255, 226, 248, 255), (255, 106, 224, 255), (73, 242, 255, 255), (42, 15, 65, 245)),
    Theme("label_paytable_footer_pg", "СКАРАБЕЙ", (255, 242, 190, 255), (94, 237, 222, 255), (255, 199, 55, 255), (68, 43, 11, 245)),
    Theme("label_paytable_footer_op", "ЖЕМЧУЖИНА", (233, 255, 255, 255), (255, 244, 218, 255), (91, 223, 255, 255), (10, 48, 69, 245)),
)


def font(size: int) -> ImageFont.FreeTypeFont:
    return load_font(size, weight=800, width=88)


def lines(theme: Theme) -> tuple[tuple[str, tuple[int, int, int, int]], ...]:
    return (
        ("ВАЙЛД ЗАМЕНЯЕТ ОБЫЧНЫЕ СИМВОЛЫ", theme.primary),
        (f"СКАТТЕР — {theme.scatter_name}", theme.scatter),
        ("3+ В ЛЮБЫХ ПОЗИЦИЯХ", theme.primary),
        ("ВЫПЛАТА ОТ ОБЩЕЙ СТАВКИ • +5 ФРИСПИНОВ", theme.accent),
        ("ПОВТОРНЫЙ БОНУС: 3+ ВО ФРИСПИНАХ = ЕЩЕ +5", theme.scatter),
        ("ТОЛЬКО ВИРТУАЛЬНЫЕ МОНЕТЫ", (132, 238, 255, 255)),
    )


def wrapped_lines(
    draw: ImageDraw.ImageDraw,
    theme: Theme,
    selected_font: ImageFont.FreeTypeFont,
    max_width: int,
) -> tuple[tuple[str, tuple[int, int, int, int]], ...]:
    result: list[tuple[str, tuple[int, int, int, int]]] = []
    for copy, fill in lines(theme):
        words = copy.split()
        current = words[0]
        for word in words[1:]:
            candidate = f"{current} {word}"
            box = draw.textbbox((0, 0), candidate, font=selected_font, stroke_width=2)
            if box[2] - box[0] <= max_width:
                current = candidate
            else:
                result.append((current, fill))
                current = word
        result.append((current, fill))
    return tuple(result)


def fitted_copy(
    draw: ImageDraw.ImageDraw,
    theme: Theme,
    size: tuple[int, int],
    maximum_font_size: int,
) -> tuple[ImageFont.FreeTypeFont, tuple[tuple[str, tuple[int, int, int, int]], ...], int]:
    max_width = size[0] - 56
    max_height = size[1] - 32
    for text_size in range(maximum_font_size, 23, -1):
        selected_font = font(text_size)
        copy_lines = wrapped_lines(draw, theme, selected_font, max_width)
        boxes = [draw.textbbox((0, 0), copy, font=selected_font, stroke_width=2) for copy, _ in copy_lines]
        heights = [box[3] - box[1] for box in boxes]
        line_gap = max(7, text_size // 5)
        if sum(heights) + line_gap * (len(copy_lines) - 1) <= max_height:
            return selected_font, copy_lines, line_gap
    raise SystemExit(f"Footer copy does not fit output size {size} for {theme.output_name}")


def render(theme: Theme, output: Path, size: tuple[int, int], maximum_font_size: int) -> None:
    image = Image.new("RGBA", size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    selected_font, copy_lines, line_gap = fitted_copy(draw, theme, size, maximum_font_size)
    boxes = [draw.textbbox((0, 0), copy, font=selected_font, stroke_width=2) for copy, _ in copy_lines]
    heights = [box[3] - box[1] for box in boxes]
    top = (size[1] - sum(heights) - line_gap * (len(copy_lines) - 1)) / 2

    for (copy, fill), box, height in zip(copy_lines, boxes, heights):
        width = box[2] - box[0]
        x = (size[0] - width) / 2 - box[0]
        y = top - box[1]
        draw.text((x + 3, y + 3), copy, font=selected_font, fill=(0, 0, 0, 195), stroke_width=2)
        draw.text((x, y), copy, font=selected_font, fill=fill, stroke_width=2, stroke_fill=theme.stroke)
        top += height + line_gap

    output.parent.mkdir(parents=True, exist_ok=True)
    image.save(output, "WEBP", lossless=True, method=6)
    print(f"{output.relative_to(ROOT)} {size[0]}x{size[1]}")


def main() -> None:
    verify_asset_toolchain()
    verify_font()
    outputs = (
        (ROOT / "app/src/main/res/drawable-nodpi", (1080, 420), 58),
        (ROOT / "app/src/main/res/drawable-land-nodpi", (760, 960), 64),
    )
    for theme in THEMES:
        for output_dir, size, maximum_font_size in outputs:
            render(theme, output_dir / f"{theme.output_name}.webp", size, maximum_font_size)


if __name__ == "__main__":
    main()
