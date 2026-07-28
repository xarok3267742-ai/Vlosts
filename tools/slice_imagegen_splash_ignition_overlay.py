from collections import Counter
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageOps


ROOT = Path(__file__).resolve().parents[1]
SOURCE_IGNITION = ROOT / "qa/source/vslot_splash_ignition_overlay_imagegen.png"
SOURCE_SCAN = ROOT / "qa/source/vslot_splash_loading_scan_imagegen.png"
DRAWABLE = ROOT / "app/src/main/res/drawable-nodpi"
OUT_IGNITION = DRAWABLE / "splash_ignition_overlay.webp"
OUT_SCAN = DRAWABLE / "splash_loading_scan.webp"
PREVIEW = ROOT / "qa/screenshots/splash_ignition_overlay_contact_sheet.png"


def remove_chroma_key(image: Image.Image) -> Image.Image:
    rgba = image.convert("RGBA")
    pixels = rgba.load()
    width, height = rgba.size
    samples = []
    for x in range(width):
        samples.append(pixels[x, 0][:3])
        samples.append(pixels[x, height - 1][:3])
    for y in range(height):
        samples.append(pixels[0, y][:3])
        samples.append(pixels[width - 1, y][:3])
    key = Counter(tuple(channel // 4 * 4 for channel in sample) for sample in samples).most_common(1)[0][0]

    out = Image.new("RGBA", rgba.size)
    out_pixels = out.load()
    for y in range(height):
        for x in range(width):
            r, g, b, a = pixels[x, y]
            distance = ((r - key[0]) ** 2 + (g - key[1]) ** 2 + (b - key[2]) ** 2) ** 0.5
            if distance < 24:
                alpha = 0
            elif distance < 128:
                alpha = int(((distance - 24) / 104) * a)
            else:
                alpha = a

            if alpha and g > 96 and g > r * 1.16 and g > b * 1.26 and r < 190:
                alpha = 0
            if alpha:
                green_spill = max(0, g - max(r, b) - 16)
                g = max(max(r, b), g - int(green_spill * 0.66))
            out_pixels[x, y] = (r, g, b, alpha)

    out.putalpha(out.getchannel("A").filter(ImageFilter.GaussianBlur(0.18)))
    return out


def fit_to_size(image: Image.Image, size: tuple[int, int]) -> Image.Image:
    bbox = image.getbbox()
    if not bbox:
        raise RuntimeError("image contains no visible pixels after key removal")
    cropped = image.crop(bbox)
    fitted = ImageOps.contain(cropped, size, Image.Resampling.LANCZOS)
    canvas = Image.new("RGBA", size, (0, 0, 0, 0))
    canvas.alpha_composite(fitted, ((size[0] - fitted.width) // 2, (size[1] - fitted.height) // 2))
    return canvas


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
    visible = sum(1 for value in alpha.getdata() if value > 8)
    if visible < image.width * image.height * 0.1:
        raise RuntimeError(f"{name} visible coverage is too low: {visible}")


def save_webp(image: Image.Image, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    image.save(path, "WEBP", quality=95, method=6)


def mock_splash(ignition: Image.Image, scan: Image.Image) -> Image.Image:
    bg = Image.new("RGBA", (720, 960), (9, 9, 28, 255))
    draw = ImageDraw.Draw(bg)
    for radius, alpha in [(520, 40), (380, 52), (260, 70)]:
        draw.ellipse(
            (360 - radius // 2, 300 - radius // 2, 360 + radius // 2, 300 + radius // 2),
            outline=(128, 82, 255, alpha),
            width=10,
        )
    aura = ignition.resize((430, 430), Image.Resampling.LANCZOS)
    bg.alpha_composite(aura, (145, 72))
    draw.rounded_rectangle((242, 176, 478, 412), radius=52, fill=(52, 28, 120, 238), outline=(255, 211, 86, 230), width=7)
    draw.text((360, 294), "V", anchor="mm", fill=(245, 232, 255), stroke_width=2, stroke_fill=(44, 14, 96))
    rail = Image.new("RGBA", (300, 58), (0, 0, 0, 0))
    rail_draw = ImageDraw.Draw(rail)
    rail_draw.rounded_rectangle((6, 8, 294, 50), radius=20, fill=(27, 18, 74, 232), outline=(119, 220, 255, 210), width=3)
    rail.alpha_composite(scan.resize((300, 58), Image.Resampling.LANCZOS))
    bg.alpha_composite(rail, (210, 742))
    return bg


def save_contact_sheet(ignition: Image.Image, scan: Image.Image) -> None:
    sheet = Image.new("RGB", (1200, 760), (18, 19, 34))
    draw = ImageDraw.Draw(sheet)
    draw.text((28, 24), "splash ignition overlay: transparent assets and launch mockup", fill=(239, 234, 216))

    ignition_preview = ImageOps.contain(ignition, (350, 350), Image.Resampling.LANCZOS)
    scan_preview = ImageOps.contain(scan, (430, 92), Image.Resampling.LANCZOS)
    mock = ImageOps.contain(mock_splash(ignition, scan), (360, 480), Image.Resampling.LANCZOS)
    panels = [
        (28, 72, ignition_preview, "ignition transparent"),
        (460, 174, scan_preview, "loading scan transparent"),
        (812, 86, mock, "splash mockup"),
    ]
    for x, y, image, label in panels:
        tile = Image.new("RGB", (image.width + 28, image.height + 50), (30, 32, 50))
        tile_draw = ImageDraw.Draw(tile)
        tile_draw.text((14, 12), label, fill=(224, 219, 202))
        tile.paste(image.convert("RGB"), (14, 36))
        sheet.paste(tile, (x, y))
    PREVIEW.parent.mkdir(parents=True, exist_ok=True)
    sheet.save(PREVIEW)


def main() -> None:
    ignition = fit_to_size(remove_chroma_key(Image.open(SOURCE_IGNITION)), (900, 900))
    scan = fit_to_size(remove_chroma_key(Image.open(SOURCE_SCAN)), (700, 140))
    validate(ignition, "splash_ignition_overlay")
    validate(scan, "splash_loading_scan")
    save_webp(ignition, OUT_IGNITION)
    save_webp(scan, OUT_SCAN)
    save_contact_sheet(ignition, scan)


if __name__ == "__main__":
    raise SystemExit(
        "NONCANONICAL_HISTORICAL_SLICER: retained for provenance only; "
        "running it would overwrite reviewed application artwork"
    )
