from pathlib import Path

from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "qa" / "source" / "vslot_push_permission_modal_panel_premium_imagegen.png"
OUT = ROOT / "app" / "src" / "main" / "res" / "drawable-nodpi" / "push_permission_modal_panel_premium.webp"
PREVIEW = ROOT / "qa" / "screenshots" / "push_permission_modal_panel_premium_contact_sheet.png"

TARGET_SIZE = (900, 420)
LANDSCAPE_SIZE = (900, 318)


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


def mock_portrait_dialog(panel: Image.Image) -> Image.Image:
    canvas = Image.new("RGBA", TARGET_SIZE, (7, 5, 16, 255))
    canvas.alpha_composite(open_asset("modal_panel_backplate.png").resize(TARGET_SIZE, Image.Resampling.LANCZOS))
    canvas.alpha_composite(panel)
    canvas.alpha_composite(alpha_scaled(open_asset("push_prompt_panel_lattice.webp").resize(TARGET_SIZE, Image.Resampling.LANCZOS), 172))
    canvas.alpha_composite(alpha_scaled(open_asset("push_permission_signal_burst.webp").resize(TARGET_SIZE, Image.Resampling.LANCZOS), 118))
    paste_center(canvas, open_asset("modal_badge_push.webp"), (398, 28, 104, 104))
    paste_center(canvas, open_asset("title_push_notifications.webp"), (54, 142, 792, 54))
    paste_center(canvas, open_asset("body_push_prompt.webp"), (48, 206, 804, 78))
    paste_center(canvas, open_asset("btn_push_later_default.webp"), (54, 312, 384, 58))
    paste_center(canvas, open_asset("label_maybe_later.webp"), (78, 318, 336, 46))
    paste_center(canvas, open_asset("btn_push_allow_default.webp"), (462, 312, 384, 58))
    paste_center(canvas, open_asset("label_allow.webp"), (486, 318, 336, 46))
    return canvas


def mock_landscape_dialog(panel: Image.Image) -> Image.Image:
    canvas = Image.new("RGBA", LANDSCAPE_SIZE, (7, 5, 16, 255))
    canvas.alpha_composite(open_asset("modal_panel_backplate.png").resize(LANDSCAPE_SIZE, Image.Resampling.LANCZOS))
    canvas.alpha_composite(panel.resize(LANDSCAPE_SIZE, Image.Resampling.LANCZOS))
    canvas.alpha_composite(alpha_scaled(open_asset("push_prompt_panel_lattice.webp").resize(LANDSCAPE_SIZE, Image.Resampling.LANCZOS), 148))
    canvas.alpha_composite(alpha_scaled(open_asset("push_permission_signal_burst.webp").resize(LANDSCAPE_SIZE, Image.Resampling.LANCZOS), 98))
    paste_center(canvas, open_asset("modal_badge_push.webp"), (96, 36, 112, 112))
    paste_center(canvas, open_asset("title_push_notifications.webp"), (42, 160, 220, 62))
    paste_center(canvas, open_asset("body_push_prompt.webp"), (316, 66, 510, 94))
    paste_center(canvas, open_asset("btn_push_later_default.webp"), (316, 186, 238, 58))
    paste_center(canvas, open_asset("label_maybe_later.webp"), (334, 192, 202, 46))
    paste_center(canvas, open_asset("btn_push_allow_default.webp"), (572, 186, 238, 58))
    paste_center(canvas, open_asset("label_allow.webp"), (590, 192, 202, 46))
    return canvas


def labeled_card(title: str, image: Image.Image, size: tuple[int, int] = (960, 520)) -> Image.Image:
    card = Image.new("RGBA", size, (13, 10, 24, 255))
    draw = ImageDraw.Draw(card)
    draw.text((30, 22), title, fill=(242, 220, 170, 255))
    x = (size[0] - image.width) // 2
    y = 62 if image.height == TARGET_SIZE[1] else (size[1] - image.height) // 2 + 24
    card.alpha_composite(image, (x, y))
    return card


def main() -> None:
    source = Image.open(SOURCE)
    panel = decontaminate_green_fringe(resize_contain(alpha_crop(remove_green_key(source), padding=10), TARGET_SIZE))
    panel.save(OUT, "WEBP", quality=95, method=6)

    old_panel = open_asset("push_permission_modal_panel.webp").resize(TARGET_SIZE, Image.Resampling.LANCZOS)
    preview = Image.new("RGBA", (1920, 1040), (9, 7, 18, 255))
    preview.alpha_composite(labeled_card("old push prompt panel", old_panel), (0, 0))
    preview.alpha_composite(labeled_card("premium imagegen push prompt panel", panel), (960, 0))
    preview.alpha_composite(labeled_card("portrait mock with live image labels", mock_portrait_dialog(panel)), (0, 520))
    preview.alpha_composite(labeled_card("landscape mock with live image labels", mock_landscape_dialog(panel)), (960, 520))
    PREVIEW.parent.mkdir(parents=True, exist_ok=True)
    preview.convert("RGB").save(PREVIEW, quality=94)

    print(f"wrote {OUT.relative_to(ROOT)} {OUT.stat().st_size} bytes")
    print(f"wrote {PREVIEW.relative_to(ROOT)}")


if __name__ == "__main__":
    raise SystemExit(
        "NONCANONICAL_HISTORICAL_SLICER: retained for provenance only; "
        "running it would overwrite reviewed application artwork"
    )
