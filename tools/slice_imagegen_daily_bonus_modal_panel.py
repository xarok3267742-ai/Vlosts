from collections import deque
from pathlib import Path

from PIL import Image, ImageDraw


from vslot_asset_toolchain import verify_asset_toolchain

verify_asset_toolchain()

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "qa" / "source" / "vslot_daily_bonus_modal_panel_premium_imagegen.png"
OUT = ROOT / "app" / "src" / "main" / "res" / "drawable-nodpi" / "daily_bonus_modal_panel_premium.webp"
PREVIEW = ROOT / "qa" / "screenshots" / "daily_bonus_modal_panel_premium_contact_sheet.png"

TARGET_SIZE = (900, 420)


def center_crop_to_ratio(image: Image.Image, ratio: float) -> Image.Image:
    width, height = image.size
    current_ratio = width / height
    if abs(current_ratio - ratio) < 0.001:
        return image
    if current_ratio > ratio:
        new_width = round(height * ratio)
        left = (width - new_width) // 2
        return image.crop((left, 0, left + new_width, height))
    new_height = round(width / ratio)
    top = (height - new_height) // 2
    return image.crop((0, top, width, top + new_height))


def is_checker_background(pixel: tuple[int, int, int, int]) -> bool:
    r, g, b, alpha = pixel
    return alpha > 0 and max(r, g, b) - min(r, g, b) <= 22 and min(r, g, b) >= 214


def remove_edge_checkerboard(image: Image.Image) -> Image.Image:
    rgba = image.convert("RGBA")
    pixels = rgba.load()
    width, height = rgba.size
    visited = set()
    queue: deque[tuple[int, int]] = deque()

    for x in range(width):
        for y in (0, height - 1):
            if is_checker_background(pixels[x, y]):
                queue.append((x, y))
                visited.add((x, y))
    for y in range(height):
        for x in (0, width - 1):
            if (x, y) not in visited and is_checker_background(pixels[x, y]):
                queue.append((x, y))
                visited.add((x, y))

    while queue:
        x, y = queue.popleft()
        for nx, ny in ((x + 1, y), (x - 1, y), (x, y + 1), (x, y - 1)):
            if nx < 0 or ny < 0 or nx >= width or ny >= height or (nx, ny) in visited:
                continue
            if is_checker_background(pixels[nx, ny]):
                visited.add((nx, ny))
                queue.append((nx, ny))

    for x, y in visited:
        pixels[x, y] = (0, 0, 0, 0)
    return rgba


def alpha_crop(image: Image.Image, padding: int = 0) -> Image.Image:
    alpha = image.getchannel("A")
    bbox = alpha.getbbox()
    if bbox is None:
        return image
    left, top, right, bottom = bbox
    left = max(0, left - padding)
    top = max(0, top - padding)
    right = min(image.width, right + padding)
    bottom = min(image.height, bottom + padding)
    return image.crop((left, top, right, bottom))


def resize_contain(image: Image.Image, size: tuple[int, int]) -> Image.Image:
    canvas = Image.new("RGBA", size, (0, 0, 0, 0))
    fitted = image.copy()
    fitted.thumbnail(size, Image.Resampling.LANCZOS)
    left = (size[0] - fitted.width) // 2
    top = (size[1] - fitted.height) // 2
    canvas.alpha_composite(fitted, (left, top))
    return canvas


def open_asset(name: str) -> Image.Image:
    for folder in ("drawable-nodpi", "drawable"):
        path = ROOT / "app" / "src" / "main" / "res" / folder / name
        if path.exists():
            return Image.open(path).convert("RGBA")
        for candidate in (ROOT / "app" / "src" / "main" / "res" / folder).glob(f"{Path(name).stem}.*"):
            return Image.open(candidate).convert("RGBA")
    raise FileNotFoundError(name)


def paste_center(canvas: Image.Image, asset: Image.Image, box: tuple[int, int, int, int]) -> None:
    x, y, width, height = box
    fitted = asset.copy()
    fitted.thumbnail((width, height), Image.Resampling.LANCZOS)
    canvas.alpha_composite(fitted, (x + (width - fitted.width) // 2, y + (height - fitted.height) // 2))


def mock_dialog(panel: Image.Image, waiting: bool = False) -> Image.Image:
    canvas = Image.new("RGBA", TARGET_SIZE, (8, 6, 18, 255))
    canvas.alpha_composite(open_asset("modal_panel_backplate.webp").resize(TARGET_SIZE, Image.Resampling.LANCZOS))
    canvas.alpha_composite(panel)
    canvas.alpha_composite(open_asset("daily_bonus_stage_lattice.webp").resize(TARGET_SIZE, Image.Resampling.LANCZOS))
    overlay_name = "daily_bonus_cooldown_vault.webp" if waiting else "daily_bonus_reward_burst.webp"
    overlay = open_asset(overlay_name).resize(TARGET_SIZE, Image.Resampling.LANCZOS)
    overlay.putalpha(112 if waiting else 58)
    canvas.alpha_composite(overlay)
    paste_center(canvas, open_asset("modal_badge_bonus.webp"), (398, 52, 104, 104))
    paste_center(canvas, open_asset("title_daily_bonus.webp"), (140, 166, 620, 54))
    paste_center(
        canvas,
        open_asset("label_bonus_wait_body.webp" if waiting else "label_bonus_ready_body.webp"),
        (128, 230, 644, 62),
    )
    if waiting:
        paste_center(canvas, open_asset("daily_bonus_modal_countdown_rail.webp"), (328, 296, 244, 44))
    paste_center(canvas, open_asset("btn_bonus_claim_default.webp"), (360, 346, 180, 52))
    paste_center(canvas, open_asset("label_ok_action.webp" if waiting else "label_claim_bonus.webp"), (378, 350, 144, 44))
    return canvas


def labeled_card(title: str, image: Image.Image) -> Image.Image:
    card = Image.new("RGBA", (960, 520), (16, 12, 28, 255))
    draw = ImageDraw.Draw(card)
    card.alpha_composite(image, (30, 62))
    draw.text((30, 22), title, fill=(242, 220, 170, 255))
    return card


def main() -> None:
    source = Image.open(SOURCE)
    source = center_crop_to_ratio(source, TARGET_SIZE[0] / TARGET_SIZE[1])
    cleaned = remove_edge_checkerboard(source)
    panel = resize_contain(alpha_crop(cleaned, padding=10), TARGET_SIZE)
    panel.save(OUT, quality=96, method=6)

    old_panel = open_asset("daily_bonus_modal_panel.webp").resize(TARGET_SIZE, Image.Resampling.LANCZOS)
    preview = Image.new("RGBA", (1920, 1040), (10, 8, 20, 255))
    preview.alpha_composite(labeled_card("old panel", old_panel), (0, 0))
    preview.alpha_composite(labeled_card("premium panel", panel), (960, 0))
    preview.alpha_composite(labeled_card("ready mock", mock_dialog(panel, waiting=False)), (0, 520))
    preview.alpha_composite(labeled_card("cooldown mock", mock_dialog(panel, waiting=True)), (960, 520))
    PREVIEW.parent.mkdir(parents=True, exist_ok=True)
    preview.convert("RGB").save(PREVIEW, quality=94)

    print(f"wrote {OUT.relative_to(ROOT)} {OUT.stat().st_size} bytes")
    print(f"wrote {PREVIEW.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
