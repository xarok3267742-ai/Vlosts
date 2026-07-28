from pathlib import Path
from collections import Counter

from PIL import Image, ImageDraw, ImageFilter, ImageOps


ROOT = Path(__file__).resolve().parents[1]
SOURCE_WIDE = ROOT / "qa/source/vslot_home_locked_slot_pulse_imagegen.png"
SOURCE_TALL = ROOT / "qa/source/vslot_home_locked_slot_pulse_land_imagegen.png"
DRAWABLE = ROOT / "app/src/main/res/drawable-nodpi"
OUT_WIDE = DRAWABLE / "home_locked_slot_pulse.webp"
OUT_TALL = DRAWABLE / "home_locked_slot_pulse_land.webp"
PREVIEW = ROOT / "qa/screenshots/home_locked_slot_pulse_contact_sheet.png"


def remove_chroma_key(image: Image.Image) -> Image.Image:
    rgba = image.convert("RGBA")
    pixels = rgba.load()
    width, height = rgba.size
    border_samples = []
    for x in range(width):
        border_samples.append(pixels[x, 0][:3])
        border_samples.append(pixels[x, height - 1][:3])
    for y in range(height):
        border_samples.append(pixels[0, y][:3])
        border_samples.append(pixels[width - 1, y][:3])

    quantized = Counter(tuple(channel // 4 * 4 for channel in sample) for sample in border_samples)
    key = quantized.most_common(1)[0][0]
    out = Image.new("RGBA", rgba.size)
    out_pixels = out.load()
    for y in range(height):
        for x in range(width):
            r, g, b, a = pixels[x, y]
            distance = ((r - key[0]) ** 2 + (g - key[1]) ** 2 + (b - key[2]) ** 2) ** 0.5
            if distance < 24:
                alpha = 0
            elif distance < 118:
                alpha = int(((distance - 24) / 94) * a)
            else:
                alpha = a

            if alpha:
                if g > 90 and g > r * 1.18 and g > b * 1.28 and r < 190:
                    alpha = 0
                # Despill only pixels still dominated by the green key, preserving cyan scanlines.
                green_spill = max(0, g - max(r, b) - 22)
                g = max(max(r, b), g - int(green_spill * 0.58))
            out_pixels[x, y] = (r, g, b, alpha)

    alpha = out.getchannel("A").filter(ImageFilter.GaussianBlur(0.28))
    out.putalpha(alpha)
    return out


def fit_to_canvas(image: Image.Image, size: tuple[int, int]) -> Image.Image:
    cropped = image.crop(image.getbbox())
    fitted = ImageOps.contain(cropped, size, Image.Resampling.LANCZOS)
    canvas = Image.new("RGBA", size, (0, 0, 0, 0))
    x = (size[0] - fitted.width) // 2
    y = (size[1] - fitted.height) // 2
    canvas.alpha_composite(fitted, (x, y))
    return canvas


def make_card_mockup(size: tuple[int, int], overlay: Image.Image, title: str) -> Image.Image:
    card = Image.new("RGBA", size, (9, 10, 30, 255))
    draw = ImageDraw.Draw(card)
    draw.rounded_rectangle((10, 10, size[0] - 10, size[1] - 10), radius=34, fill=(15, 18, 50, 255), outline=(128, 91, 255, 255), width=7)
    reel = (58, 112, size[0] - 58, size[1] - 116)
    draw.rounded_rectangle(reel, radius=22, fill=(5, 8, 26, 255), outline=(88, 208, 255, 152), width=4)
    column_width = (reel[2] - reel[0]) // 5
    for column in range(5):
        x = reel[0] + column * column_width
        draw.line((x, reel[1], x, reel[3]), fill=(74, 92, 156, 102), width=2)
    row_height = (reel[3] - reel[1]) // 3
    for row in range(1, 3):
        y = reel[1] + row * row_height
        draw.line((reel[0], y, reel[2], y), fill=(74, 92, 156, 96), width=2)
    for x in range(reel[0] + 34, reel[2], column_width):
        for y in range(reel[1] + 34, reel[3], row_height):
            draw.ellipse((x - 23, y - 23, x + 23, y + 23), fill=(98, 60, 170, 255), outline=(255, 213, 90, 210), width=3)
    draw.rounded_rectangle((size[0] // 2 - 168, 34, size[0] // 2 + 168, 88), radius=24, fill=(22, 20, 62, 238), outline=(255, 215, 100, 180), width=3)
    draw.text((size[0] // 2, 50), title, anchor="mm", fill=(255, 246, 214, 255))
    card.alpha_composite(overlay.resize(size, Image.Resampling.LANCZOS))
    return card


def save_contact_sheet(wide: Image.Image, tall: Image.Image) -> None:
    checker_wide = ImageOps.contain(wide, (420, 270), Image.Resampling.LANCZOS)
    checker_tall = ImageOps.contain(tall, (220, 300), Image.Resampling.LANCZOS)
    mock_wide = ImageOps.contain(make_card_mockup((980, 620), wide, "LOCKED WIDE"), (420, 270), Image.Resampling.LANCZOS)
    mock_tall = ImageOps.contain(make_card_mockup((720, 980), tall, "LOCKED TALL"), (220, 300), Image.Resampling.LANCZOS)
    sheet = Image.new("RGB", (980, 790), (18, 20, 34))
    draw = ImageDraw.Draw(sheet)
    draw.text((28, 26), "home locked slot pulse: transparent assets and card mockups", fill=(240, 235, 215))

    boxes = [
        (28, 78, checker_wide, "wide transparent"),
        (520, 78, mock_wide, "wide on card"),
        (110, 410, checker_tall, "tall transparent"),
        (570, 410, mock_tall, "tall on card"),
    ]
    for x, y, image, label in boxes:
        tile = Image.new("RGB", (image.width + 28, image.height + 50), (31, 34, 52))
        tile_draw = ImageDraw.Draw(tile)
        tile_draw.text((14, 12), label, fill=(227, 222, 204))
        tile.paste(image.convert("RGB"), (14, 36))
        sheet.paste(tile, (x, y))

    PREVIEW.parent.mkdir(parents=True, exist_ok=True)
    sheet.save(PREVIEW)


def validate(image: Image.Image, name: str) -> None:
    alpha = image.getchannel("A")
    corners = [alpha.getpixel((0, 0)), alpha.getpixel((image.width - 1, 0)), alpha.getpixel((0, image.height - 1)), alpha.getpixel((image.width - 1, image.height - 1))]
    if any(value > 2 for value in corners):
        raise RuntimeError(f"{name} corners must stay transparent: {corners}")
    visible = sum(1 for value in alpha.getdata() if value > 8)
    if visible < image.width * image.height * 0.16:
        raise RuntimeError(f"{name} visible area is too small: {visible}")


def main() -> None:
    wide = fit_to_canvas(remove_chroma_key(Image.open(SOURCE_WIDE)), (980, 620))
    tall = fit_to_canvas(remove_chroma_key(Image.open(SOURCE_TALL)), (720, 980))
    validate(wide, "home_locked_slot_pulse")
    validate(tall, "home_locked_slot_pulse_land")
    DRAWABLE.mkdir(parents=True, exist_ok=True)
    wide.save(OUT_WIDE, "WEBP", quality=95, method=6)
    tall.save(OUT_TALL, "WEBP", quality=95, method=6)
    save_contact_sheet(wide, tall)


if __name__ == "__main__":
    raise SystemExit(
        "NONCANONICAL_HISTORICAL_SLICER: retained for provenance only; "
        "running it would overwrite reviewed application artwork"
    )
