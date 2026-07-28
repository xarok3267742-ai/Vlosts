#!/usr/bin/env python3
from __future__ import annotations

from pathlib import Path

from PIL import Image, ImageDraw


from vslot_asset_toolchain import verify_asset_toolchain

verify_asset_toolchain()

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "qa/source/vslot_privacy_loading_overlay_imagegen.png"
OUT_DIR = ROOT / "app/src/main/res/drawable-nodpi"
OUT_SHIELD = OUT_DIR / "privacy_loading_shield.webp"
OUT_SCAN_RAIL = OUT_DIR / "privacy_loading_scan_rail.webp"
PREVIEW = ROOT / "qa/screenshots/privacy_loading_overlay_contact_sheet.png"
SHIELD_SIZE = (640, 640)
RAIL_SIZE = (620, 180)


def is_chroma(pixel: tuple[int, int, int, int]) -> bool:
    r, g, b, _ = pixel
    return g >= 145 and g - r >= 76 and g - b >= 76


def remove_chroma_key(image: Image.Image) -> Image.Image:
    rgba = image.convert("RGBA")
    pixels = rgba.load()
    for y in range(rgba.height):
        for x in range(rgba.width):
            r, g, b, a = pixels[x, y]
            if is_chroma((r, g, b, a)):
                pixels[x, y] = (r, g, b, 0)
            elif a > 0 and g > r and g > b:
                pixels[x, y] = (r, min(g, max(r, b) + 14), b, a)
    return rgba


def alpha_bbox(image: Image.Image) -> tuple[int, int, int, int]:
    bbox = image.getchannel("A").getbbox()
    if bbox is None:
        raise SystemExit("No opaque pixels after chroma removal")
    return bbox


def final_edge_clean(image: Image.Image) -> Image.Image:
    rgba = image.convert("RGBA")
    pixels = rgba.load()
    for y in range(rgba.height):
        for x in range(rgba.width):
            r, g, b, a = pixels[x, y]
            if a > 0 and g >= 128 and g - r >= 52 and g - b >= 52:
                pixels[x, y] = (r, g, b, 0)
            elif a > 0 and g > r and g > b:
                pixels[x, y] = (r, min(g, max(r, b) + 10), b, a)
    return rgba


def crop_square(image: Image.Image) -> Image.Image:
    left, top, right, bottom = alpha_bbox(image)
    pad = 34
    left = max(0, left - pad)
    top = max(0, top - pad)
    right = min(image.width, right + pad)
    bottom = min(image.height, bottom + pad)
    crop = image.crop((left, top, right, bottom))
    side = max(crop.width, crop.height)
    canvas = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    canvas.alpha_composite(crop, ((side - crop.width) // 2, (side - crop.height) // 2))
    return final_edge_clean(canvas.resize(SHIELD_SIZE, Image.Resampling.LANCZOS))


def crop_scan_rail(image: Image.Image) -> Image.Image:
    left, top, right, bottom = alpha_bbox(image)
    height = bottom - top
    rail_top = top + round(height * 0.70)
    rail_crop = image.crop((max(0, left - 24), rail_top, min(image.width, right + 24), min(image.height, bottom + 22)))
    bbox = alpha_bbox(rail_crop)
    rail_crop = rail_crop.crop((
        max(0, bbox[0] - 18),
        max(0, bbox[1] - 14),
        min(rail_crop.width, bbox[2] + 18),
        min(rail_crop.height, bbox[3] + 14),
    ))
    target_aspect = RAIL_SIZE[0] / RAIL_SIZE[1]
    aspect = rail_crop.width / rail_crop.height
    if aspect > target_aspect:
        new_height = round(rail_crop.width / target_aspect)
        canvas = Image.new("RGBA", (rail_crop.width, new_height), (0, 0, 0, 0))
        canvas.alpha_composite(rail_crop, (0, (new_height - rail_crop.height) // 2))
    else:
        new_width = round(rail_crop.height * target_aspect)
        canvas = Image.new("RGBA", (new_width, rail_crop.height), (0, 0, 0, 0))
        canvas.alpha_composite(rail_crop, ((new_width - rail_crop.width) // 2, 0))
    return final_edge_clean(canvas.resize(RAIL_SIZE, Image.Resampling.LANCZOS))


def validate(image: Image.Image, name: str, min_opaque: int) -> tuple[tuple[int, int, int, int], int]:
    corners = [
        image.getpixel((0, 0))[3],
        image.getpixel((image.width - 1, 0))[3],
        image.getpixel((0, image.height - 1))[3],
        image.getpixel((image.width - 1, image.height - 1))[3],
    ]
    if any(corners):
        raise SystemExit(f"{name} corners must be transparent, alpha={corners}")
    green_pixels = sum(
        1
        for r, g, b, a in image.getdata()
        if a > 0 and g >= 128 and g - r >= 52 and g - b >= 52
    )
    if green_pixels:
        raise SystemExit(f"{name} still has strong chroma pixels: {green_pixels}")
    opaque = sum(1 for pixel in image.getdata() if pixel[3] > 24)
    if opaque < min_opaque:
        raise SystemExit(f"{name} subject coverage too low: {opaque}")
    return alpha_bbox(image), opaque


def save_contact_sheet(shield: Image.Image, rail: Image.Image) -> None:
    PREVIEW.parent.mkdir(parents=True, exist_ok=True)
    sheet = Image.new("RGB", (1600, 900), (13, 10, 28))
    draw = ImageDraw.Draw(sheet)

    source = Image.open(SOURCE).convert("RGB")
    source.thumbnail((440, 440), Image.Resampling.LANCZOS)
    sheet.paste(source, (150, 48))
    draw.text((40, 28), "imagegen source on chroma key", fill=(232, 222, 255))

    shield_cell = Image.new("RGBA", (720, 420), (18, 14, 36, 255))
    shield_preview = shield.copy()
    shield_preview.thumbnail((340, 340), Image.Resampling.LANCZOS)
    shield_cell.alpha_composite(shield_preview, ((720 - shield_preview.width) // 2, (420 - shield_preview.height) // 2))
    sheet.paste(shield_cell.convert("RGB"), (840, 48))
    draw.text((840, 28), "transparent loading shield", fill=(232, 222, 255))

    rail_cell = Image.new("RGBA", (720, 260), (18, 14, 36, 255))
    rail_preview = rail.copy()
    rail_preview.thumbnail((560, 140), Image.Resampling.LANCZOS)
    rail_cell.alpha_composite(rail_preview, ((720 - rail_preview.width) // 2, (260 - rail_preview.height) // 2))
    sheet.paste(rail_cell.convert("RGB"), (40, 590))
    draw.text((40, 552), "separate scan rail", fill=(232, 222, 255))

    mock = Image.new("RGBA", (720, 260), (8, 6, 20, 255))
    mock_draw = ImageDraw.Draw(mock)
    mock_draw.rounded_rectangle((54, 18, 666, 242), radius=28, fill=(30, 22, 58), outline=(119, 88, 210), width=3)
    mock_shield = shield.resize((156, 156), Image.Resampling.LANCZOS)
    mock_rail = rail.resize((214, 62), Image.Resampling.LANCZOS)
    mock.alpha_composite(mock_shield, (282, 36))
    mock.alpha_composite(mock_rail, (253, 150))
    mock_draw.text((92, 222), "privacy loading overlay over WebView frame", fill=(225, 218, 255))
    sheet.paste(mock.convert("RGB"), (840, 590))
    draw.text((840, 552), "privacy screen mock", fill=(232, 222, 255))
    sheet.save(PREVIEW)


def main() -> None:
    if not SOURCE.exists():
        raise SystemExit(f"Missing imagegen source: {SOURCE}")
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    keyed = remove_chroma_key(Image.open(SOURCE))
    shield = crop_square(keyed)
    rail = crop_scan_rail(keyed)
    shield_bbox, shield_opaque = validate(shield, "shield", min_opaque=90_000)
    rail_bbox, rail_opaque = validate(rail, "scan rail", min_opaque=35_000)
    shield.save(OUT_SHIELD, "WEBP", quality=96, method=6)
    rail.save(OUT_SCAN_RAIL, "WEBP", quality=96, method=6)
    save_contact_sheet(shield, rail)
    print(f"{OUT_SHIELD}\t{OUT_SHIELD.stat().st_size}\t{shield.width}x{shield.height}\tbbox={shield_bbox}\topaque={shield_opaque}")
    print(f"{OUT_SCAN_RAIL}\t{OUT_SCAN_RAIL.stat().st_size}\t{rail.width}x{rail.height}\tbbox={rail_bbox}\topaque={rail_opaque}")
    print(f"preview={PREVIEW}")


if __name__ == "__main__":
    main()
