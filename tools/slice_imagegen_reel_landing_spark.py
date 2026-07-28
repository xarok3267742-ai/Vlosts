from collections import Counter
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageOps


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "qa/source/vslot_reel_landing_spark_imagegen.png"
DRAWABLE = ROOT / "app/src/main/res/drawable-nodpi"
PREVIEW = ROOT / "qa/screenshots/reel_landing_spark_contact_sheet.png"

ASSET_SIZE = (320, 1080)
ASSET_NAME = "reel_landing_spark.webp"


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
            if distance < 26:
                alpha = 0
            elif distance < 132:
                alpha = int(((distance - 26) / 106) * a)
            else:
                alpha = a

            if alpha and g > 108 and g > r * 1.12 and g > b * 1.18 and r < 220:
                alpha = 0
            if alpha:
                green_spill = max(0, g - max(r, b) - 14)
                g = max(max(r, b), g - int(green_spill * 0.72))
            out_pixels[x, y] = (r, g, b, alpha)

    alpha = out.getchannel("A").filter(ImageFilter.GaussianBlur(0.16))
    out.putalpha(alpha)
    return out


def trim_alpha(image: Image.Image) -> Image.Image:
    alpha = image.getchannel("A")
    bbox = alpha.getbbox()
    if not bbox:
        raise RuntimeError("all visible pixels were removed")
    return image.crop(bbox)


def fit_overlay(image: Image.Image) -> Image.Image:
    cropped = trim_alpha(image)
    fitted = ImageOps.contain(cropped, (ASSET_SIZE[0] - 20, ASSET_SIZE[1] - 28), Image.Resampling.LANCZOS)
    canvas = Image.new("RGBA", ASSET_SIZE, (0, 0, 0, 0))
    canvas.alpha_composite(
        fitted,
        ((ASSET_SIZE[0] - fitted.width) // 2, (ASSET_SIZE[1] - fitted.height) // 2),
    )
    return canvas


def validate(image: Image.Image) -> None:
    alpha = image.getchannel("A")
    corners = [
        alpha.getpixel((0, 0)),
        alpha.getpixel((image.width - 1, 0)),
        alpha.getpixel((0, image.height - 1)),
        alpha.getpixel((image.width - 1, image.height - 1)),
    ]
    if any(value > 4 for value in corners):
        raise RuntimeError(f"landing spark corners must be transparent: {corners}")
    visible = sum(1 for value in alpha.getdata() if value > 10)
    area = image.width * image.height
    if visible < area * 0.06:
        raise RuntimeError(f"landing spark visible coverage is too low: {visible}")
    if visible > area * 0.58:
        raise RuntimeError(f"landing spark visible coverage is too high: {visible}")
    if alpha.getextrema()[1] < 220:
        raise RuntimeError("landing spark needs a strong bright impact center")


def save_webp(image: Image.Image, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    image.save(path, "WEBP", quality=95, method=6)


def make_reel_mock(overlay: Image.Image, label: str, scale: float) -> Image.Image:
    tile = Image.new("RGBA", (360, 900), (12, 14, 30, 255))
    draw = ImageDraw.Draw(tile)
    draw.rounded_rectangle((20, 46, 340, 852), radius=28, fill=(24, 24, 48, 255), outline=(210, 174, 88, 185), width=4)
    draw.rounded_rectangle((42, 72, 318, 826), radius=22, fill=(7, 9, 24, 255), outline=(94, 166, 224, 130), width=2)
    for row in range(3):
        y = 100 + row * 222
        draw.rounded_rectangle((74, y, 286, y + 172), radius=18, fill=(42, 35, 78, 255), outline=(148, 137, 196, 120), width=2)
        draw.ellipse((126, y + 30, 234, y + 126), fill=(75, 58, 124, 255), outline=(239, 210, 128, 150), width=3)
    resized = overlay.resize((int(overlay.width * scale), int(overlay.height * scale)), Image.Resampling.LANCZOS)
    tile.alpha_composite(resized, ((tile.width - resized.width) // 2, 32))
    draw.text((22, 16), label, fill=(238, 232, 214))
    return tile


def save_contact_sheet(asset: Image.Image) -> None:
    sheet = Image.new("RGB", (1120, 960), (16, 18, 32))
    draw = ImageDraw.Draw(sheet)
    draw.text((28, 24), "reel landing spark: transparent imagegen asset over reel mockups", fill=(240, 235, 218))
    previews = [
        make_reel_mock(asset, "normal stop", 0.76),
        make_reel_mock(asset, "strong stop", 0.9),
        make_reel_mock(asset, "landscape fit", 0.68),
    ]
    x = 28
    for preview in previews:
        sheet.paste(preview.convert("RGB"), (x, 64))
        x += 364
    PREVIEW.parent.mkdir(parents=True, exist_ok=True)
    sheet.save(PREVIEW)


def main() -> None:
    asset = fit_overlay(remove_chroma_key(Image.open(SOURCE)))
    validate(asset)
    save_webp(asset, DRAWABLE / ASSET_NAME)
    save_contact_sheet(asset)


if __name__ == "__main__":
    raise SystemExit(
        "NONCANONICAL_HISTORICAL_SLICER: retained for provenance only; "
        "running it would overwrite reviewed application artwork"
    )
