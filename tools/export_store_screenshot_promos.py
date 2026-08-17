#!/usr/bin/env python3
"""Build deterministic Google Play promo screenshots from real V Slot UI captures."""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import math
from pathlib import Path
import zipfile

from PIL import Image, ImageChops, ImageDraw, ImageEnhance, ImageFilter, ImageFont

from vslot_asset_fonts import FONT_PATH, load_font, verify_font


ROOT = Path(__file__).resolve().parents[1]
OUTPUT_DIR = ROOT / "docs/store/assets/screenshots-promo-v2"
SOURCE_DIR = OUTPUT_DIR / "sources"
MANIFEST_PATH = OUTPUT_DIR / "manifest.json"
README_PATH = OUTPUT_DIR / "README.md"
ARCHIVE_PATH = ROOT / "docs/store/assets/v-slot-google-play-screenshots-v2.zip"
CANVAS_SIZE = (1080, 1920)
BACKGROUND_PATH = SOURCE_DIR / "v-slot-promo-background-v1.png"
ICON_PATH = ROOT / "docs/store/assets/v-slot-icon-512-v2.png"

BACKGROUND_PROMPT = (
    "Original premium 9:16 V Slot promotional background: deep near-black violet space, "
    "faceted crystal edge geometry, restrained gold particles and cyan energy accents, "
    "with a calm dark center. No text, UI, logos, currency, people, or watermark."
)

SLIDES = (
    {
        "output": "01-five-themes.png",
        "source": ROOT / "docs/store/assets/screenshots/01-home.png",
        "line_1": "ПЯТЬ ТЕМ",
        "line_2": "ОДНА ИГРА",
        "banner": "bottom",
        "accent_a": "#F0047F",
        "accent_b": "#9A1AFF",
        "tint": "#7A104F",
        "phone_tilt": -0.8,
    },
    {
        "output": "02-spin-and-lines.png",
        "source": ROOT / "docs/store/assets/screenshots/02-violet-slot.png",
        "line_1": "КРУТИ БАРАБАНЫ",
        "line_2": "ВЫБИРАЙ ЛИНИИ",
        "banner": "top",
        "accent_a": "#7E24F2",
        "accent_b": "#DA168D",
        "tint": "#3A177C",
        "phone_tilt": 0.7,
        "phone_max_width": 740,
        "phone_max_height": 1460,
        "phone_y": 395,
    },
    {
        "output": "03-paytable.png",
        "source": ROOT / "docs/store/assets/screenshots/03-paytable.png",
        "line_1": "ВСЕ ВЫПЛАТЫ",
        "line_2": "ПОД РУКОЙ",
        "banner": "bottom",
        "accent_a": "#075FE8",
        "accent_b": "#08B8F2",
        "tint": "#063B78",
        "phone_tilt": -0.5,
    },
    {
        "output": "04-settings.png",
        "source": ROOT / "docs/store/assets/screenshots/04-settings.png",
        "line_1": "НАСТРОЙ ИГРУ",
        "line_2": "ПОД СЕБЯ",
        "banner": "top",
        "accent_a": "#1C9AF2",
        "accent_b": "#7132F5",
        "tint": "#073C73",
        "phone_tilt": 0.5,
    },
    {
        "output": "05-free-spins.png",
        "source": ROOT / "docs/store/assets/screenshots/05-free-spins.png",
        "line_1": "ФРИСПИНЫ",
        "line_2": "БЕЗ ПОКУПОК",
        "banner": "bottom",
        "accent_a": "#FF8A00",
        "accent_b": "#EC187E",
        "tint": "#7D3208",
        "phone_tilt": -0.7,
    },
)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def hex_rgb(value: str) -> tuple[int, int, int]:
    value = value.removeprefix("#")
    return tuple(int(value[index : index + 2], 16) for index in (0, 2, 4))


def cover(image: Image.Image, size: tuple[int, int]) -> Image.Image:
    scale = max(size[0] / image.width, size[1] / image.height)
    resized = image.resize(
        (math.ceil(image.width * scale), math.ceil(image.height * scale)),
        Image.Resampling.LANCZOS,
    )
    left = (resized.width - size[0]) // 2
    top = (resized.height - size[1]) // 2
    return resized.crop((left, top, left + size[0], top + size[1]))


def rounded_mask(size: tuple[int, int], radius: int) -> Image.Image:
    mask = Image.new("L", size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, size[0] - 1, size[1] - 1), radius, fill=255)
    return mask


def add_background_detail(canvas: Image.Image, tint: str, index: int) -> None:
    width, height = canvas.size
    tint_layer = Image.new("RGBA", canvas.size, (*hex_rgb(tint), 255))
    canvas.paste(Image.blend(canvas, tint_layer, 0.13))

    glow = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(glow)
    centers = ((120, 320), (960, 680), (220, 1500), (910, 1730))
    colors = ((70, 210, 255, 105), (152, 47, 255, 110), (255, 181, 52, 85), (213, 36, 157, 90))
    for offset, ((cx, cy), color) in enumerate(zip(centers, colors)):
        radius = 150 + ((index + offset) % 3) * 45
        draw.ellipse((cx - radius, cy - radius, cx + radius, cy + radius), fill=color)
    canvas.alpha_composite(glow.filter(ImageFilter.GaussianBlur(105)))

    pattern = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    pattern_draw = ImageDraw.Draw(pattern)
    for row, y in enumerate(range(90, height, 150)):
        for column, x in enumerate(range(70, width, 150)):
            if (row + column + index) % 3:
                continue
            r = 13
            pattern_draw.polygon(
                ((x, y - r), (x + r, y), (x, y + r), (x - r, y)),
                outline=(132, 213, 255, 40),
                width=3,
            )
    canvas.alpha_composite(pattern)


def phone_mockup(slide: dict[str, object], index: int) -> Image.Image:
    source = Image.open(Path(slide["source"])).convert("RGB")
    source_crop = slide.get("source_crop")
    if source_crop is not None:
        source = source.crop(tuple(int(value) for value in source_crop))
    source = ImageEnhance.Color(source).enhance(1.04)
    source = ImageEnhance.Contrast(source).enhance(1.025)

    default_max_width = 810 if source.height / source.width < 2.05 else 690
    max_width = int(slide.get("phone_max_width", default_max_width))
    max_height = int(slide.get("phone_max_height", 1350))
    scale = min(max_width / source.width, max_height / source.height)
    inner_size = (round(source.width * scale), round(source.height * scale))
    source = source.resize(inner_size, Image.Resampling.LANCZOS)

    frame = 25
    outer_size = (inner_size[0] + frame * 2, inner_size[1] + frame * 2)
    phone = Image.new("RGBA", outer_size, (0, 0, 0, 0))
    phone_draw = ImageDraw.Draw(phone)
    phone_draw.rounded_rectangle(
        (0, 0, outer_size[0] - 1, outer_size[1] - 1),
        radius=66,
        fill=(5, 6, 11, 255),
        outline=(134, 148, 173, 255),
        width=6,
    )
    phone_draw.rounded_rectangle(
        (9, 9, outer_size[0] - 10, outer_size[1] - 10),
        radius=58,
        outline=(90, 42, 143, 220),
        width=5,
    )

    mask = rounded_mask(inner_size, 43)
    phone.paste(source.convert("RGBA"), (frame, frame), mask)

    shine = Image.new("RGBA", outer_size, (0, 0, 0, 0))
    shine_draw = ImageDraw.Draw(shine)
    shine_draw.line(
        (frame + 26, frame + 55, frame + 26, outer_size[1] - frame - 60),
        fill=(83, 222, 255, 115),
        width=4,
    )
    shine_draw.line(
        (outer_size[0] - frame - 25, frame + 80, outer_size[0] - frame - 25, outer_size[1] - frame - 80),
        fill=(255, 188, 66, 95),
        width=4,
    )
    phone.alpha_composite(shine.filter(ImageFilter.GaussianBlur(2)))
    return phone.rotate(float(slide["phone_tilt"]), resample=Image.Resampling.BICUBIC, expand=True)


def paste_phone(
    canvas: Image.Image,
    phone: Image.Image,
    banner_position: str,
    y_override: object | None = None,
) -> None:
    x = (canvas.width - phone.width) // 2
    y = int(y_override) if y_override is not None else (55 if banner_position == "bottom" else 425)

    shadow = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    shadow_shape = Image.new("L", phone.size, 0)
    ImageDraw.Draw(shadow_shape).rounded_rectangle(
        (20, 20, phone.width - 21, phone.height - 21), radius=70, fill=220
    )
    shadow_shape = shadow_shape.filter(ImageFilter.GaussianBlur(42))
    shadow_color = Image.new("RGBA", phone.size, (0, 0, 0, 215))
    shadow_color.putalpha(shadow_shape)
    shadow.alpha_composite(shadow_color, (x + 14, y + 28))
    canvas.alpha_composite(shadow)
    canvas.alpha_composite(phone, (x, y))


def gradient(size: tuple[int, int], first: str, second: str) -> Image.Image:
    first_rgb = hex_rgb(first)
    second_rgb = hex_rgb(second)
    image = Image.new("RGBA", size)
    draw = ImageDraw.Draw(image)
    for x in range(size[0]):
        ratio = x / max(1, size[0] - 1)
        color = tuple(round(a + (b - a) * ratio) for a, b in zip(first_rgb, second_rgb))
        draw.line((x, 0, x, size[1]), fill=(*color, 255))
    return image


def banner_mask(position: str) -> tuple[Image.Image, tuple[int, int, int, int]]:
    mask = Image.new("L", CANVAS_SIZE, 0)
    draw = ImageDraw.Draw(mask)
    if position == "top":
        polygon = ((0, 0), (1080, 0), (1080, 300), (0, 385))
        bounds = (0, 0, 1080, 385)
    else:
        polygon = ((0, 1550), (1080, 1470), (1080, 1920), (0, 1920))
        bounds = (0, 1470, 1080, 1920)
    draw.polygon(polygon, fill=255)
    return mask, bounds


def fit_font(text: str, target_width: int, start_size: int) -> ImageFont.FreeTypeFont:
    for size in range(start_size, 48, -2):
        font = load_font(size, weight=850, width=75)
        bbox = ImageDraw.Draw(Image.new("L", (1, 1))).textbbox(
            (0, 0), text, font=font, stroke_width=5
        )
        if bbox[2] - bbox[0] <= target_width:
            return font
    return load_font(48, weight=850, width=75)


def centered_text_layer(
    text: str,
    y: int,
    font: ImageFont.FreeTypeFont,
    shadow_color: tuple[int, int, int, int],
) -> Image.Image:
    layer = Image.new("RGBA", CANVAS_SIZE, (0, 0, 0, 0))
    shadow = Image.new("RGBA", CANVAS_SIZE, (0, 0, 0, 0))
    shadow_draw = ImageDraw.Draw(shadow)
    shadow_draw.text(
        (CANVAS_SIZE[0] // 2 + 9, y + 13),
        text,
        font=font,
        anchor="mm",
        fill=shadow_color,
        stroke_width=8,
        stroke_fill=shadow_color,
    )
    layer.alpha_composite(shadow.filter(ImageFilter.GaussianBlur(6)))
    draw = ImageDraw.Draw(layer)
    draw.text(
        (CANVAS_SIZE[0] // 2, y),
        text,
        font=font,
        anchor="mm",
        fill=(255, 255, 255, 255),
        stroke_width=6,
        stroke_fill=(56, 9, 70, 255),
    )
    return layer


def add_banner(canvas: Image.Image, slide: dict[str, object]) -> None:
    position = str(slide["banner"])
    mask, bounds = banner_mask(position)

    shadow = Image.new("RGBA", CANVAS_SIZE, (0, 0, 0, 0))
    shadow_alpha = mask.filter(ImageFilter.GaussianBlur(25))
    shadow.putalpha(shadow_alpha)
    black = Image.new("RGBA", CANVAS_SIZE, (0, 0, 0, 175))
    black.putalpha(shadow_alpha.point(lambda value: round(value * 0.72)))
    canvas.alpha_composite(ImageChops.offset(black, 0, 25 if position == "top" else -25))

    fill = gradient(CANVAS_SIZE, str(slide["accent_a"]), str(slide["accent_b"]))
    fill.putalpha(mask)
    canvas.alpha_composite(fill)

    texture = Image.new("RGBA", CANVAS_SIZE, (0, 0, 0, 0))
    texture_draw = ImageDraw.Draw(texture)
    for y in range(bounds[1] + 22, bounds[3], 72):
        for x in range(-20, CANVAS_SIZE[0] + 80, 115):
            texture_draw.rounded_rectangle(
                (x, y, x + 72, y + 42), radius=12, outline=(255, 255, 255, 28), width=4
            )
    texture.putalpha(ImageChops.multiply(texture.getchannel("A"), mask))
    canvas.alpha_composite(texture)

    edge = Image.new("RGBA", CANVAS_SIZE, (0, 0, 0, 0))
    edge_draw = ImageDraw.Draw(edge)
    if position == "top":
        edge_draw.line((0, 385, 1080, 300), fill=(255, 221, 105, 235), width=8)
        y1, y2 = 105, 225
    else:
        edge_draw.line((0, 1550, 1080, 1470), fill=(255, 221, 105, 235), width=8)
        y1, y2 = 1655, 1780
    canvas.alpha_composite(edge.filter(ImageFilter.GaussianBlur(1)))

    small_font = fit_font(str(slide["line_1"]), 960, 90)
    large_font = fit_font(str(slide["line_2"]), 980, 128)
    canvas.alpha_composite(centered_text_layer(str(slide["line_1"]), y1, small_font, (30, 2, 36, 235)))
    canvas.alpha_composite(centered_text_layer(str(slide["line_2"]), y2, large_font, (30, 2, 36, 235)))


def add_brand_chip(canvas: Image.Image, position: str) -> None:
    icon = Image.open(ICON_PATH).convert("RGBA").resize((96, 96), Image.Resampling.LANCZOS)
    chip = Image.new("RGBA", (132, 132), (0, 0, 0, 0))
    chip_draw = ImageDraw.Draw(chip)
    chip_draw.ellipse((3, 3, 128, 128), fill=(7, 8, 18, 245), outline=(255, 205, 88, 255), width=5)
    chip.alpha_composite(icon, (18, 18))
    x = 32 if position == "top" else CANVAS_SIZE[0] - chip.width - 32
    y = 270 if position == "top" else 1485
    canvas.alpha_composite(chip, (x, y))


def render(slide: dict[str, object], index: int) -> Path:
    background = cover(Image.open(BACKGROUND_PATH).convert("RGB"), CANVAS_SIZE).convert("RGBA")
    add_background_detail(background, str(slide["tint"]), index)
    phone = phone_mockup(slide, index)
    paste_phone(background, phone, str(slide["banner"]), slide.get("phone_y"))
    add_banner(background, slide)
    add_brand_chip(background, str(slide["banner"]))

    output = OUTPUT_DIR / str(slide["output"])
    background.convert("RGB").save(output, format="PNG", optimize=True, compress_level=9)
    return output


def build_manifest(outputs: list[Path]) -> dict[str, object]:
    slides = []
    for slide, output in zip(SLIDES, outputs):
        source = Path(slide["source"])
        slides.append(
            {
                "output": str(output.relative_to(ROOT)),
                "output_sha256": sha256(output),
                "source": str(source.relative_to(ROOT)),
                "source_sha256": sha256(source),
                "source_crop": list(slide["source_crop"]) if "source_crop" in slide else None,
                "copy": [slide["line_1"], slide["line_2"]],
                "banner_position": slide["banner"],
                "accent_colors": [slide["accent_a"], slide["accent_b"]],
            }
        )
    return {
        "schema_version": 1,
        "generator": "tools/export_store_screenshot_promos.py",
        "generator_sha256": sha256(Path(__file__)),
        "reference_url": "https://play.google.com/store/apps/details?id=com.LevenProduct.CasinoX&hl=ru",
        "reference_use": "Hierarchy and promotional framing only; no competitor assets are embedded.",
        "canvas": {"width": CANVAS_SIZE[0], "height": CANVAS_SIZE[1], "format": "PNG RGB"},
        "font": {
            "path": str(FONT_PATH.relative_to(ROOT)),
            "sha256": sha256(FONT_PATH),
            "weight": 850,
            "width": 75,
        },
        "generated_background": {
            "path": str(BACKGROUND_PATH.relative_to(ROOT)),
            "sha256": sha256(BACKGROUND_PATH),
            "tool": "built-in OpenAI image generation",
            "prompt": BACKGROUND_PROMPT,
        },
        "store_icon": {
            "path": str(ICON_PATH.relative_to(ROOT)),
            "sha256": sha256(ICON_PATH),
        },
        "slides": slides,
    }


def build_archive(outputs: list[Path]) -> bytes:
    members = [(output.name, output) for output in outputs]
    members.extend(((MANIFEST_PATH.name, MANIFEST_PATH), (README_PATH.name, README_PATH)))
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, mode="w", compression=zipfile.ZIP_STORED) as archive:
        for name, path in members:
            info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            info.create_system = 3
            info.external_attr = 0o100644 << 16
            archive.writestr(info, path.read_bytes(), compress_type=zipfile.ZIP_STORED)
    return buffer.getvalue()


def export() -> None:
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    if not BACKGROUND_PATH.is_file():
        raise SystemExit(f"Missing generated background: {BACKGROUND_PATH}")
    verify_font()
    outputs = [render(slide, index) for index, slide in enumerate(SLIDES)]
    MANIFEST_PATH.write_text(
        json.dumps(build_manifest(outputs), ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    if not README_PATH.is_file():
        raise SystemExit(f"Missing promo README: {README_PATH}")
    ARCHIVE_PATH.write_bytes(build_archive(outputs))
    print(f"Exported {len(outputs)} promo screenshots and {ARCHIVE_PATH.name}")


def check() -> None:
    if not MANIFEST_PATH.is_file():
        raise SystemExit(f"Missing manifest: {MANIFEST_PATH}")
    manifest = json.loads(MANIFEST_PATH.read_text(encoding="utf-8"))
    issues: list[str] = []
    if manifest.get("generator_sha256") != sha256(Path(__file__)):
        issues.append("generator changed since export")
    font = manifest.get("font", {})
    if font.get("path") != str(FONT_PATH.relative_to(ROOT)):
        issues.append("pinned font path mismatch")
    if font.get("sha256") != sha256(FONT_PATH):
        issues.append("pinned font hash mismatch")
    if font.get("weight") != 850 or font.get("width") != 75:
        issues.append("pinned font variation mismatch")
    background = manifest.get("generated_background", {})
    if background.get("sha256") != sha256(BACKGROUND_PATH):
        issues.append("generated background changed since export")
    for entry in manifest.get("slides", []):
        output = ROOT / entry["output"]
        source = ROOT / entry["source"]
        if not output.is_file():
            issues.append(f"missing output: {output.relative_to(ROOT)}")
            continue
        if not source.is_file():
            issues.append(f"missing source: {source.relative_to(ROOT)}")
            continue
        if sha256(output) != entry["output_sha256"]:
            issues.append(f"output hash mismatch: {output.relative_to(ROOT)}")
        if sha256(source) != entry["source_sha256"]:
            issues.append(f"source hash mismatch: {source.relative_to(ROOT)}")
        with Image.open(output) as image:
            if image.size != CANVAS_SIZE or image.mode != "RGB":
                issues.append(f"invalid output geometry/mode: {output.relative_to(ROOT)}")
    outputs = [OUTPUT_DIR / str(slide["output"]) for slide in SLIDES]
    if not README_PATH.is_file():
        issues.append(f"missing promo README: {README_PATH.relative_to(ROOT)}")
    if not ARCHIVE_PATH.is_file():
        issues.append(f"missing promo archive: {ARCHIVE_PATH.relative_to(ROOT)}")
    elif not issues and ARCHIVE_PATH.read_bytes() != build_archive(outputs):
        issues.append("promo archive is stale or non-deterministic")
    if issues:
        raise SystemExit("Promo screenshot verification failed: " + "; ".join(issues))
    print("Promo screenshot exports are current.")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    check() if args.check else export()


if __name__ == "__main__":
    main()
