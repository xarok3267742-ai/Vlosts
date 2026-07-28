from pathlib import Path

from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "qa" / "source" / "vslot_privacy_web_panel_premium_imagegen.png"
SOURCE_LANDSCAPE = ROOT / "qa" / "source" / "vslot_privacy_web_panel_landscape_premium_imagegen.png"
OUT = ROOT / "app" / "src" / "main" / "res" / "drawable-nodpi" / "privacy_web_panel_premium.webp"
OUT_LANDSCAPE = ROOT / "app" / "src" / "main" / "res" / "drawable-nodpi" / "privacy_web_panel_landscape_premium.webp"
PREVIEW = ROOT / "qa" / "screenshots" / "privacy_web_panel_premium_contact_sheet.png"

TARGET_SIZE = (900, 1450)
LANDSCAPE_SIZE = (1500, 620)


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


def resize_contain(image: Image.Image, size: tuple[int, int]) -> Image.Image:
    canvas = Image.new("RGBA", size, (0, 0, 0, 0))
    fitted = image.copy()
    fitted.thumbnail(size, Image.Resampling.LANCZOS)
    canvas.alpha_composite(fitted, ((size[0] - fitted.width) // 2, (size[1] - fitted.height) // 2))
    return canvas


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


def alpha_scaled(image: Image.Image, alpha: int) -> Image.Image:
    copy = image.copy()
    copy.putalpha(copy.getchannel("A").point(lambda value: min(alpha, value * alpha // 255)))
    return copy


def draw_webview_fill(canvas: Image.Image, box: tuple[int, int, int, int]) -> None:
    draw = ImageDraw.Draw(canvas, "RGBA")
    x, y, width, height = box
    draw.rounded_rectangle((x, y, x + width, y + height), radius=18, fill=(9, 12, 27, 232))


def mock_portrait_content(panel: Image.Image) -> Image.Image:
    canvas = Image.new("RGBA", TARGET_SIZE, (7, 5, 16, 255))
    canvas.alpha_composite(panel)
    draw_webview_fill(canvas, (108, 120, 684, 1210))
    canvas.alpha_composite(alpha_scaled(open_asset("privacy_guard_document_glow.webp").resize(TARGET_SIZE, Image.Resampling.LANCZOS), 88))
    return canvas


def mock_portrait_error(panel: Image.Image) -> Image.Image:
    canvas = Image.new("RGBA", TARGET_SIZE, (7, 5, 16, 255))
    canvas.alpha_composite(panel)
    canvas.alpha_composite(alpha_scaled(open_asset("privacy_guard_document_glow.webp").resize(TARGET_SIZE, Image.Resampling.LANCZOS), 132))
    paste_center(canvas, open_asset("privacy_guard_badge.webp"), (330, 390, 240, 240))
    paste_center(canvas, open_asset("label_privacy_error_load.webp"), (90, 668, 720, 120))
    paste_center(canvas, open_asset("btn_privacy_retry_default.webp"), (315, 838, 270, 78))
    paste_center(canvas, open_asset("label_retry.webp"), (344, 848, 212, 58))
    return canvas


def mock_landscape_error(panel: Image.Image) -> Image.Image:
    canvas = Image.new("RGBA", LANDSCAPE_SIZE, (7, 5, 16, 255))
    canvas.alpha_composite(panel)
    canvas.alpha_composite(alpha_scaled(open_asset("privacy_guard_document_glow.webp").resize(LANDSCAPE_SIZE, Image.Resampling.LANCZOS), 112))
    paste_center(canvas, open_asset("privacy_guard_badge.webp"), (260, 190, 170, 170))
    paste_center(canvas, open_asset("label_privacy_error_load.webp"), (520, 202, 680, 96))
    paste_center(canvas, open_asset("btn_privacy_retry_default.webp"), (760, 336, 270, 78))
    paste_center(canvas, open_asset("label_retry.webp"), (790, 346, 210, 58))
    return canvas


def labeled_card(title: str, image: Image.Image, size: tuple[int, int]) -> Image.Image:
    card = Image.new("RGBA", size, (13, 10, 24, 255))
    draw = ImageDraw.Draw(card)
    draw.text((28, 20), title, fill=(242, 220, 170, 255))
    fitted = image.copy()
    fitted.thumbnail((size[0] - 56, size[1] - 74), Image.Resampling.LANCZOS)
    card.alpha_composite(fitted, ((size[0] - fitted.width) // 2, 58))
    return card


def process(source: Path, size: tuple[int, int]) -> Image.Image:
    image = Image.open(source)
    return decontaminate_green_fringe(resize_contain(alpha_crop(remove_green_key(image), padding=12), size))


def main() -> None:
    panel = process(SOURCE, TARGET_SIZE)
    landscape_panel = process(SOURCE_LANDSCAPE, LANDSCAPE_SIZE)
    panel.save(OUT, "WEBP", quality=95, method=6)
    landscape_panel.save(OUT_LANDSCAPE, "WEBP", quality=95, method=6)

    old_panel = open_asset("privacy_web_panel.webp").resize(TARGET_SIZE, Image.Resampling.LANCZOS)
    preview = Image.new("RGBA", (2200, 2200), (9, 7, 18, 255))
    preview.alpha_composite(labeled_card("old privacy web panel", old_panel, (700, 1080)), (0, 0))
    preview.alpha_composite(labeled_card("premium privacy web panel", panel, (700, 1080)), (700, 0))
    preview.alpha_composite(labeled_card("portrait WebView content mock", mock_portrait_content(panel), (800, 1080)), (1400, 0))
    preview.alpha_composite(labeled_card("portrait error mock with live image labels", mock_portrait_error(panel), (800, 1080)), (0, 1120))
    preview.alpha_composite(labeled_card("landscape error mock with live image labels", mock_landscape_error(landscape_panel), (1400, 720)), (800, 1120))
    PREVIEW.parent.mkdir(parents=True, exist_ok=True)
    preview.convert("RGB").save(PREVIEW, quality=94)

    print(f"wrote {OUT.relative_to(ROOT)} {OUT.stat().st_size} bytes")
    print(f"wrote {OUT_LANDSCAPE.relative_to(ROOT)} {OUT_LANDSCAPE.stat().st_size} bytes")
    print(f"wrote {PREVIEW.relative_to(ROOT)}")


if __name__ == "__main__":
    raise SystemExit(
        "NONCANONICAL_HISTORICAL_SLICER: retained for provenance only; "
        "running it would overwrite reviewed application artwork"
    )
