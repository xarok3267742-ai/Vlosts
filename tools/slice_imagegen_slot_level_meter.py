#!/usr/bin/env python3
from __future__ import annotations

from pathlib import Path

from PIL import Image, ImageDraw


from vslot_asset_toolchain import verify_asset_toolchain

verify_asset_toolchain()

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "qa/source/vslot_slot_level_meter_imagegen.png"
OUT_DIR = ROOT / "app/src/main/res/drawable-nodpi"
OUT_PANEL = OUT_DIR / "slot_level_session_panel.webp"
PREVIEW = ROOT / "qa/screenshots/slot_level_session_meter_contact_sheet.png"
TARGET_SIZE = (760, 168)
TARGET_ASPECT = TARGET_SIZE[0] / TARGET_SIZE[1]


def is_chroma(pixel: tuple[int, int, int, int]) -> bool:
    r, g, b, _ = pixel
    return g >= 150 and g - r >= 82 and g - b >= 82


def remove_chroma_key(image: Image.Image) -> Image.Image:
    rgba = image.convert("RGBA")
    pixels = rgba.load()
    width, height = rgba.size
    for y in range(height):
        for x in range(width):
            r, g, b, a = pixels[x, y]
            if is_chroma((r, g, b, a)):
                pixels[x, y] = (r, g, b, 0)
            elif g > r and g > b:
                # Despill antialiased edges without flattening cyan slot glow.
                pixels[x, y] = (r, min(g, max(r, b) + 18), b, a)
    return rgba


def alpha_bbox(image: Image.Image) -> tuple[int, int, int, int]:
    bbox = image.getchannel("A").getbbox()
    if bbox is None:
        raise SystemExit("No opaque panel pixels after chroma removal")
    return bbox


def crop_and_pad(image: Image.Image) -> Image.Image:
    left, top, right, bottom = alpha_bbox(image)
    pad_x = 26
    pad_y = 20
    left = max(0, left - pad_x)
    top = max(0, top - pad_y)
    right = min(image.width, right + pad_x)
    bottom = min(image.height, bottom + pad_y)
    cropped = image.crop((left, top, right, bottom))

    crop_aspect = cropped.width / cropped.height
    if crop_aspect > TARGET_ASPECT:
        new_height = round(cropped.width / TARGET_ASPECT)
        canvas = Image.new("RGBA", (cropped.width, new_height), (0, 0, 0, 0))
        canvas.alpha_composite(cropped, (0, (new_height - cropped.height) // 2))
    else:
        new_width = round(cropped.height * TARGET_ASPECT)
        canvas = Image.new("RGBA", (new_width, cropped.height), (0, 0, 0, 0))
        canvas.alpha_composite(cropped, ((new_width - cropped.width) // 2, 0))
    return final_edge_clean(canvas.resize(TARGET_SIZE, Image.Resampling.LANCZOS))


def final_edge_clean(image: Image.Image) -> Image.Image:
    rgba = image.convert("RGBA")
    pixels = rgba.load()
    for y in range(rgba.height):
        for x in range(rgba.width):
            r, g, b, a = pixels[x, y]
            if a > 0 and g >= 132 and g - r >= 58 and g - b >= 58:
                pixels[x, y] = (r, g, b, 0)
            elif a > 0 and g > r and g > b:
                pixels[x, y] = (r, min(g, max(r, b) + 12), b, a)
    return rgba


def save_contact_sheet(panel: Image.Image) -> None:
    PREVIEW.parent.mkdir(parents=True, exist_ok=True)
    sheet = Image.new("RGB", (1600, 900), (13, 10, 28))
    draw = ImageDraw.Draw(sheet)

    source = Image.open(SOURCE).convert("RGB")
    source.thumbnail((720, 300), Image.Resampling.LANCZOS)
    sheet.paste(source, (40, 62))
    draw.text((40, 28), "imagegen source on chroma key", fill=(232, 222, 255))

    dark_cell = Image.new("RGBA", (720, 300), (18, 14, 36, 255))
    preview = panel.copy()
    preview.thumbnail((680, 170), Image.Resampling.LANCZOS)
    dark_cell.alpha_composite(preview, ((720 - preview.width) // 2, (300 - preview.height) // 2))
    sheet.paste(dark_cell.convert("RGB"), (840, 62))
    draw.text((840, 28), "transparent slot level meter", fill=(232, 222, 255))

    portrait_mock = Image.new("RGBA", (720, 320), (8, 6, 20, 255))
    landscape_mock = Image.new("RGBA", (720, 320), (8, 6, 20, 255))
    for canvas, scale, label in (
        (portrait_mock, (178, 39), "portrait cabinet placement"),
        (landscape_mock, (154, 34), "landscape cabinet placement"),
    ):
        mock_draw = ImageDraw.Draw(canvas)
        mock_draw.rounded_rectangle((68, 24, 652, 296), radius=30, fill=(31, 22, 64), outline=(134, 96, 222), width=3)
        mock_draw.rounded_rectangle((104, 68, 616, 112), radius=20, fill=(24, 16, 52), outline=(219, 179, 82), width=2)
        mock_draw.rounded_rectangle((112, 120, 608, 270), radius=18, fill=(9, 7, 18), outline=(80, 210, 246), width=2)
        mock_draw.text((96, 292), label, fill=(225, 218, 255))
        meter = panel.resize(scale, Image.Resampling.LANCZOS)
        canvas.alpha_composite(meter, (96, 44))
        mock_draw.rectangle((132, 61, 144, 76), fill=(245, 230, 112))
        mock_draw.rounded_rectangle((178, 64, 274, 70), radius=3, fill=(252, 62, 232))

    sheet.paste(portrait_mock.convert("RGB"), (40, 520))
    sheet.paste(landscape_mock.convert("RGB"), (840, 520))
    draw.text((40, 486), "meter over reel cabinet mock", fill=(232, 222, 255))
    draw.text((840, 486), "compact orientation mock", fill=(232, 222, 255))
    sheet.save(PREVIEW)


def validate(panel: Image.Image) -> tuple[int, tuple[int, int, int, int], int]:
    corners = [
        panel.getpixel((0, 0))[3],
        panel.getpixel((panel.width - 1, 0))[3],
        panel.getpixel((0, panel.height - 1))[3],
        panel.getpixel((panel.width - 1, panel.height - 1))[3],
    ]
    if any(corners):
        raise SystemExit(f"Panel corners must be transparent, alpha={corners}")
    bbox = alpha_bbox(panel)
    opaque = sum(1 for pixel in panel.getdata() if pixel[3] > 24)
    green_pixels = sum(
        1
        for r, g, b, a in panel.getdata()
        if a > 0 and g >= 150 and g - r >= 82 and g - b >= 82
    )
    if green_pixels:
        raise SystemExit(f"Panel still has strong chroma pixels: {green_pixels}")
    if opaque < 26_000:
        raise SystemExit(f"Panel subject coverage too low: {opaque}")
    return opaque, bbox, green_pixels


def main() -> None:
    if not SOURCE.exists():
        raise SystemExit(f"Missing imagegen source: {SOURCE}")
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    keyed = remove_chroma_key(Image.open(SOURCE))
    panel = crop_and_pad(keyed)
    opaque, bbox, green_pixels = validate(panel)
    panel.save(OUT_PANEL, "WEBP", quality=96, method=6)
    save_contact_sheet(panel)
    print(f"{OUT_PANEL}\t{OUT_PANEL.stat().st_size}\t{panel.width}x{panel.height}")
    print(f"bbox={bbox}\topaque_pixels={opaque}\tstrong_green_pixels={green_pixels}")
    print(f"preview={PREVIEW}")


if __name__ == "__main__":
    main()
