from collections import Counter
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageOps


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "qa/source/vslot_home_slot_unlock_burst_imagegen.png"
DRAWABLE = ROOT / "app/src/main/res/drawable-nodpi"
PREVIEW = ROOT / "qa/screenshots/home_slot_unlock_burst_contact_sheet.png"

WIDE_SIZE = (980, 608)
LAND_SIZE = (720, 980)


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

    key = Counter(tuple(channel // 4 * 4 for channel in sample) for sample in border_samples).most_common(1)[0][0]
    out = Image.new("RGBA", rgba.size)
    out_pixels = out.load()
    for y in range(height):
        for x in range(width):
            r, g, b, a = pixels[x, y]
            distance = ((r - key[0]) ** 2 + (g - key[1]) ** 2 + (b - key[2]) ** 2) ** 0.5
            if distance < 30:
                alpha = 0
            elif distance < 132:
                alpha = int(((distance - 30) / 102) * a)
            else:
                alpha = a

            if alpha and g > 105 and g > r * 1.15 and g > b * 1.22 and r < 210:
                alpha = 0
            if alpha:
                green_spill = max(0, g - max(r, b) - 18)
                g = max(max(r, b), g - int(green_spill * 0.68))
            out_pixels[x, y] = (r, g, b, alpha)

    alpha = out.getchannel("A").filter(ImageFilter.GaussianBlur(0.2))
    out.putalpha(alpha)
    return out


def trim_alpha(image: Image.Image) -> Image.Image:
    bbox = image.getchannel("A").getbbox()
    if not bbox:
        raise RuntimeError("source image lost all visible pixels")
    return image.crop(bbox)


def fit_overlay(image: Image.Image, size: tuple[int, int], padding: tuple[int, int]) -> Image.Image:
    cropped = trim_alpha(image)
    target = (size[0] - padding[0] * 2, size[1] - padding[1] * 2)
    fitted = ImageOps.contain(cropped, target, Image.Resampling.LANCZOS)
    canvas = Image.new("RGBA", size, (0, 0, 0, 0))
    canvas.alpha_composite(fitted, ((size[0] - fitted.width) // 2, (size[1] - fitted.height) // 2))
    return canvas


def save_webp(image: Image.Image, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    image.save(path, "WEBP", quality=95, method=6)


def validate(image: Image.Image, name: str) -> None:
    alpha = image.getchannel("A")
    corners = [
        alpha.getpixel((0, 0)),
        alpha.getpixel((image.width - 1, 0)),
        alpha.getpixel((0, image.height - 1)),
        alpha.getpixel((image.width - 1, image.height - 1)),
    ]
    if any(value > 4 for value in corners):
        raise RuntimeError(f"{name} corners must be transparent: {corners}")
    visible = sum(1 for value in alpha.getdata() if value > 10)
    if visible < image.width * image.height * 0.06:
        raise RuntimeError(f"{name} visible coverage is too low: {visible}")
    if visible > image.width * image.height * 0.74:
        raise RuntimeError(f"{name} visible coverage is too high for an overlay: {visible}")


def make_wide_mock(overlay: Image.Image) -> Image.Image:
    panel = Image.new("RGBA", WIDE_SIZE, (12, 13, 31, 255))
    draw = ImageDraw.Draw(panel)
    draw.rounded_rectangle((18, 24, WIDE_SIZE[0] - 18, WIDE_SIZE[1] - 24), radius=34, fill=(23, 19, 54, 255), outline=(205, 174, 86, 230), width=8)
    draw.rounded_rectangle((58, 72, WIDE_SIZE[0] - 58, WIDE_SIZE[1] - 70), radius=26, fill=(34, 30, 76, 255), outline=(116, 202, 255, 160), width=4)
    draw.ellipse((84, 122, 254, 292), fill=(76, 44, 132, 255), outline=(236, 207, 132, 200), width=5)
    draw.ellipse((WIDE_SIZE[0] - 256, 136, WIDE_SIZE[0] - 92, 300), fill=(28, 84, 122, 255), outline=(236, 207, 132, 200), width=5)
    draw.rounded_rectangle((330, 178, 650, 356), radius=30, fill=(10, 12, 34, 255), outline=(236, 207, 132, 210), width=5)
    panel.alpha_composite(overlay)
    return panel


def make_land_mock(overlay: Image.Image) -> Image.Image:
    panel = Image.new("RGBA", LAND_SIZE, (12, 13, 31, 255))
    draw = ImageDraw.Draw(panel)
    draw.rounded_rectangle((28, 24, LAND_SIZE[0] - 28, LAND_SIZE[1] - 24), radius=34, fill=(21, 20, 55, 255), outline=(205, 174, 86, 230), width=8)
    for index in range(3):
        y = 104 + index * 242
        draw.rounded_rectangle((90, y, LAND_SIZE[0] - 90, y + 176), radius=28, fill=(34, 30, 76, 255), outline=(116, 202, 255, 150), width=4)
        draw.ellipse((LAND_SIZE[0] // 2 - 72, y + 28, LAND_SIZE[0] // 2 + 72, y + 172), fill=(56, 50, 118, 255), outline=(236, 207, 132, 200), width=5)
    panel.alpha_composite(overlay)
    return panel


def save_contact_sheet(wide: Image.Image, land: Image.Image) -> None:
    sheet = Image.new("RGB", (1580, 900), (16, 18, 32))
    draw = ImageDraw.Draw(sheet)
    draw.text((28, 24), "home slot unlock burst: transparent assets over card mockups", fill=(240, 235, 218))
    wide_mock = ImageOps.contain(make_wide_mock(wide), (880, 548), Image.Resampling.LANCZOS)
    land_mock = ImageOps.contain(make_land_mock(land), (430, 760), Image.Resampling.LANCZOS)
    sheet.paste(wide_mock.convert("RGB"), (28, 110))
    sheet.paste(land_mock.convert("RGB"), (1040, 84))
    PREVIEW.parent.mkdir(parents=True, exist_ok=True)
    sheet.save(PREVIEW)


def main() -> None:
    source = remove_chroma_key(Image.open(SOURCE))
    width, height = source.size
    left = source.crop((0, 0, width // 2, height))
    right = source.crop((width // 2, 0, width, height))
    wide = fit_overlay(left, WIDE_SIZE, padding=(18, 18))
    land = fit_overlay(right, LAND_SIZE, padding=(18, 24))
    validate(wide, "home_slot_unlock_burst")
    validate(land, "home_slot_unlock_burst_land")
    save_webp(wide, DRAWABLE / "home_slot_unlock_burst.webp")
    save_webp(land, DRAWABLE / "home_slot_unlock_burst_land.webp")
    save_contact_sheet(wide, land)


if __name__ == "__main__":
    raise SystemExit(
        "NONCANONICAL_HISTORICAL_SLICER: retained for provenance only; "
        "running it would overwrite reviewed application artwork"
    )
