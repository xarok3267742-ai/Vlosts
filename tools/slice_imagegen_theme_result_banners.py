from pathlib import Path

from PIL import Image, ImageDraw, ImageEnhance, ImageFilter, ImageFont, ImageOps

from vslot_asset_fonts import load_font, verify_font

from vslot_asset_toolchain import verify_asset_toolchain

verify_asset_toolchain()

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "qa/source/vslot_theme_result_banner_backgrounds_imagegen.png"
OUT_DIR = ROOT / "app/src/main/res/drawable-nodpi"
PREVIEW = ROOT / "qa/screenshots/theme_result_banners_contact_sheet.png"

GRID_COLUMNS = 5
BIG_SIZE = (1000, 300)
BONUS_SIZE = (1000, 260)

THEMES = [
    ("violet", "Violet", (255, 246, 255), (115, 34, 198), (83, 231, 255)),
    ("roman", "Roman", (255, 239, 178), (110, 62, 10), (255, 218, 98)),
    ("neon", "Neon", (252, 246, 255), (164, 23, 214), (45, 229, 255)),
    ("pharaoh", "Pharaoh", (255, 235, 155), (95, 55, 0), (78, 234, 218)),
    ("ocean", "Ocean", (235, 252, 255), (0, 92, 138), (116, 239, 255)),
]


def active_bbox(image: Image.Image) -> tuple[int, int, int, int]:
    gray = ImageOps.grayscale(image)
    mask = gray.point(lambda value: 255 if value > 8 else 0)
    bbox = mask.getbbox()
    if bbox is None:
        return (0, 0, image.width, image.height)
    x0, y0, x1, y1 = bbox
    pad_x = max(8, round((x1 - x0) * 0.025))
    pad_y = max(6, round((y1 - y0) * 0.12))
    return (
        max(0, x0 - pad_x),
        max(0, y0 - pad_y),
        min(image.width, x1 + pad_x),
        min(image.height, y1 + pad_y),
    )


def panel_source(source: Image.Image, index: int) -> Image.Image:
    width, height = source.size
    x0 = round(index * width / GRID_COLUMNS)
    x1 = round((index + 1) * width / GRID_COLUMNS)
    cell = source.crop((x0, 0, x1, height)).convert("RGB")
    return cell.crop(active_bbox(cell))


def banner_mask(size: tuple[int, int]) -> Image.Image:
    width, height = size
    scale = 3
    mask = Image.new("L", (width * scale, height * scale), 0)
    draw = ImageDraw.Draw(mask)
    margin_x = int(width * 0.035 * scale)
    margin_y = int(height * 0.08 * scale)
    radius = int(height * 0.36 * scale)
    draw.rounded_rectangle(
        (margin_x, margin_y, width * scale - margin_x, height * scale - margin_y),
        radius=radius,
        fill=245,
    )
    return mask.resize(size, Image.Resampling.LANCZOS).filter(ImageFilter.GaussianBlur(radius=0.25))


def prepare_background(panel: Image.Image, size: tuple[int, int]) -> Image.Image:
    bg = ImageOps.fit(panel, size, method=Image.Resampling.LANCZOS, centering=(0.5, 0.5))
    bg = ImageEnhance.Color(bg).enhance(1.12)
    bg = ImageEnhance.Contrast(bg).enhance(1.08)
    bg = ImageEnhance.Brightness(bg).enhance(1.02)
    rgba = bg.convert("RGBA")
    rgba.putalpha(banner_mask(size))
    return rgba


def text_size(draw: ImageDraw.ImageDraw, text: str, font: ImageFont.FreeTypeFont, stroke_width: int = 0) -> tuple[int, int]:
    left, top, right, bottom = draw.textbbox((0, 0), text, font=font, stroke_width=stroke_width)
    return right - left, bottom - top


def fitted_font(text: str, max_width: int, max_height: int, start_size: int, weight: int, stroke_width: int) -> ImageFont.FreeTypeFont:
    dummy = Image.new("RGBA", (10, 10), (0, 0, 0, 0))
    draw = ImageDraw.Draw(dummy)
    size = start_size
    while size > 20:
        font = load_font(size, weight=weight, width=82)
        width, height = text_size(draw, text, font, stroke_width)
        if width <= max_width and height <= max_height:
            return font
        size -= 2
    return load_font(20, weight=weight, width=82)


def draw_glow_text(
    image: Image.Image,
    text: str,
    center: tuple[int, int],
    font: ImageFont.FreeTypeFont,
    fill: tuple[int, int, int],
    stroke: tuple[int, int, int],
    glow: tuple[int, int, int],
    stroke_width: int,
    glow_radius: float,
) -> None:
    text_layer = Image.new("RGBA", image.size, (0, 0, 0, 0))
    text_draw = ImageDraw.Draw(text_layer)
    bbox = text_draw.textbbox((0, 0), text, font=font, stroke_width=stroke_width)
    x = center[0] - (bbox[2] - bbox[0]) // 2
    y = center[1] - (bbox[3] - bbox[1]) // 2 - bbox[1]

    glow_layer = Image.new("RGBA", image.size, (0, 0, 0, 0))
    glow_draw = ImageDraw.Draw(glow_layer)
    glow_draw.text((x, y), text, font=font, fill=(*glow, 210), stroke_width=stroke_width + 2, stroke_fill=(*glow, 180))
    glow_layer = glow_layer.filter(ImageFilter.GaussianBlur(radius=glow_radius))
    image.alpha_composite(glow_layer)

    shadow_layer = Image.new("RGBA", image.size, (0, 0, 0, 0))
    shadow_draw = ImageDraw.Draw(shadow_layer)
    shadow_draw.text((x + 4, y + 6), text, font=font, fill=(0, 0, 0, 150), stroke_width=stroke_width, stroke_fill=(0, 0, 0, 160))
    image.alpha_composite(shadow_layer)

    text_draw.text(
        (x, y),
        text,
        font=font,
        fill=(*fill, 255),
        stroke_width=stroke_width,
        stroke_fill=(*stroke, 255),
    )
    image.alpha_composite(text_layer)


def compose_banner(
    panel: Image.Image,
    theme_key: str,
    title: str,
    subtitle: str,
    output_stem: str,
    output_size: tuple[int, int],
    fill: tuple[int, int, int],
    stroke: tuple[int, int, int],
    accent: tuple[int, int, int],
) -> Image.Image:
    image = prepare_background(panel, output_size)
    draw = ImageDraw.Draw(image)
    width, height = output_size

    title_font = fitted_font(title, int(width * 0.68), int(height * 0.42), int(height * 0.34), 900, 5)
    subtitle_font = fitted_font(subtitle, int(width * 0.54), int(height * 0.17), int(height * 0.12), 700, 2)

    title_y = int(height * (0.45 if height >= 300 else 0.44))
    subtitle_y = int(height * (0.70 if height >= 300 else 0.72))
    draw_glow_text(image, title, (width // 2, title_y), title_font, fill, stroke, accent, 5, 5.0)
    draw_glow_text(image, subtitle, (width // 2, subtitle_y), subtitle_font, accent, (15, 17, 46), fill, 2, 3.5)

    out = OUT_DIR / f"{output_stem}_{theme_key}.webp"
    image.save(out, "WEBP", quality=92, method=6)
    print(f"{out.relative_to(ROOT)} {image.size[0]}x{image.size[1]} {out.stat().st_size} bytes")
    return image


def build_preview(images: list[tuple[str, Image.Image]]) -> None:
    columns = 5
    thumb_w = 240
    thumb_h = 78
    label_h = 24
    gap = 14
    rows = 2
    width = gap + columns * (thumb_w + gap)
    height = gap + rows * (label_h + thumb_h + gap)
    preview = Image.new("RGB", (width, height), (6, 5, 16))
    draw = ImageDraw.Draw(preview)
    preview_font = load_font(16, weight=600)
    for index, (label, image) in enumerate(images):
        column = index % columns
        row = index // columns
        x = gap + column * (thumb_w + gap)
        y = gap + row * (label_h + thumb_h + gap)
        draw.text((x, y), label, font=preview_font, fill=(246, 240, 255))
        base = Image.new("RGBA", (thumb_w, thumb_h), (10, 8, 24, 255))
        thumb = image.copy()
        thumb.thumbnail((thumb_w, thumb_h), Image.Resampling.LANCZOS)
        base.alpha_composite(thumb, ((thumb_w - thumb.width) // 2, (thumb_h - thumb.height) // 2))
        preview.paste(base.convert("RGB"), (x, y + label_h))
    PREVIEW.parent.mkdir(parents=True, exist_ok=True)
    preview.save(PREVIEW)
    print(PREVIEW.relative_to(ROOT))


def main() -> None:
    if not SOURCE.exists():
        raise SystemExit(f"Missing source image: {SOURCE}")
    verify_font()

    source = Image.open(SOURCE).convert("RGB")
    previews = []
    for index, (theme_key, label, fill, stroke, accent) in enumerate(THEMES):
        panel = panel_source(source, index)
        big = compose_banner(
            panel,
            theme_key,
            "ВЫИГРЫШ!",
            "виртуальные монеты",
            "slot_big_win_banner",
            BIG_SIZE,
            fill,
            stroke,
            accent,
        )
        bonus = compose_banner(
            panel,
            theme_key,
            "ФРИСПИНЫ!",
            "бонусный режим",
            "slot_bonus_free_spins_banner",
            BONUS_SIZE,
            fill,
            stroke,
            accent,
        )
        previews.append((f"{label} win", big))
        previews.append((f"{label} bonus", bonus))
    build_preview(previews)


if __name__ == "__main__":
    main()
