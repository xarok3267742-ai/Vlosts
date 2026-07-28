from collections import Counter
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageOps


from vslot_asset_toolchain import verify_asset_toolchain

verify_asset_toolchain()

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "qa/source/vslot_reel_anticipation_beams_imagegen.png"
DRAWABLE = ROOT / "app/src/main/res/drawable-nodpi"
PREVIEW = ROOT / "qa/screenshots/reel_anticipation_beams_contact_sheet.png"

THEMES = ("violet", "roman", "neon", "pharaoh", "ocean")
ASSET_SIZE = (280, 760)


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
            elif distance < 128:
                alpha = int(((distance - 28) / 100) * a)
            else:
                alpha = a

            if alpha and g > 112 and g > r * 1.14 and g > b * 1.2 and r < 210:
                alpha = 0
            if alpha:
                green_spill = max(0, g - max(r, b) - 16)
                g = max(max(r, b), g - int(green_spill * 0.7))
            out_pixels[x, y] = (r, g, b, alpha)

    alpha = out.getchannel("A").filter(ImageFilter.GaussianBlur(0.18))
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
    fitted = ImageOps.contain(cropped, (ASSET_SIZE[0] - 36, ASSET_SIZE[1] - 38), Image.Resampling.LANCZOS)
    canvas = Image.new("RGBA", ASSET_SIZE, (0, 0, 0, 0))
    canvas.alpha_composite(
        fitted,
        ((ASSET_SIZE[0] - fitted.width) // 2, (ASSET_SIZE[1] - fitted.height) // 2),
    )
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
    if visible < image.width * image.height * 0.08:
        raise RuntimeError(f"{name} visible coverage is too low: {visible}")
    if visible > image.width * image.height * 0.72:
        raise RuntimeError(f"{name} visible coverage is too high for a reel overlay: {visible}")


def make_reel_mock(overlay: Image.Image, theme: str) -> Image.Image:
    tile = Image.new("RGBA", (300, 820), (12, 14, 30, 255))
    draw = ImageDraw.Draw(tile)
    draw.rounded_rectangle((16, 40, 284, 780), radius=24, fill=(21, 23, 48, 255), outline=(194, 161, 90, 180), width=4)
    draw.rounded_rectangle((34, 62, 266, 758), radius=18, fill=(8, 10, 24, 255), outline=(86, 146, 220, 120), width=2)
    for row in range(3):
        y = 86 + row * 214
        draw.rounded_rectangle((56, y, 244, y + 172), radius=18, fill=(39, 34, 72, 255), outline=(148, 137, 196, 120), width=2)
        draw.ellipse((104, y + 26, 196, y + 118), fill=(65, 52, 114, 255), outline=(239, 210, 128, 150), width=3)
    tile.alpha_composite(overlay.resize((252, 684), Image.Resampling.LANCZOS), (24, 68))
    draw.text((22, 14), theme, fill=(238, 232, 214))
    return tile


def save_contact_sheet(assets: dict[str, Image.Image]) -> None:
    sheet = Image.new("RGB", (1580, 900), (16, 18, 32))
    draw = ImageDraw.Draw(sheet)
    draw.text((28, 24), "reel anticipation beams: transparent assets over reel mockups", fill=(240, 235, 218))
    x = 28
    for theme in THEMES:
        mock = make_reel_mock(assets[theme], theme)
        sheet.paste(mock.convert("RGB"), (x, 66))
        x += 308
    PREVIEW.parent.mkdir(parents=True, exist_ok=True)
    sheet.save(PREVIEW)


def main() -> None:
    source = remove_chroma_key(Image.open(SOURCE))
    width, height = source.size
    slice_width = width / len(THEMES)
    assets: dict[str, Image.Image] = {}
    for index, theme in enumerate(THEMES):
        left = int(round(index * slice_width))
        right = int(round((index + 1) * slice_width))
        panel = source.crop((left, 0, right, height))
        asset = fit_overlay(panel)
        validate(asset, f"reel_anticipation_beam_{theme}")
        save_webp(asset, DRAWABLE / f"reel_anticipation_beam_{theme}.webp")
        assets[theme] = asset
    save_contact_sheet(assets)


if __name__ == "__main__":
    main()
