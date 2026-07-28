from pathlib import Path

from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "qa" / "source" / "vslot_top_bar_premium_imagegen.png"
OUT = ROOT / "app" / "src" / "main" / "res" / "drawable-nodpi" / "top_bar_premium.webp"
PREVIEW = ROOT / "qa" / "screenshots" / "top_bar_premium_contact_sheet.png"

TARGET_SIZE = (1200, 220)


def remove_green_key(image: Image.Image) -> Image.Image:
    rgba = image.convert("RGBA")
    pixels = rgba.load()
    width, height = rgba.size
    for y in range(height):
        for x in range(width):
            r, g, b, a = pixels[x, y]
            green_distance = abs(r - 0) + abs(g - 255) + abs(b - 0)
            green_dominance = g - max(r, b)
            if green_distance <= 95 or (g >= 190 and green_dominance >= 80):
                alpha = 0
            elif g >= 145 and green_dominance >= 42:
                alpha = max(0, min(255, int(a * (green_distance - 42) / 165)))
            else:
                alpha = a
            if alpha == 0:
                pixels[x, y] = (0, 0, 0, 0)
            elif alpha != a:
                pixels[x, y] = (r, max(0, g - 35), b, alpha)
    return rgba


def alpha_crop(image: Image.Image, padding: int = 0) -> Image.Image:
    bbox = image.getchannel("A").getbbox()
    if bbox is None:
        return image
    left, top, right, bottom = bbox
    return image.crop(
        (
            max(0, left - padding),
            max(0, top - padding),
            min(image.width, right + padding),
            min(image.height, bottom + padding),
        )
    )


def crop_to_aspect(image: Image.Image, aspect: float) -> Image.Image:
    width, height = image.size
    current = width / height
    if current > aspect:
        new_width = int(height * aspect)
        left = (width - new_width) // 2
        return image.crop((left, 0, left + new_width, height))
    new_height = int(width / aspect)
    top = (height - new_height) // 2
    return image.crop((0, top, width, top + new_height))


def decontaminate_green_fringe(image: Image.Image) -> Image.Image:
    rgba = image.copy()
    pixels = rgba.load()
    width, height = rgba.size
    for y in range(height):
        for x in range(width):
            r, g, b, a = pixels[x, y]
            if a == 0:
                continue
            dominant = g - max(r, b)
            if dominant <= 8:
                continue
            if g >= 115 or a < 235:
                clean_g = max(r, b) + min(8, dominant // 4)
                clean_alpha = a
                if dominant >= 34 and a < 230:
                    clean_alpha = max(0, int(a * 0.68))
                pixels[x, y] = (r, clean_g, b, clean_alpha)
    return rgba


def open_asset(name: str) -> Image.Image:
    for folder in ("drawable-nodpi", "drawable"):
        root = ROOT / "app" / "src" / "main" / "res" / folder
        path = root / name
        if path.exists():
            return Image.open(path).convert("RGBA")
        for candidate in root.glob(f"{Path(name).stem}.*"):
            return Image.open(candidate).convert("RGBA")
    raise FileNotFoundError(name)


def paste_center(canvas: Image.Image, asset: Image.Image, box: tuple[int, int, int, int]) -> None:
    x, y, width, height = box
    fitted = asset.copy()
    fitted.thumbnail((width, height), Image.Resampling.LANCZOS)
    canvas.alpha_composite(fitted, (x + (width - fitted.width) // 2, y + (height - fitted.height) // 2))


def bar_on_surface(bar: Image.Image, height: int, width: int = 1200) -> Image.Image:
    canvas = Image.new("RGBA", (width, height), (8, 6, 18, 255))
    scaled = bar.resize((width, height), Image.Resampling.LANCZOS)
    canvas.alpha_composite(scaled)
    return canvas


def mock_home_header(bar: Image.Image) -> Image.Image:
    canvas = bar_on_surface(bar, 220)
    paste_center(canvas, open_asset("logo_v_slot.webp"), (36, 58, 300, 98))
    paste_center(canvas, open_asset("btn_settings_default.webp"), (1050, 48, 116, 116))
    return canvas


def mock_title_header(bar: Image.Image, title: str) -> Image.Image:
    canvas = bar_on_surface(bar, 220)
    paste_center(canvas, open_asset("btn_back_default.webp"), (34, 48, 116, 116))
    paste_center(canvas, open_asset(title), (218, 52, 764, 100))
    return canvas


def mock_balance_strip(bar: Image.Image) -> Image.Image:
    canvas = bar_on_surface(bar, 150, width=620)
    paste_center(canvas, open_asset("coin_icon.webp"), (32, 40, 70, 70))
    draw = ImageDraw.Draw(canvas)
    draw.rounded_rectangle((122, 48, 520, 102), radius=18, fill=(18, 24, 54, 170), outline=(63, 222, 255, 150), width=2)
    return canvas


def labeled_card(title: str, image: Image.Image, size: tuple[int, int]) -> Image.Image:
    card = Image.new("RGBA", size, (13, 10, 24, 255))
    draw = ImageDraw.Draw(card)
    draw.text((28, 20), title, fill=(242, 220, 170, 255))
    fitted = image.copy()
    fitted.thumbnail((size[0] - 56, size[1] - 72), Image.Resampling.LANCZOS)
    card.alpha_composite(fitted, ((size[0] - fitted.width) // 2, 58))
    return card


def main() -> None:
    source = Image.open(SOURCE)
    cropped = alpha_crop(remove_green_key(source), padding=10)
    panel = crop_to_aspect(cropped, TARGET_SIZE[0] / TARGET_SIZE[1]).resize(TARGET_SIZE, Image.Resampling.LANCZOS)
    panel = decontaminate_green_fringe(panel)
    panel.save(OUT, "WEBP", quality=95, method=6)

    old = open_asset("top_bar.webp").resize(TARGET_SIZE, Image.Resampling.LANCZOS)
    preview = Image.new("RGBA", (1920, 1120), (9, 7, 18, 255))
    preview.alpha_composite(labeled_card("old shared top bar", old, (960, 280)), (0, 0))
    preview.alpha_composite(labeled_card("premium imagegen shared top bar", panel, (960, 280)), (960, 0))
    preview.alpha_composite(labeled_card("home header mock", mock_home_header(panel), (960, 280)), (0, 280))
    preview.alpha_composite(labeled_card("settings/privacy header mock", mock_title_header(panel, "title_settings.webp"), (960, 280)), (960, 280))
    preview.alpha_composite(labeled_card("privacy header mock", mock_title_header(panel, "title_privacy_policy.webp"), (960, 280)), (0, 560))
    preview.alpha_composite(labeled_card("compact balance strip mock", mock_balance_strip(panel), (960, 280)), (960, 560))
    PREVIEW.parent.mkdir(parents=True, exist_ok=True)
    preview.convert("RGB").save(PREVIEW, quality=94)

    print(f"wrote {OUT.relative_to(ROOT)} {OUT.stat().st_size} bytes")
    print(f"wrote {PREVIEW.relative_to(ROOT)}")


if __name__ == "__main__":
    raise SystemExit(
        "NONCANONICAL_HISTORICAL_SLICER: retained for provenance only; "
        "running it would overwrite reviewed application artwork"
    )
