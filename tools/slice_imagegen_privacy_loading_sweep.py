#!/usr/bin/env python3
from __future__ import annotations

from pathlib import Path

from PIL import Image, ImageDraw


from vslot_asset_toolchain import verify_asset_toolchain

verify_asset_toolchain()

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "qa/source/vslot_privacy_loading_sweep_imagegen.png"
OUT_DIR = ROOT / "app/src/main/res/drawable-nodpi"
OUT_SWEEP = OUT_DIR / "privacy_loading_sweep.webp"
PREVIEW = ROOT / "qa/screenshots/privacy_loading_sweep_contact_sheet.png"
SWEEP_SIZE = (520, 760)


def is_chroma(pixel: tuple[int, int, int, int]) -> bool:
    r, g, b, a = pixel
    if a == 0:
        return True
    return g >= 136 and g - r >= 62 and g - b >= 62


def remove_chroma_key(image: Image.Image) -> Image.Image:
    rgba = image.convert("RGBA")
    pixels = rgba.load()
    for y in range(rgba.height):
        for x in range(rgba.width):
            r, g, b, a = pixels[x, y]
            if is_chroma((r, g, b, a)):
                pixels[x, y] = (r, g, b, 0)
            elif a > 0 and g > r and g > b:
                pixels[x, y] = (r, min(g, max(r, b) + 10), b, a)
    return rgba


def alpha_bbox(image: Image.Image) -> tuple[int, int, int, int]:
    bbox = image.getchannel("A").getbbox()
    if bbox is None:
        raise SystemExit("No visible sweep pixels after chroma removal")
    return bbox


def final_edge_clean(image: Image.Image) -> Image.Image:
    rgba = image.convert("RGBA")
    pixels = rgba.load()
    for y in range(rgba.height):
        for x in range(rgba.width):
            r, g, b, a = pixels[x, y]
            if a > 0 and g >= 120 and g - r >= 44 and g - b >= 44:
                pixels[x, y] = (r, g, b, 0)
            elif a > 0 and g > r and g > b:
                pixels[x, y] = (r, min(g, max(r, b) + 8), b, a)
    return rgba


def crop_sweep(image: Image.Image) -> Image.Image:
    left, top, right, bottom = alpha_bbox(image)
    pad_x = 52
    pad_y = 74
    left = max(0, left - pad_x)
    top = max(0, top - pad_y)
    right = min(image.width, right + pad_x)
    bottom = min(image.height, bottom + pad_y)
    crop = image.crop((left, top, right, bottom))
    target_aspect = SWEEP_SIZE[0] / SWEEP_SIZE[1]
    aspect = crop.width / crop.height
    if aspect > target_aspect:
        new_height = round(crop.width / target_aspect)
        canvas = Image.new("RGBA", (crop.width, new_height), (0, 0, 0, 0))
        canvas.alpha_composite(crop, (0, (new_height - crop.height) // 2))
    else:
        new_width = round(crop.height * target_aspect)
        canvas = Image.new("RGBA", (new_width, crop.height), (0, 0, 0, 0))
        canvas.alpha_composite(crop, ((new_width - crop.width) // 2, 0))
    return final_edge_clean(canvas.resize(SWEEP_SIZE, Image.Resampling.LANCZOS))


def validate(image: Image.Image) -> tuple[tuple[int, int, int, int], int]:
    corners = [
        image.getpixel((0, 0))[3],
        image.getpixel((image.width - 1, 0))[3],
        image.getpixel((0, image.height - 1))[3],
        image.getpixel((image.width - 1, image.height - 1))[3],
    ]
    if any(corners):
        raise SystemExit(f"sweep corners must be transparent, alpha={corners}")
    green_pixels = sum(
        1
        for r, g, b, a in image.getdata()
        if a > 0 and g >= 120 and g - r >= 44 and g - b >= 44
    )
    if green_pixels:
        raise SystemExit(f"sweep still has strong chroma pixels: {green_pixels}")
    visible = sum(1 for pixel in image.getdata() if pixel[3] > 18)
    min_visible = 34_000
    max_visible = 220_000
    if not (min_visible <= visible <= max_visible):
        raise SystemExit(f"sweep coverage outside expected range: {visible}")
    return alpha_bbox(image), visible


def paste_center(canvas: Image.Image, asset: Image.Image, box: tuple[int, int, int, int]) -> None:
    left, top, width, height = box
    image = asset.copy()
    image.thumbnail((width, height), Image.Resampling.LANCZOS)
    canvas.alpha_composite(image, (left + (width - image.width) // 2, top + (height - image.height) // 2))


def save_contact_sheet(sweep: Image.Image) -> None:
    PREVIEW.parent.mkdir(parents=True, exist_ok=True)
    sheet = Image.new("RGB", (1500, 920), (12, 9, 26))
    draw = ImageDraw.Draw(sheet)

    source = Image.open(SOURCE).convert("RGB")
    source.thumbnail((430, 645), Image.Resampling.LANCZOS)
    sheet.paste(source, (72, 78))
    draw.text((72, 40), "imagegen source on chroma key", fill=(232, 222, 255))

    transparent_cell = Image.new("RGBA", (430, 645), (18, 14, 36, 255))
    paste_center(transparent_cell, sweep, (48, 32, 334, 580))
    sheet.paste(transparent_cell.convert("RGB"), (540, 78))
    draw.text((540, 40), "transparent privacy sweep asset", fill=(232, 222, 255))

    mock = Image.new("RGBA", (430, 645), (8, 6, 20, 255))
    mock_draw = ImageDraw.Draw(mock)
    mock_draw.rounded_rectangle((24, 24, 406, 621), radius=28, fill=(27, 20, 54), outline=(126, 94, 222), width=4)
    mock_draw.rounded_rectangle((62, 80, 368, 566), radius=22, fill=(9, 16, 30), outline=(76, 203, 231), width=2)
    paste_center(mock, sweep, (70, 60, 290, 500))
    shield = Image.open(OUT_DIR / "privacy_loading_shield.webp").convert("RGBA")
    rail = Image.open(OUT_DIR / "privacy_loading_scan_rail.webp").convert("RGBA")
    paste_center(mock, shield, (112, 194, 206, 206))
    paste_center(mock, rail, (108, 380, 214, 62))
    sheet.paste(mock.convert("RGB"), (1008, 78))
    draw.text((1008, 40), "mock over privacy WebView panel", fill=(232, 222, 255))

    draw.text((72, 790), "No text, no money imagery, decorative only; intended behind shield/scan rail.", fill=(228, 220, 250))
    sheet.save(PREVIEW)


def main() -> None:
    if not SOURCE.exists():
        raise SystemExit(f"Missing imagegen source: {SOURCE}")
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    keyed = remove_chroma_key(Image.open(SOURCE))
    sweep = crop_sweep(keyed)
    bbox, visible = validate(sweep)
    sweep.save(OUT_SWEEP, "WEBP", quality=96, method=6)
    save_contact_sheet(sweep)
    print(f"{OUT_SWEEP}\t{OUT_SWEEP.stat().st_size}\t{sweep.width}x{sweep.height}\tbbox={bbox}\tvisible={visible}")
    print(f"preview={PREVIEW}")


if __name__ == "__main__":
    main()
