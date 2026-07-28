from collections import Counter
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageOps


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "qa/source/vslot_theme_reel_landing_sparks_imagegen.png"
DRAWABLE = ROOT / "app/src/main/res/drawable-nodpi"
PREVIEW = ROOT / "qa/screenshots/theme_reel_landing_sparks_contact_sheet.png"

ASSET_SIZE = (320, 1080)
THEMES = [
    ("violet", "reel_landing_spark_violet.webp"),
    ("roman", "reel_landing_spark_roman.webp"),
    ("neon", "reel_landing_spark_neon.webp"),
    ("pharaoh", "reel_landing_spark_pharaoh.webp"),
    ("ocean", "reel_landing_spark_ocean.webp"),
]
THEME_GRADES = {
    "violet": (1.22, 0.76, 1.3),
    "roman": (1.16, 1.02, 0.74),
    "neon": (0.86, 1.1, 1.28),
    "pharaoh": (1.22, 1.08, 0.62),
    "ocean": (0.74, 1.18, 1.32),
}


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
            elif distance < 138:
                alpha = int(((distance - 28) / 110) * a)
            else:
                alpha = a

            if alpha and g > 110 and g > r * 1.12 and g > b * 1.18 and r < 220:
                alpha = 0
            if alpha:
                green_spill = max(0, g - max(r, b) - 12)
                g = max(max(r, b), g - int(green_spill * 0.74))
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


def split_equal_columns(image: Image.Image) -> list[Image.Image]:
    columns = []
    for index in range(len(THEMES)):
        left = round(image.width * index / len(THEMES))
        right = round(image.width * (index + 1) / len(THEMES))
        columns.append(image.crop((left, 0, right, image.height)))
    return columns


def fit_overlay(image: Image.Image) -> Image.Image:
    cropped = trim_alpha(image)
    fitted = ImageOps.contain(cropped, (ASSET_SIZE[0] - 22, ASSET_SIZE[1] - 28), Image.Resampling.LANCZOS)
    canvas = Image.new("RGBA", ASSET_SIZE, (0, 0, 0, 0))
    canvas.alpha_composite(
        fitted,
        ((ASSET_SIZE[0] - fitted.width) // 2, (ASSET_SIZE[1] - fitted.height) // 2),
    )
    return canvas


def color_grade(theme: str, image: Image.Image) -> Image.Image:
    red_mul, green_mul, blue_mul = THEME_GRADES[theme]
    graded = image.copy()
    pixels = graded.load()
    for y in range(graded.height):
        for x in range(graded.width):
            r, g, b, a = pixels[x, y]
            if a == 0:
                continue
            luminance = (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255.0
            grade_strength = (a / 255.0) * (0.78 - luminance * 0.34)
            grade_strength = max(0.16, min(0.76, grade_strength))
            target = (
                min(255, int(r * red_mul)),
                min(255, int(g * green_mul)),
                min(255, int(b * blue_mul)),
            )
            pixels[x, y] = (
                int(r * (1 - grade_strength) + target[0] * grade_strength),
                int(g * (1 - grade_strength) + target[1] * grade_strength),
                int(b * (1 - grade_strength) + target[2] * grade_strength),
                a,
            )
    return graded


def validate(name: str, image: Image.Image) -> None:
    if image.size != ASSET_SIZE:
        raise RuntimeError(f"{name} must be {ASSET_SIZE}, got {image.size}")
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
    area = image.width * image.height
    if visible < area * 0.05:
        raise RuntimeError(f"{name} visible coverage is too low: {visible}")
    if visible > area * 0.72:
        raise RuntimeError(f"{name} visible coverage is too high: {visible}")
    if alpha.getextrema()[1] < 220:
        raise RuntimeError(f"{name} needs a strong bright impact center")


def save_webp(image: Image.Image, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    image.save(path, "WEBP", quality=95, method=6)


def make_reel_mock(overlay: Image.Image, label: str) -> Image.Image:
    tile = Image.new("RGBA", (300, 760), (12, 14, 30, 255))
    draw = ImageDraw.Draw(tile)
    draw.rounded_rectangle((18, 44, 282, 718), radius=26, fill=(24, 24, 48, 255), outline=(210, 174, 88, 185), width=3)
    draw.rounded_rectangle((36, 70, 264, 696), radius=20, fill=(7, 9, 24, 255), outline=(94, 166, 224, 130), width=2)
    for row in range(3):
        y = 94 + row * 184
        draw.rounded_rectangle((64, y, 236, y + 140), radius=16, fill=(42, 35, 78, 255), outline=(148, 137, 196, 120), width=2)
        draw.ellipse((104, y + 26, 196, y + 108), fill=(75, 58, 124, 255), outline=(239, 210, 128, 150), width=3)
    resized = overlay.resize((224, int(224 / overlay.width * overlay.height)), Image.Resampling.LANCZOS)
    tile.alpha_composite(resized, ((tile.width - resized.width) // 2, 36))
    draw.text((20, 16), label, fill=(238, 232, 214))
    return tile


def save_contact_sheet(assets: dict[str, Image.Image]) -> None:
    sheet = Image.new("RGB", (1580, 840), (16, 18, 32))
    draw = ImageDraw.Draw(sheet)
    draw.text((28, 24), "theme reel landing sparks: five imagegen stop-impact overlays", fill=(240, 235, 218))
    x = 28
    for theme, _ in THEMES:
        preview = make_reel_mock(assets[theme], theme)
        sheet.paste(preview.convert("RGB"), (x, 64))
        x += 306
    PREVIEW.parent.mkdir(parents=True, exist_ok=True)
    sheet.save(PREVIEW)


def main() -> None:
    keyed = remove_chroma_key(Image.open(SOURCE))
    assets = {}
    for (theme, file_name), column in zip(THEMES, split_equal_columns(keyed)):
        asset = color_grade(theme, fit_overlay(column))
        validate(file_name, asset)
        save_webp(asset, DRAWABLE / file_name)
        assets[theme] = asset
    save_contact_sheet(assets)


if __name__ == "__main__":
    raise SystemExit(
        "NONCANONICAL_HISTORICAL_SLICER: retained for provenance only; "
        "running it would overwrite reviewed application artwork"
    )
