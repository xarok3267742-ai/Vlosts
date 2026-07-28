from collections import Counter
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "qa/source/vslot_theme_slam_stop_cues_imagegen.png"
DRAWABLE = ROOT / "app/src/main/res/drawable-nodpi"
PREVIEW = ROOT / "qa/screenshots/theme_slam_stop_cues_contact_sheet.png"

TARGET_SIZE = (512, 512)
THEMES = [
    ("violet", "slam_stop_cue_violet.webp"),
    ("roman", "slam_stop_cue_roman.webp"),
    ("neon", "slam_stop_cue_neon.webp"),
    ("pharaoh", "slam_stop_cue_pharaoh.webp"),
    ("ocean", "slam_stop_cue_ocean.webp"),
]
THEME_GRADES = {
    "violet": (1.28, 0.62, 1.38),
    "roman": (1.14, 1.02, 0.72),
    "neon": (0.86, 1.08, 1.28),
    "pharaoh": (1.18, 1.08, 0.62),
    "ocean": (0.74, 1.16, 1.32),
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

            if alpha and g > 112 and g > r * 1.12 and g > b * 1.18 and r < 220:
                alpha = 0
            if alpha:
                green_spill = max(0, g - max(r, b) - 12)
                g = max(max(r, b), g - int(green_spill * 0.74))
            out_pixels[x, y] = (r, g, b, alpha)

    alpha = out.getchannel("A").filter(ImageFilter.GaussianBlur(0.12))
    out.putalpha(alpha)
    return out


def split_equal_columns(image: Image.Image) -> list[Image.Image]:
    columns = []
    for index in range(len(THEMES)):
        left = round(image.width * index / len(THEMES))
        right = round(image.width * (index + 1) / len(THEMES))
        columns.append(image.crop((left, 0, right, image.height)))
    return columns


def alpha_crop(image: Image.Image, padding: int = 18) -> Image.Image:
    bbox = image.getchannel("A").getbbox()
    if bbox is None:
        raise RuntimeError("all visible pixels were removed")
    left, top, right, bottom = bbox
    return image.crop(
        (
            max(0, left - padding),
            max(0, top - padding),
            min(image.width, right + padding),
            min(image.height, bottom + padding),
        )
    )


def fit_square(image: Image.Image) -> Image.Image:
    cropped = alpha_crop(image)
    cropped.thumbnail((TARGET_SIZE[0] - 26, TARGET_SIZE[1] - 26), Image.Resampling.LANCZOS)
    canvas = Image.new("RGBA", TARGET_SIZE, (0, 0, 0, 0))
    canvas.alpha_composite(cropped, ((TARGET_SIZE[0] - cropped.width) // 2, (TARGET_SIZE[1] - cropped.height) // 2))
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
            grade_strength = (a / 255.0) * (0.72 - luminance * 0.28)
            grade_strength = max(0.14, min(0.68, grade_strength))
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
    if image.size != TARGET_SIZE:
        raise RuntimeError(f"{name} must be {TARGET_SIZE}, got {image.size}")
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
    if visible < area * 0.2:
        raise RuntimeError(f"{name} visible coverage is too low: {visible}")
    if visible > area * 0.76:
        raise RuntimeError(f"{name} visible coverage is too high: {visible}")
    if alpha.getextrema()[1] < 230:
        raise RuntimeError(f"{name} needs a solid opaque control center")


def save_webp(image: Image.Image, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    image.save(path, "WEBP", quality=95, method=6)


def open_asset(name: str) -> Image.Image:
    for folder in ("drawable-nodpi", "drawable"):
        root = ROOT / "app/src/main/res" / folder
        path = root / name
        if path.exists():
            return Image.open(path).convert("RGBA")
        for candidate in root.glob(f"{Path(name).stem}.*"):
            return Image.open(candidate).convert("RGBA")
    raise FileNotFoundError(name)


def theme_asset(theme: str, base: str, extension: str = "webp") -> str:
    if theme == "violet":
        return f"{base}_violet.{extension}"
    return f"{base}_{theme}.{extension}"


def mock_spin_deck(theme: str, cue: Image.Image) -> Image.Image:
    canvas = Image.new("RGBA", (520, 250), (7, 5, 16, 255))
    deck_name = theme_asset(theme, "slot_spin_deck_glow")
    button_name = theme_asset(theme, "spin_button", "webp")
    if button_name == "spin_button_violet.webp":
        button_name = "spin_button_violet_default.webp"
    else:
        button_name = button_name.replace(".webp", "_default.webp")
    deck = open_asset(deck_name).resize((520, 205), Image.Resampling.LANCZOS)
    spin = open_asset(button_name).resize((226, 86), Image.Resampling.LANCZOS)
    canvas.alpha_composite(deck, (0, 22))
    canvas.alpha_composite(spin, (147, 86))
    sized = cue.resize((104, 104), Image.Resampling.LANCZOS)
    canvas.alpha_composite(sized, (260 - sized.width // 2, 129 - sized.height // 2))
    return canvas


def make_card(theme: str, cue: Image.Image) -> Image.Image:
    card = Image.new("RGBA", (300, 360), (12, 9, 22, 255))
    draw = ImageDraw.Draw(card)
    draw.text((18, 14), theme, fill=(242, 220, 170, 255))
    thumb = cue.copy()
    thumb.thumbnail((224, 224), Image.Resampling.LANCZOS)
    card.alpha_composite(thumb, ((card.width - thumb.width) // 2, 42))
    return card


def save_contact_sheet(assets: dict[str, Image.Image]) -> None:
    sheet = Image.new("RGB", (1580, 750), (9, 7, 18))
    draw = ImageDraw.Draw(sheet)
    draw.text((28, 22), "theme slam-stop cues: transparent imagegen mechanical rings over spin deck mockups", fill=(240, 235, 218))
    x = 28
    for theme, _ in THEMES:
        sheet.paste(make_card(theme, assets[theme]).convert("RGB"), (x, 58))
        sheet.paste(mock_spin_deck(theme, assets[theme]).convert("RGB"), (x - 4, 430))
        x += 306
    PREVIEW.parent.mkdir(parents=True, exist_ok=True)
    sheet.save(PREVIEW, quality=94)


def main() -> None:
    keyed = remove_chroma_key(Image.open(SOURCE))
    assets = {}
    for (theme, file_name), column in zip(THEMES, split_equal_columns(keyed)):
        asset = color_grade(theme, fit_square(column))
        validate(file_name, asset)
        save_webp(asset, DRAWABLE / file_name)
        assets[theme] = asset
    save_contact_sheet(assets)


if __name__ == "__main__":
    raise SystemExit(
        "NONCANONICAL_HISTORICAL_SLICER: retained for provenance only; "
        "running it would overwrite reviewed application artwork"
    )
