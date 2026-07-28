from pathlib import Path

from PIL import Image, ImageDraw, ImageEnhance, ImageFilter, ImageOps


from vslot_asset_toolchain import verify_asset_toolchain

verify_asset_toolchain()

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "qa/source/vslot_theme_spin_overlays_imagegen.png"
OUT_DIR = ROOT / "app/src/main/res/drawable-nodpi"
PREVIEW = ROOT / "qa/screenshots/theme_spin_overlays_contact_sheet.png"

THEMES = [
    ("violet", "Violet Fortune"),
    ("roman", "Roman Reels"),
    ("neon", "Neon Nights"),
    ("pharaoh", "Pharaoh Gold"),
    ("ocean", "Ocean Pearl"),
]

TARGET_SIZE = (1400, 492)


def alpha_from_luminance(panel: Image.Image) -> Image.Image:
    luminance = ImageOps.grayscale(panel)
    alpha = luminance.point(
        lambda value: 0
        if value < 10
        else min(230, int(((value - 10) / 112) ** 1.18 * 255))
    )
    return alpha.filter(ImageFilter.GaussianBlur(radius=0.35))


def slice_panel(source: Image.Image, index: int) -> Image.Image:
    width, height = source.size
    y0 = round(index * height / len(THEMES))
    y1 = round((index + 1) * height / len(THEMES))
    # Remove the dark separator lines that imagegen placed between panels.
    if index > 0:
        y0 += 3
    if index < len(THEMES) - 1:
        y1 -= 3
    panel = source.crop((0, y0, width, y1)).convert("RGB")
    panel = ImageEnhance.Color(panel).enhance(1.18)
    panel = ImageEnhance.Contrast(panel).enhance(1.08)
    panel = ImageEnhance.Brightness(panel).enhance(1.04)
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
    preview_width = 900
    tile_height = 248
    label_height = 34
    preview = Image.new(
        "RGB",
        (preview_width, len(panels) * (tile_height + label_height)),
        (7, 5, 16),
    )
    draw = ImageDraw.Draw(preview)
    for index, (label, panel) in enumerate(panels):
        top = index * (tile_height + label_height)
        draw.text((18, top + 10), label, fill=(246, 239, 255))
        base = checkerboard((preview_width, tile_height))
        image = panel.copy()
        image.thumbnail((preview_width, tile_height), Image.Resampling.LANCZOS)
        x = (preview_width - image.width) // 2
        y = top + label_height + (tile_height - image.height) // 2
        base.paste(image, (x, (tile_height - image.height) // 2), image)
        preview.paste(base, (0, top + label_height))
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
        out = OUT_DIR / f"theme_spin_overlay_{slug}.webp"
        panel.save(out, "WEBP", quality=92, method=6)
        panels.append((label, panel))
        print(f"{out.relative_to(ROOT)} {panel.size[0]}x{panel.size[1]} {out.stat().st_size} bytes")
    build_preview(panels)
    print(PREVIEW.relative_to(ROOT))


if __name__ == "__main__":
    main()
