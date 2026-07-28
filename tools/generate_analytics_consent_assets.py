#!/usr/bin/env python3
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

from vslot_asset_fonts import load_font, verify_font
from vslot_asset_toolchain import verify_asset_toolchain

ROOT = Path(__file__).resolve().parents[1]
DRAWABLE = ROOT / "app" / "src" / "main" / "res" / "drawable-nodpi"


def centered_text_asset(
    size: tuple[int, int],
    text: str,
    maximum_size: int,
    minimum_size: int,
) -> Image.Image:
    image = Image.new("RGBA", size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    selected = load_font(maximum_size, weight=800, width=78)
    for text_size in range(maximum_size, minimum_size - 1, -2):
        selected = load_font(text_size, weight=800, width=78)
        bounds = draw.textbbox((0, 0), text, font=selected, stroke_width=2)
        if bounds[2] - bounds[0] <= size[0] - 28 and bounds[3] - bounds[1] <= size[1] - 16:
            break
    bounds = draw.textbbox((0, 0), text, font=selected, stroke_width=2)
    x = (size[0] - (bounds[2] - bounds[0])) // 2 - bounds[0]
    y = (size[1] - (bounds[3] - bounds[1])) // 2 - bounds[1]
    glow = Image.new("RGBA", size, (0, 0, 0, 0))
    glow_draw = ImageDraw.Draw(glow)
    glow_draw.text((x, y), text, font=selected, fill=(152, 99, 255, 220), stroke_width=5)
    image.alpha_composite(glow.filter(ImageFilter.GaussianBlur(7)))
    draw = ImageDraw.Draw(image)
    draw.text(
        (x, y),
        text,
        font=selected,
        fill=(255, 244, 205, 255),
        stroke_width=2,
        stroke_fill=(46, 19, 82, 255),
    )
    return image


def wrapped_body_asset() -> Image.Image:
    size = (1080, 380)
    text = (
        "Разрешить V Slot передавать сервису AppMetrica псевдонимные "
        "идентификаторы приложения и устройства, источник установки, действия в игре, "
        "а также данные диагностики и производительности? Это нужно для аналитики и "
        "улучшения игры. Согласие можно отозвать в настройках. Подробнее — в политике "
        "конфиденциальности."
    )
    image = Image.new("RGBA", size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    selected = load_font(38, weight=600)
    words = text.split()
    lines: list[str] = []
    current = ""
    for word in words:
        candidate = f"{current} {word}".strip()
        if draw.textlength(candidate, font=selected) <= size[0] - 56:
            current = candidate
        else:
            lines.append(current)
            current = word
    if current:
        lines.append(current)

    line_height = 44
    start_y = (size[1] - line_height * len(lines)) // 2
    for index, line in enumerate(lines):
        bounds = draw.textbbox((0, 0), line, font=selected, stroke_width=1)
        x = (size[0] - (bounds[2] - bounds[0])) // 2
        y = start_y + index * line_height
        draw.text(
            (x + 2, y + 3),
            line,
            font=selected,
            fill=(0, 0, 0, 145),
            stroke_width=1,
            stroke_fill=(0, 0, 0, 145),
        )
        draw.text(
            (x, y),
            line,
            font=selected,
            fill=(236, 240, 255, 255),
            stroke_width=1,
            stroke_fill=(35, 26, 68, 255),
        )
    return image


def analytics_icon(enabled: bool) -> Image.Image:
    size = 128
    image = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    glow_color = (71, 235, 211) if enabled else (126, 133, 160)
    glow = Image.new("RGBA", image.size, (0, 0, 0, 0))
    glow_draw = ImageDraw.Draw(glow)
    glow_draw.rounded_rectangle((18, 18, 110, 110), radius=24, fill=(*glow_color, 180))
    image.alpha_composite(glow.filter(ImageFilter.GaussianBlur(14)))

    draw = ImageDraw.Draw(image)
    draw.rounded_rectangle(
        (18, 18, 110, 110),
        radius=24,
        fill=(16, 20, 48, 245),
        outline=(*glow_color, 255),
        width=5,
    )
    bars = ((34, 73, 47, 94), (57, 57, 70, 94), (80, 38, 93, 94))
    for bar in bars:
        draw.rounded_rectangle(bar, radius=5, fill=(*glow_color, 255))
    draw.line((31, 96, 97, 96), fill=(244, 227, 155, 230), width=4)
    if not enabled:
        draw.line((27, 27, 101, 101), fill=(255, 103, 125, 255), width=9)
        draw.line((27, 27, 101, 101), fill=(255, 225, 230, 255), width=3)
    return image


def save(image: Image.Image, name: str) -> None:
    image.save(DRAWABLE / name, "WEBP", lossless=True, method=6)


def main() -> None:
    verify_asset_toolchain()
    verify_font()
    save(centered_text_asset((520, 86), "АНАЛИТИКА", 64, 40), "title_analytics_consent.webp")
    save(wrapped_body_asset(), "body_analytics_consent.webp")
    save(centered_text_asset((360, 76), "НЕ РАЗРЕШАТЬ", 46, 28), "label_analytics_decline.webp")
    save(centered_text_asset((320, 76), "РАЗРЕШИТЬ", 48, 30), "label_analytics_allow.webp")
    save(analytics_icon(enabled=True), "settings_analytics_on.webp")
    save(analytics_icon(enabled=False), "settings_analytics_off.webp")


if __name__ == "__main__":
    main()
