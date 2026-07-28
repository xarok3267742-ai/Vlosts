from pathlib import Path

from PIL import Image, ImageDraw


from vslot_asset_toolchain import verify_asset_toolchain

verify_asset_toolchain()

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "qa" / "source" / "vslot_low_coins_modal_panel_premium_imagegen.png"
OUT = ROOT / "app" / "src" / "main" / "res" / "drawable-nodpi" / "low_coins_modal_panel_premium.webp"
PREVIEW = ROOT / "qa" / "screenshots" / "low_coins_modal_panel_premium_contact_sheet.png"

TARGET_SIZE = (900, 420)


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
            elif g >= 150 and green_dominance >= 45:
                alpha = max(0, min(255, int(a * (green_distance - 45) / 160)))
            else:
                alpha = a
            if alpha == 0:
                pixels[x, y] = (0, 0, 0, 0)
            elif alpha != a:
                pixels[x, y] = (r, max(0, g - 30), b, alpha)
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


def mock_dialog(panel: Image.Image, waiting: bool) -> Image.Image:
    canvas = Image.new("RGBA", TARGET_SIZE, (7, 5, 16, 255))
    canvas.alpha_composite(open_asset("modal_panel_backplate.png").resize(TARGET_SIZE, Image.Resampling.LANCZOS))
    canvas.alpha_composite(panel)
    canvas.alpha_composite(open_asset("low_coins_caution_stage.webp").resize(TARGET_SIZE, Image.Resampling.LANCZOS))
    glow = open_asset("low_coins_rescue_glow.webp").resize(TARGET_SIZE, Image.Resampling.LANCZOS)
    canvas.alpha_composite(alpha_scaled(glow, 140 if not waiting else 96))
    paste_center(canvas, open_asset("low_coins_badge.webp"), (384, 34, 132, 132))
    paste_center(canvas, open_asset("title_low_coins.webp"), (96, 174, 708, 50))
    paste_center(
        canvas,
        open_asset("label_low_coins_wait_body.webp" if waiting else "label_low_coins_bonus_body.webp"),
        (90, 230, 720, 82),
    )
    if waiting:
        paste_center(canvas, open_asset("low_coins_cooldown_rail.webp"), (338, 316, 222, 46))
    paste_center(canvas, open_asset("btn_bonus_claim_default.webp"), (360, 360, 180, 52))
    paste_center(canvas, open_asset("label_ok_action.webp" if waiting else "label_claim_bonus.webp"), (378, 364, 144, 44))
    return canvas


def labeled_card(title: str, image: Image.Image) -> Image.Image:
    card = Image.new("RGBA", (960, 520), (13, 10, 24, 255))
    draw = ImageDraw.Draw(card)
    draw.text((30, 22), title, fill=(242, 220, 170, 255))
    card.alpha_composite(image, (30, 62))
    return card


def main() -> None:
    source = Image.open(SOURCE)
    panel = resize_contain(alpha_crop(remove_green_key(source), padding=8), TARGET_SIZE)
    panel.save(OUT, quality=96, method=6)

    old_panel = open_asset("low_coins_modal_panel.webp").resize(TARGET_SIZE, Image.Resampling.LANCZOS)
    preview = Image.new("RGBA", (1920, 1040), (9, 7, 18, 255))
    preview.alpha_composite(labeled_card("old low coins panel", old_panel), (0, 0))
    preview.alpha_composite(labeled_card("premium low coins panel", panel), (960, 0))
    preview.alpha_composite(labeled_card("claim bonus mock", mock_dialog(panel, waiting=False)), (0, 520))
    preview.alpha_composite(labeled_card("cooldown wait mock", mock_dialog(panel, waiting=True)), (960, 520))
    PREVIEW.parent.mkdir(parents=True, exist_ok=True)
    preview.convert("RGB").save(PREVIEW, quality=94)

    print(f"wrote {OUT.relative_to(ROOT)} {OUT.stat().st_size} bytes")
    print(f"wrote {PREVIEW.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
