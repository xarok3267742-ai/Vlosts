from pathlib import Path

from PIL import Image, ImageDraw


from vslot_asset_toolchain import verify_asset_toolchain

verify_asset_toolchain()

ROOT = Path(__file__).resolve().parents[1]
SOURCE_DIR = ROOT / "qa" / "source"
DRAWABLE = ROOT / "app" / "src" / "main" / "res" / "drawable-nodpi"
PREVIEW = ROOT / "qa" / "screenshots" / "result_modal_panels_premium_contact_sheet.png"

TARGET_SIZE = (900, 420)

THEMES = {
    "violet": {
        "source": "vslot_result_panel_violet_premium_imagegen.png",
        "output": "result_modal_panel_violet_premium.webp",
        "old": "result_modal_panel.webp",
        "stage": "result_stage_lattice.webp",
        "reward": "result_win_payout_burst.webp",
    },
    "roman": {
        "source": "vslot_result_panel_roman_premium_imagegen.png",
        "output": "result_modal_panel_roman_premium.webp",
        "old": "result_modal_panel_roman.webp",
        "stage": "result_stage_lattice_roman.webp",
        "reward": "result_win_payout_burst_roman.webp",
    },
    "neon": {
        "source": "vslot_result_panel_neon_premium_imagegen.png",
        "output": "result_modal_panel_neon_premium.webp",
        "old": "result_modal_panel_neon.webp",
        "stage": "result_stage_lattice_neon.webp",
        "reward": "result_win_payout_burst_neon.webp",
    },
    "pharaoh": {
        "source": "vslot_result_panel_pharaoh_premium_imagegen.png",
        "output": "result_modal_panel_pharaoh_premium.webp",
        "old": "result_modal_panel_pharaoh.webp",
        "stage": "result_stage_lattice_pharaoh.webp",
        "reward": "result_win_payout_burst_pharaoh.webp",
    },
    "ocean": {
        "source": "vslot_result_panel_ocean_premium_imagegen.png",
        "output": "result_modal_panel_ocean_premium.webp",
        "old": "result_modal_panel_ocean.webp",
        "stage": "result_stage_lattice_ocean.webp",
        "reward": "result_win_payout_burst_ocean.webp",
    },
}


def remove_green_key(image: Image.Image) -> Image.Image:
    rgba = image.convert("RGBA")
    pixels = rgba.load()
    width, height = rgba.size
    for y in range(height):
        for x in range(width):
            r, g, b, alpha = pixels[x, y]
            green_distance = abs(r) + abs(g - 255) + abs(b)
            green_dominance = g - max(r, b)
            if green_distance <= 105 or (g >= 190 and green_dominance >= 74):
                pixels[x, y] = (0, 0, 0, 0)
            elif g >= 145 and green_dominance >= 42:
                edge_alpha = max(0, min(255, int(alpha * (green_distance - 38) / 180)))
                pixels[x, y] = (r, max(0, g - 38), b, edge_alpha)
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


def result_mock(panel: Image.Image, theme: dict[str, str]) -> Image.Image:
    canvas = Image.new("RGBA", TARGET_SIZE, (8, 6, 18, 255))
    canvas.alpha_composite(open_asset("modal_panel_backplate.png").resize(TARGET_SIZE, Image.Resampling.LANCZOS))
    canvas.alpha_composite(panel)
    canvas.alpha_composite(open_asset(theme["stage"]).resize(TARGET_SIZE, Image.Resampling.LANCZOS))
    reward = open_asset(theme["reward"]).resize(TARGET_SIZE, Image.Resampling.LANCZOS)
    canvas.alpha_composite(alpha_scaled(reward, 78))
    paste_center(canvas, open_asset("modal_badge_win.webp"), (394, 44, 112, 112))
    paste_center(canvas, open_asset("title_win.webp"), (130, 164, 640, 48))
    paste_center(canvas, open_asset("coin_icon.webp"), (334, 220, 44, 44))
    paste_center(canvas, open_asset("label_result_win_body.webp"), (104, 288, 692, 74))
    paste_center(canvas, open_asset("btn_modal_close_default.webp"), (360, 374, 180, 52))
    paste_center(canvas, open_asset("label_close.webp"), (378, 378, 144, 44))
    return canvas


def labeled_card(title: str, image: Image.Image, card_size: tuple[int, int] = (960, 500)) -> Image.Image:
    card = Image.new("RGBA", card_size, (12, 10, 22, 255))
    draw = ImageDraw.Draw(card)
    draw.text((26, 18), title, fill=(242, 220, 170, 255))
    card.alpha_composite(image, (30, 58))
    return card


def main() -> None:
    panels: dict[str, Image.Image] = {}
    for name, theme in THEMES.items():
        source = Image.open(SOURCE_DIR / theme["source"])
        panel = resize_contain(alpha_crop(remove_green_key(source), padding=8), TARGET_SIZE)
        out = DRAWABLE / theme["output"]
        panel.save(out, quality=96, method=6)
        panels[name] = panel
        print(f"wrote {out.relative_to(ROOT)} {out.stat().st_size} bytes")

    preview = Image.new("RGBA", (2880, 2500), (9, 7, 18, 255))
    for row, (name, theme) in enumerate(THEMES.items()):
        y = row * 500
        old_panel = open_asset(theme["old"]).resize(TARGET_SIZE, Image.Resampling.LANCZOS)
        preview.alpha_composite(labeled_card(f"{name} old", old_panel), (0, y))
        preview.alpha_composite(labeled_card(f"{name} premium", panels[name]), (960, y))
        preview.alpha_composite(labeled_card(f"{name} result mock", result_mock(panels[name], theme)), (1920, y))
    PREVIEW.parent.mkdir(parents=True, exist_ok=True)
    preview.convert("RGB").save(PREVIEW, quality=94)
    print(f"wrote {PREVIEW.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
