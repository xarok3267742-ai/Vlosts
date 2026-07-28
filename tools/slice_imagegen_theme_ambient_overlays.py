#!/usr/bin/env python3
from __future__ import annotations

from pathlib import Path

from PIL import Image, ImageDraw


from vslot_asset_toolchain import verify_asset_toolchain

verify_asset_toolchain()

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "qa/source/vslot_theme_ambient_overlays_imagegen.png"
OUT_DIR = ROOT / "app/src/main/res/drawable-nodpi"
PREVIEW = ROOT / "qa/screenshots/theme_ambient_overlays_contact_sheet.png"


OVERLAYS = {
    "violet": (8, 12, 508, 508),
    "roman": (518, 12, 1018, 508),
    "neon": (1028, 12, 1528, 508),
    "pharaoh": (8, 520, 764, 1016),
    "ocean": (772, 520, 1528, 1016),
}

TARGET_SIZE = (1200, 860)


def extract_vfx_alpha(image: Image.Image) -> Image.Image:
    rgba = image.convert("RGBA")
    pixels = rgba.load()
    width, height = rgba.size
    for y in range(height):
        for x in range(width):
            r, g, b, _ = pixels[x, y]
            brightest = max(r, g, b)
            darkest = min(r, g, b)
            saturation = brightest - darkest
            if brightest < 46 or (brightest < 82 and saturation < 18):
                pixels[x, y] = (r, g, b, 0)
                continue
            alpha_base = float_coerce((brightest - 42) / 213, 0.0, 1.0)
            alpha = int(alpha_base ** 1.22 * 255)
            if saturation > 52:
                alpha = min(255, int(alpha * 1.18))
            pixels[x, y] = (r, g, b, alpha)
    return rgba


def build_assets() -> list[tuple[str, Image.Image]]:
    source = Image.open(SOURCE)
    rendered: list[tuple[str, Image.Image]] = []
    for theme, box in OVERLAYS.items():
        crop = source.crop(box).resize(TARGET_SIZE, Image.Resampling.LANCZOS)
        asset = extract_vfx_alpha(crop)
        filename = f"theme_ambient_overlay_{theme}.webp"
        out = OUT_DIR / filename
        asset.save(out, "WEBP", quality=96, method=6)
        rendered.append((filename, asset))
    return rendered


def save_preview(rendered: list[tuple[str, Image.Image]]) -> None:
    PREVIEW.parent.mkdir(parents=True, exist_ok=True)
    cell_w, cell_h = 420, 300
    sheet = Image.new("RGB", (cell_w * 2, cell_h * 3), (8, 11, 24))
    draw = ImageDraw.Draw(sheet)
    for index, (name, image) in enumerate(rendered):
        col = index % 2
        row = index // 2
        x = col * cell_w
        y = row * cell_h
        draw.rectangle((x + 8, y + 8, x + cell_w - 8, y + cell_h - 8), fill=(18, 24, 44))
        preview = image.copy()
        preview.thumbnail((cell_w - 24, cell_h - 44), Image.Resampling.LANCZOS)
        px = x + (cell_w - preview.width) // 2
        py = y + 12 + (cell_h - 44 - preview.height) // 2
        sheet.paste(preview, (px, py), preview)
        draw.text((x + 16, y + cell_h - 24), name, fill=(230, 238, 255))
    sheet.save(PREVIEW)


def float_coerce(value: float, minimum: float, maximum: float) -> float:
    return max(minimum, min(maximum, value))


def main() -> None:
    if not SOURCE.exists():
        raise SystemExit(f"Missing imagegen source: {SOURCE}")
    rendered = build_assets()
    save_preview(rendered)
    for name, image in rendered:
        path = OUT_DIR / name
        print(f"{name}\t{path.stat().st_size}\t{image.width}x{image.height}")
    print(f"preview\t{PREVIEW}")


if __name__ == "__main__":
    main()
