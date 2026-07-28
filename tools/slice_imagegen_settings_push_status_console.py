#!/usr/bin/env python3
from __future__ import annotations

from pathlib import Path

from PIL import Image, ImageDraw


from vslot_asset_toolchain import verify_asset_toolchain

verify_asset_toolchain()

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "qa/source/vslot_settings_push_status_console_imagegen.png"
OUT_DIR = ROOT / "app/src/main/res/drawable-nodpi"
OUT_CONSOLE = OUT_DIR / "settings_push_status_console.webp"
OUT_SIGNAL = OUT_DIR / "settings_push_status_signal_pulse.webp"
PREVIEW = ROOT / "qa/screenshots/settings_push_status_console_contact_sheet.png"
CONSOLE_SIZE = (760, 164)
SIGNAL_SIZE = (220, 220)
CONSOLE_ASPECT = CONSOLE_SIZE[0] / CONSOLE_SIZE[1]


def is_chroma(pixel: tuple[int, int, int, int]) -> bool:
    r, g, b, _ = pixel
    return g >= 145 and g - r >= 78 and g - b >= 78


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
            if a > 0 and g >= 128 and g - r >= 54 and g - b >= 54:
                pixels[x, y] = (r, g, b, 0)
            elif a > 0 and g > r and g > b:
                pixels[x, y] = (r, min(g, max(r, b) + 10), b, a)
    return rgba


def crop_console(image: Image.Image) -> Image.Image:
    left, top, right, bottom = alpha_bbox(image)
    left = max(0, left - 28)
    top = max(0, top - 22)
    right = min(image.width, right + 28)
    bottom = min(image.height, bottom + 22)
    cropped = image.crop((left, top, right, bottom))
    crop_aspect = cropped.width / cropped.height
    if crop_aspect > CONSOLE_ASPECT:
        new_height = round(cropped.width / CONSOLE_ASPECT)
        canvas = Image.new("RGBA", (cropped.width, new_height), (0, 0, 0, 0))
        canvas.alpha_composite(cropped, (0, (new_height - cropped.height) // 2))
    else:
        new_width = round(cropped.height * CONSOLE_ASPECT)
        canvas = Image.new("RGBA", (new_width, cropped.height), (0, 0, 0, 0))
        canvas.alpha_composite(cropped, ((new_width - cropped.width) // 2, 0))
    return final_edge_clean(canvas.resize(CONSOLE_SIZE, Image.Resampling.LANCZOS))


def crop_signal(console: Image.Image) -> Image.Image:
    # The left beacon is deliberately isolated so Android can pulse it independently.
    signal_crop = console.crop((0, 0, 184, console.height))
    canvas = Image.new("RGBA", (SIGNAL_SIZE[0], SIGNAL_SIZE[1]), (0, 0, 0, 0))
    signal_crop.thumbnail((SIGNAL_SIZE[0] - 10, SIGNAL_SIZE[1] - 10), Image.Resampling.LANCZOS)
    canvas.alpha_composite(signal_crop, ((SIGNAL_SIZE[0] - signal_crop.width) // 2, (SIGNAL_SIZE[1] - signal_crop.height) // 2))
    return final_edge_clean(canvas)


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
        if a > 0 and g >= 128 and g - r >= 54 and g - b >= 54
    )
    if green_pixels:
        raise SystemExit(f"{name} still has strong chroma pixels: {green_pixels}")
    opaque = sum(1 for pixel in image.getdata() if pixel[3] > 24)
    if opaque < min_opaque:
        raise SystemExit(f"{name} subject coverage too low: {opaque}")
    return alpha_bbox(image), opaque


def save_contact_sheet(console: Image.Image, signal: Image.Image) -> None:
    PREVIEW.parent.mkdir(parents=True, exist_ok=True)
    sheet = Image.new("RGB", (1600, 900), (13, 10, 28))
    draw = ImageDraw.Draw(sheet)

    source = Image.open(SOURCE).convert("RGB")
    source.thumbnail((720, 300), Image.Resampling.LANCZOS)
    sheet.paste(source, (40, 62))
    draw.text((40, 28), "imagegen source on chroma key", fill=(232, 222, 255))

    panel_cell = Image.new("RGBA", (720, 300), (18, 14, 36, 255))
    panel_preview = console.copy()
    panel_preview.thumbnail((680, 150), Image.Resampling.LANCZOS)
    panel_cell.alpha_composite(panel_preview, ((720 - panel_preview.width) // 2, (300 - panel_preview.height) // 2))
    sheet.paste(panel_cell.convert("RGB"), (840, 62))
    draw.text((840, 28), "transparent status console", fill=(232, 222, 255))

    signal_cell = Image.new("RGBA", (720, 300), (18, 14, 36, 255))
    signal_preview = signal.copy()
    signal_preview.thumbnail((210, 210), Image.Resampling.LANCZOS)
    signal_cell.alpha_composite(signal_preview, ((720 - signal_preview.width) // 2, (300 - signal_preview.height) // 2))
    sheet.paste(signal_cell.convert("RGB"), (40, 520))
    draw.text((40, 486), "separate signal pulse asset", fill=(232, 222, 255))

    mock = Image.new("RGBA", (720, 300), (8, 6, 20, 255))
    mock_draw = ImageDraw.Draw(mock)
    mock_draw.rounded_rectangle((74, 54, 646, 236), radius=28, fill=(32, 24, 62), outline=(117, 86, 205), width=3)
    meter = console.resize((430, 93), Image.Resampling.LANCZOS)
    pulse = signal.resize((74, 74), Image.Resampling.LANCZOS)
    mock.alpha_composite(meter, (145, 102))
    mock.alpha_composite(pulse, (152, 111))
    mock_draw.rounded_rectangle((255, 126, 510, 169), radius=14, fill=(48, 22, 72), outline=(240, 196, 98), width=2)
    mock_draw.text((96, 254), "settings status row mock with label bay", fill=(225, 218, 255))
    sheet.paste(mock.convert("RGB"), (840, 520))
    draw.text((840, 486), "settings row mock", fill=(232, 222, 255))
    sheet.save(PREVIEW)


def main() -> None:
    if not SOURCE.exists():
        raise SystemExit(f"Missing imagegen source: {SOURCE}")
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    keyed = remove_chroma_key(Image.open(SOURCE))
    console = crop_console(keyed)
    signal = crop_signal(console)
    console_bbox, console_opaque = validate(console, "console", min_opaque=75_000)
    signal_bbox, signal_opaque = validate(signal, "signal", min_opaque=14_000)
    console.save(OUT_CONSOLE, "WEBP", quality=96, method=6)
    signal.save(OUT_SIGNAL, "WEBP", quality=96, method=6)
    save_contact_sheet(console, signal)
    print(f"{OUT_CONSOLE}\t{OUT_CONSOLE.stat().st_size}\t{console.width}x{console.height}\tbbox={console_bbox}\topaque={console_opaque}")
    print(f"{OUT_SIGNAL}\t{OUT_SIGNAL.stat().st_size}\t{signal.width}x{signal.height}\tbbox={signal_bbox}\topaque={signal_opaque}")
    print(f"preview={PREVIEW}")


if __name__ == "__main__":
    main()
