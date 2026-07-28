from collections import Counter
from pathlib import Path

from PIL import Image, ImageDraw, ImageEnhance, ImageFilter, ImageOps


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "qa/source/vslot_free_spins_stake_lock_overlay_imagegen.png"
SOURCE_LAND = ROOT / "qa/source/vslot_free_spins_stake_lock_overlay_land_imagegen.png"
DRAWABLE = ROOT / "app/src/main/res/drawable-nodpi"
PREVIEW = ROOT / "qa/screenshots/free_spins_stake_lock_overlay_contact_sheet.png"

THEMES = {
    "violet": (154, 86, 255),
    "roman": (220, 58, 66),
    "neon": (32, 220, 255),
    "pharaoh": (238, 178, 54),
    "ocean": (41, 188, 214),
}

GOLD_MIN = (142, 84, 28)
WIDE_SIZE = (700, 170)
LAND_SIZE = (820, 292)


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
            elif distance < 124:
                alpha = int(((distance - 26) / 98) * a)
            else:
                alpha = a

            if alpha and g > 96 and g > r * 1.16 and g > b * 1.28 and r < 190:
                alpha = 0
            if alpha:
                green_spill = max(0, g - max(r, b) - 18)
                g = max(max(r, b), g - int(green_spill * 0.64))
            out_pixels[x, y] = (r, g, b, alpha)

    alpha = out.getchannel("A").filter(ImageFilter.GaussianBlur(0.22))
    out.putalpha(alpha)
    return out


def fit_to_size(image: Image.Image, size: tuple[int, int]) -> Image.Image:
    bbox = image.getbbox()
    if not bbox:
        raise RuntimeError("source image lost all visible pixels")
    cropped = image.crop(bbox)
    return ImageOps.fit(cropped, size, Image.Resampling.LANCZOS, centering=(0.5, 0.5))


def recolor_theme(image: Image.Image, theme_rgb: tuple[int, int, int]) -> Image.Image:
    rgba = image.convert("RGBA")
    color_boost = ImageEnhance.Color(rgba).enhance(1.12)
    pixels = color_boost.load()
    width, height = color_boost.size
    tr, tg, tb = theme_rgb
    for y in range(height):
        for x in range(width):
            r, g, b, a = pixels[x, y]
            if a == 0:
                continue
            is_gold = r >= GOLD_MIN[0] and g >= GOLD_MIN[1] and b <= 96 and r > b * 1.45
            is_white_spark = r > 218 and g > 214 and b > 196
            if is_gold or is_white_spark:
                pixels[x, y] = (r, g, b, a)
                continue
            brightness = max(r, g, b) / 255.0
            blend = 0.42 + brightness * 0.22
            pixels[x, y] = (
                int(r * (1 - blend) + tr * blend),
                int(g * (1 - blend) + tg * blend),
                int(b * (1 - blend) + tb * blend),
                a,
            )
    return color_boost


def save_webp(image: Image.Image, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    image.save(path, "WEBP", quality=95, method=6)


def make_mock_panel(size: tuple[int, int], overlay: Image.Image, stacked: bool) -> Image.Image:
    panel = Image.new("RGBA", size, (9, 10, 25, 255))
    draw = ImageDraw.Draw(panel)
    draw.rounded_rectangle((5, 5, size[0] - 5, size[1] - 5), radius=18, fill=(20, 18, 48, 255), outline=(112, 80, 210, 210), width=4)
    rows = [(18, 18, size[0] - 18, size[1] - 18)]
    if stacked:
        mid = size[1] // 2
        rows = [(18, 18, size[0] - 18, mid - 6), (18, mid + 6, size[0] - 18, size[1] - 18)]
    else:
        mid = size[0] // 2
        rows = [(18, 18, mid - 10, size[1] - 18), (mid + 10, 18, size[0] - 18, size[1] - 18)]
    for row in rows:
        draw.rounded_rectangle(row, radius=16, fill=(32, 28, 72, 255), outline=(92, 198, 255, 120), width=2)
        cx = (row[0] + row[2]) // 2
        cy = (row[1] + row[3]) // 2
        draw.rounded_rectangle((row[0] + 18, cy - 22, row[0] + 64, cy + 22), radius=12, fill=(56, 42, 92, 255), outline=(197, 160, 86, 180), width=2)
        draw.rounded_rectangle((row[2] - 64, cy - 22, row[2] - 18, cy + 22), radius=12, fill=(56, 42, 92, 255), outline=(197, 160, 86, 180), width=2)
        draw.rounded_rectangle((cx - 54, cy - 21, cx + 54, cy + 21), radius=12, fill=(12, 15, 40, 255), outline=(197, 160, 86, 200), width=2)
    panel.alpha_composite(overlay.resize(size, Image.Resampling.LANCZOS))
    return panel


def save_contact_sheet(wide: dict[str, Image.Image], land: dict[str, Image.Image]) -> None:
    sheet = Image.new("RGB", (1380, 920), (17, 18, 32))
    draw = ImageDraw.Draw(sheet)
    draw.text((26, 24), "free spins stake lock overlays: theme assets over control-panel mockups", fill=(240, 235, 218))
    y = 70
    for theme in THEMES:
        wide_mock = ImageOps.contain(make_mock_panel(WIDE_SIZE, wide[theme], stacked=False), (520, 128), Image.Resampling.LANCZOS)
        land_mock = ImageOps.contain(make_mock_panel(LAND_SIZE, land[theme], stacked=True), (520, 184), Image.Resampling.LANCZOS)
        tile = Image.new("RGB", (1328, 148), (29, 31, 50))
        tile_draw = ImageDraw.Draw(tile)
        tile_draw.text((16, 12), theme, fill=(232, 226, 210))
        tile.paste(wide_mock.convert("RGB"), (128, 12))
        tile.paste(land_mock.convert("RGB"), (740, 6))
        sheet.paste(tile, (26, y))
        y += 162
    PREVIEW.parent.mkdir(parents=True, exist_ok=True)
    sheet.save(PREVIEW)


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
    if visible < image.width * image.height * 0.2:
        raise RuntimeError(f"{name} visible coverage is too low: {visible}")


def main() -> None:
    source_wide = fit_to_size(remove_chroma_key(Image.open(SOURCE)), WIDE_SIZE)
    source_land = fit_to_size(remove_chroma_key(Image.open(SOURCE_LAND)), LAND_SIZE)
    wide_assets: dict[str, Image.Image] = {}
    land_assets: dict[str, Image.Image] = {}
    for theme, color in THEMES.items():
        wide = recolor_theme(source_wide, color)
        land = recolor_theme(source_land, color)
        validate(wide, f"free_spins_stake_lock_overlay_{theme}")
        validate(land, f"free_spins_stake_lock_overlay_{theme}_land")
        save_webp(wide, DRAWABLE / f"free_spins_stake_lock_overlay_{theme}.webp")
        save_webp(land, DRAWABLE / f"free_spins_stake_lock_overlay_{theme}_land.webp")
        wide_assets[theme] = wide
        land_assets[theme] = land
    save_contact_sheet(wide_assets, land_assets)


if __name__ == "__main__":
    raise SystemExit(
        "NONCANONICAL_HISTORICAL_SLICER: retained for provenance only; "
        "running it would overwrite reviewed application artwork"
    )
