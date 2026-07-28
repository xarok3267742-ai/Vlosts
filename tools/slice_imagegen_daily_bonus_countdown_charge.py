#!/usr/bin/env python3
from __future__ import annotations

from pathlib import Path

from PIL import Image, ImageDraw


from vslot_asset_toolchain import verify_asset_toolchain

verify_asset_toolchain()

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "qa/source/vslot_daily_bonus_countdown_charge_imagegen.png"
OUT_DIR = ROOT / "app/src/main/res/drawable-nodpi"
OUT_CHARGE = OUT_DIR / "daily_bonus_countdown_charge.webp"
PREVIEW = ROOT / "qa/screenshots/daily_bonus_countdown_charge_contact_sheet.png"
ASSET_SIZE = (540, 144)


def is_chroma(pixel: tuple[int, int, int, int]) -> bool:
    r, g, b, a = pixel
    if a == 0:
        return True
    return g >= 128 and g - r >= 62 and g - b >= 62


def remove_chroma_key(image: Image.Image) -> Image.Image:
    rgba = image.convert("RGBA")
    pixels = rgba.load()
    for y in range(rgba.height):
        for x in range(rgba.width):
            r, g, b, a = pixels[x, y]
            if is_chroma((r, g, b, a)):
                pixels[x, y] = (r, g, b, 0)
            elif a > 0 and g > r and g > b:
                pixels[x, y] = (r, min(g, max(r, b) + 8), b, a)
    return rgba


def alpha_bbox(image: Image.Image) -> tuple[int, int, int, int]:
    bbox = image.getchannel("A").getbbox()
    if bbox is None:
        raise SystemExit("No visible charge pixels after chroma removal")
    return bbox


def crop_charge(image: Image.Image) -> Image.Image:
    left, top, right, bottom = alpha_bbox(image)
    pad_x = 78
    pad_y = 46
    left = max(0, left - pad_x)
    top = max(0, top - pad_y)
    right = min(image.width, right + pad_x)
    bottom = min(image.height, bottom + pad_y)
    crop = image.crop((left, top, right, bottom))

    target_aspect = ASSET_SIZE[0] / ASSET_SIZE[1]
    aspect = crop.width / crop.height
    if aspect > target_aspect:
        new_height = round(crop.width / target_aspect)
        canvas = Image.new("RGBA", (crop.width, new_height), (0, 0, 0, 0))
        canvas.alpha_composite(crop, (0, (new_height - crop.height) // 2))
    else:
        new_width = round(crop.height * target_aspect)
        canvas = Image.new("RGBA", (new_width, crop.height), (0, 0, 0, 0))
        canvas.alpha_composite(crop, ((new_width - crop.width) // 2, 0))
    return final_edge_clean(canvas.resize(ASSET_SIZE, Image.Resampling.LANCZOS))


def final_edge_clean(image: Image.Image) -> Image.Image:
    rgba = image.convert("RGBA")
    pixels = rgba.load()
    for y in range(rgba.height):
        for x in range(rgba.width):
            r, g, b, a = pixels[x, y]
            if a > 0 and g >= 124 and g - r >= 44 and g - b >= 44:
                pixels[x, y] = (r, g, b, 0)
            elif a > 0 and g > r and g > b:
                pixels[x, y] = (r, min(g, max(r, b) + 6), b, a)
    return rgba


def validate(image: Image.Image) -> tuple[tuple[int, int, int, int], int]:
    corner_alpha = [
        image.getpixel((0, 0))[3],
        image.getpixel((image.width - 1, 0))[3],
        image.getpixel((0, image.height - 1))[3],
        image.getpixel((image.width - 1, image.height - 1))[3],
    ]
    if any(corner_alpha):
        raise SystemExit(f"charge corners must be transparent, alpha={corner_alpha}")
    green_pixels = sum(
        1
        for r, g, b, a in image.getdata()
        if a > 0 and g >= 124 and g - r >= 44 and g - b >= 44
    )
    if green_pixels:
        raise SystemExit(f"charge still has strong chroma pixels: {green_pixels}")
    visible = sum(1 for pixel in image.getdata() if pixel[3] > 18)
    if not (7_000 <= visible <= 62_000):
        raise SystemExit(f"charge coverage outside expected range: {visible}")
    return alpha_bbox(image), visible


def paste_center(canvas: Image.Image, asset: Image.Image, box: tuple[int, int, int, int]) -> None:
    left, top, width, height = box
    image = asset.copy()
    image.thumbnail((width, height), Image.Resampling.LANCZOS)
    canvas.alpha_composite(image, (left + (width - image.width) // 2, top + (height - image.height) // 2))


def save_contact_sheet(charge: Image.Image) -> None:
    PREVIEW.parent.mkdir(parents=True, exist_ok=True)
    sheet = Image.new("RGB", (1500, 760), (10, 8, 24))
    draw = ImageDraw.Draw(sheet)

    source = Image.open(SOURCE).convert("RGB")
    source.thumbnail((430, 270), Image.Resampling.LANCZOS)
    sheet.paste(source, (70, 96))
    draw.text((70, 56), "imagegen source on chroma key", fill=(232, 222, 255))

    transparent_cell = Image.new("RGBA", (430, 270), (18, 14, 38, 255))
    paste_center(transparent_cell, charge, (22, 38, 386, 104))
    sheet.paste(transparent_cell.convert("RGB"), (540, 96))
    draw.text((540, 56), "transparent countdown charge", fill=(232, 222, 255))

    mock = Image.new("RGBA", (430, 270), (17, 13, 36, 255))
    rail = Image.open(OUT_DIR / "daily_bonus_countdown_rail.webp").convert("RGBA")
    paste_center(mock, charge, (42, 82, 342, 84))
    paste_center(mock, rail, (42, 82, 342, 84))
    mock_draw = ImageDraw.Draw(mock)
    mock_draw.rounded_rectangle((178, 122, 352, 155), radius=8, outline=(255, 225, 78), width=2)
    mock_draw.text((202, 128), "18:42:09", fill=(255, 229, 65))
    sheet.paste(mock.convert("RGB"), (1008, 96))
    draw.text((1008, 56), "mock behind existing home rail", fill=(232, 222, 255))

    draw.text((70, 432), "Decorative only. No text in asset, no money imagery, no prize/cashout cues.", fill=(228, 220, 250))
    sheet.save(PREVIEW)


def main() -> None:
    if not SOURCE.exists():
        raise SystemExit(f"Missing imagegen source: {SOURCE}")
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    keyed = remove_chroma_key(Image.open(SOURCE))
    charge = crop_charge(keyed)
    bbox, visible = validate(charge)
    charge.save(OUT_CHARGE, "WEBP", quality=96, method=6)
    save_contact_sheet(charge)
    print(f"{OUT_CHARGE}\t{OUT_CHARGE.stat().st_size}\t{charge.width}x{charge.height}\tbbox={bbox}\tvisible={visible}")
    print(f"preview={PREVIEW}")


if __name__ == "__main__":
    main()
