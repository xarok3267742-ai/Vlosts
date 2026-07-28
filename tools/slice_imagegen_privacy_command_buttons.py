from pathlib import Path

from PIL import Image, ImageDraw


from vslot_asset_toolchain import verify_asset_toolchain

verify_asset_toolchain()

ROOT = Path(__file__).resolve().parents[1]
SOURCE_DEFAULT = ROOT / "qa" / "source" / "vslot_btn_privacy_default_premium_imagegen.png"
SOURCE_PRESSED = ROOT / "qa" / "source" / "vslot_btn_privacy_pressed_premium_imagegen.png"
OUT_DEFAULT = ROOT / "app" / "src" / "main" / "res" / "drawable-nodpi" / "btn_privacy_default_premium.webp"
OUT_PRESSED = ROOT / "app" / "src" / "main" / "res" / "drawable-nodpi" / "btn_privacy_pressed_premium.webp"
PREVIEW = ROOT / "qa" / "screenshots" / "privacy_command_buttons_premium_contact_sheet.png"

TARGET_SIZE = (700, 150)


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


def process(source: Path) -> Image.Image:
    image = Image.open(source)
    cropped = crop_to_aspect(alpha_crop(remove_green_key(image), padding=10), TARGET_SIZE[0] / TARGET_SIZE[1])
    return decontaminate_green_fringe(cropped.resize(TARGET_SIZE, Image.Resampling.LANCZOS))


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


def mock_button(button: Image.Image, label: str, scale: float = 1.0) -> Image.Image:
    width = int(TARGET_SIZE[0] * scale)
    height = int(TARGET_SIZE[1] * scale)
    canvas = Image.new("RGBA", (width, height), (7, 5, 16, 255))
    canvas.alpha_composite(button.resize((width, height), Image.Resampling.LANCZOS))
    paste_center(canvas, open_asset(label), (int(width * 0.13), int(height * 0.18), int(width * 0.74), int(height * 0.64)))
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
    default = process(SOURCE_DEFAULT)
    pressed = process(SOURCE_PRESSED)
    default.save(OUT_DEFAULT, lossless=True, method=6)
    pressed.save(OUT_PRESSED, lossless=True, method=6)

    old_default = open_asset("btn_privacy_default.webp").resize(TARGET_SIZE, Image.Resampling.LANCZOS)
    old_pressed = open_asset("btn_privacy_pressed.webp").resize(TARGET_SIZE, Image.Resampling.LANCZOS)
    preview = Image.new("RGBA", (1920, 1120), (9, 7, 18, 255))
    preview.alpha_composite(labeled_card("old default button", old_default, (960, 280)), (0, 0))
    preview.alpha_composite(labeled_card("premium default button", default, (960, 280)), (960, 0))
    preview.alpha_composite(labeled_card("old pressed button", old_pressed, (960, 280)), (0, 280))
    preview.alpha_composite(labeled_card("premium pressed button", pressed, (960, 280)), (960, 280))
    preview.alpha_composite(labeled_card("privacy label mock", mock_button(default, "label_privacy_policy.webp"), (960, 280)), (0, 560))
    preview.alpha_composite(labeled_card("rules label mock", mock_button(default, "label_social_rules.webp"), (960, 280)), (960, 560))
    preview.alpha_composite(labeled_card("push unavailable label mock", mock_button(default, "label_push_unconfigured_action.webp"), (960, 280)), (0, 840))
    preview.alpha_composite(labeled_card("compact home strip mock", mock_button(default, "label_privacy_policy.webp", scale=0.74), (960, 280)), (960, 840))
    PREVIEW.parent.mkdir(parents=True, exist_ok=True)
    preview.convert("RGB").save(PREVIEW, quality=94)

    print(f"wrote {OUT_DEFAULT.relative_to(ROOT)} {OUT_DEFAULT.stat().st_size} bytes")
    print(f"wrote {OUT_PRESSED.relative_to(ROOT)} {OUT_PRESSED.stat().st_size} bytes")
    print(f"wrote {PREVIEW.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
