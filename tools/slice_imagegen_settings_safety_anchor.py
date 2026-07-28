from collections import Counter
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageOps


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "qa/source/vslot_settings_safety_anchor_imagegen.png"
DRAWABLE = ROOT / "app/src/main/res/drawable-nodpi"
PREVIEW = ROOT / "qa/screenshots/settings_safety_anchor_contact_sheet.png"

ASSET_SIZE = (1180, 360)
ASSET_NAME = "settings_safety_anchor.webp"


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
            if distance < 28:
                alpha = 0
            elif distance < 132:
                alpha = int(((distance - 28) / 104) * a)
            else:
                alpha = a

            if alpha and g > 112 and g > r * 1.12 and g > b * 1.18 and r < 220:
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
    fitted = ImageOps.contain(cropped, (ASSET_SIZE[0] - 24, ASSET_SIZE[1] - 26), Image.Resampling.LANCZOS)
    canvas = Image.new("RGBA", ASSET_SIZE, (0, 0, 0, 0))
    canvas.alpha_composite(
        fitted,
        ((ASSET_SIZE[0] - fitted.width) // 2, ASSET_SIZE[1] - fitted.height - 6),
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
    if any(value > 6 for value in corners):
        raise RuntimeError(f"safety anchor corners must be transparent: {corners}")
    visible = sum(1 for value in alpha.getdata() if value > 10)
    area = image.width * image.height
    if visible < area * 0.12:
        raise RuntimeError(f"safety anchor visible coverage is too low: {visible}")
    if visible > area * 0.78:
        raise RuntimeError(f"safety anchor visible coverage is too high: {visible}")
    if alpha.getextrema()[1] < 190:
        raise RuntimeError("safety anchor needs a strong opaque cabinet base")


def save_webp(image: Image.Image, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    image.save(path, "WEBP", quality=95, method=6)


def make_mock(anchor: Image.Image, label: str, size: tuple[int, int]) -> Image.Image:
    width, height = size
    tile = Image.new("RGBA", size, (13, 15, 34, 255))
    draw = ImageDraw.Draw(tile)
    draw.rounded_rectangle((24, 24, width - 24, height - 24), radius=34, fill=(31, 31, 58, 255), outline=(92, 157, 214, 110), width=2)
    anchor_resized = anchor.resize((width - 62, int((width - 62) / anchor.width * anchor.height)), Image.Resampling.LANCZOS)
    tile.alpha_composite(anchor_resized, (31, height - anchor_resized.height - 26))
    safety = Image.open(DRAWABLE / "settings_safety_panel.webp").convert("RGBA")
    panel_width = min(width - 94, 760)
    panel_height = int(panel_width / safety.width * safety.height)
    safety = safety.resize((panel_width, panel_height), Image.Resampling.LANCZOS)
    tile.alpha_composite(safety, ((width - safety.width) // 2, height - anchor_resized.height - 8))
    draw.text((28, 12), label, fill=(238, 232, 214))
    return tile


def save_contact_sheet(asset: Image.Image) -> None:
    sheet = Image.new("RGB", (1320, 860), (16, 18, 32))
    draw = ImageDraw.Draw(sheet)
    draw.text((28, 24), "settings safety anchor: transparent imagegen dock under safety panel", fill=(240, 235, 218))
    portrait = make_mock(asset, "portrait bottom", (500, 760))
    landscape = make_mock(asset, "landscape left column", (760, 360))
    sheet.paste(portrait.convert("RGB"), (28, 70))
    sheet.paste(landscape.convert("RGB"), (548, 70))
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
