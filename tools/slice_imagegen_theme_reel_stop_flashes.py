from pathlib import Path

from PIL import Image, ImageDraw, ImageEnhance, ImageFilter, ImageOps


from vslot_asset_toolchain import verify_asset_toolchain

verify_asset_toolchain()

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "qa/source/vslot_theme_reel_stop_flashes_imagegen.png"
OUT_DIR = ROOT / "app/src/main/res/drawable-nodpi"
PREVIEW = ROOT / "qa/screenshots/theme_reel_stop_flashes_contact_sheet.png"

THEMES = [
    ("violet", "Violet Fortune"),
    ("roman", "Roman Reels"),
    ("neon", "Neon Nights"),
    ("pharaoh", "Pharaoh Gold"),
    ("ocean", "Ocean Pearl"),
]

TARGET_SIZE = (320, 1080)
CROP_WIDTH_RATIO = 0.44


def alpha_from_luminance(panel: Image.Image) -> Image.Image:
    luminance = ImageOps.grayscale(panel)
    alpha = luminance.point(
        lambda value: 0
        if value < 8
        else min(238, int(((value - 8) / 118) ** 1.16 * 255))
    )
    return alpha.filter(ImageFilter.GaussianBlur(radius=0.3))


def slice_panel(source: Image.Image, index: int) -> Image.Image:
    width, height = source.size
    y0 = round(index * height / len(THEMES))
    y1 = round((index + 1) * height / len(THEMES))
    if index > 0:
        y0 += 3
    if index < len(THEMES) - 1:
        y1 -= 3
    panel = source.crop((0, y0, width, y1)).convert("RGB")
    crop_width = int(width * CROP_WIDTH_RATIO)
    left = (width - crop_width) // 2
    panel = panel.crop((left, 0, left + crop_width, panel.height))
    panel = ImageEnhance.Color(panel).enhance(1.16)
    panel = ImageEnhance.Contrast(panel).enhance(1.1)
    panel = ImageEnhance.Brightness(panel).enhance(1.06)
    panel = panel.resize(TARGET_SIZE, Image.Resampling.LANCZOS)
    rgba = panel.convert("RGBA")
    rgba.putalpha(alpha_from_luminance(panel))
    return rgba


def checkerboard(size: tuple[int, int]) -> Image.Image:
    image = Image.new("RGB", size, (9, 7, 20))
    draw = ImageDraw.Draw(image)
    cell = 24
    for y in range(0, size[1], cell):
        for x in range(0, size[0], cell):
            if (x // cell + y // cell) % 2 == 0:
                draw.rectangle((x, y, x + cell - 1, y + cell - 1), fill=(18, 15, 34))
    return image


def build_preview(panels: list[tuple[str, Image.Image]]) -> None:
    tile_width = 220
    tile_height = 520
    label_height = 32
    gap = 18
    width = len(panels) * tile_width + (len(panels) + 1) * gap
    height = tile_height + label_height + gap * 2
    preview = Image.new("RGB", (width, height), (7, 5, 16))
    draw = ImageDraw.Draw(preview)
    for index, (label, panel) in enumerate(panels):
        x = gap + index * (tile_width + gap)
        draw.text((x, 12), label, fill=(246, 239, 255))
        base = checkerboard((tile_width, tile_height))
        image = panel.copy()
        image.thumbnail((tile_width, tile_height), Image.Resampling.LANCZOS)
        px = (tile_width - image.width) // 2
        py = (tile_height - image.height) // 2
        base.paste(image, (px, py), image)
        preview.paste(base, (x, label_height + gap))
    PREVIEW.parent.mkdir(parents=True, exist_ok=True)
    preview.save(PREVIEW)


def main() -> None:
    if not SOURCE.exists():
        raise SystemExit(f"Missing source image: {SOURCE}")
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    source = Image.open(SOURCE)
    panels = []
    for index, (slug, label) in enumerate(THEMES):
        panel = slice_panel(source, index)
        out = OUT_DIR / f"reel_stop_flash_{slug}.webp"
        panel.save(out, "WEBP", quality=92, method=6)
        panels.append((label, panel))
        print(f"{out.relative_to(ROOT)} {panel.size[0]}x{panel.size[1]} {out.stat().st_size} bytes")
    build_preview(panels)
    print(PREVIEW.relative_to(ROOT))


if __name__ == "__main__":
    main()
