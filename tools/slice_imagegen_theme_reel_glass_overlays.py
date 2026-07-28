from pathlib import Path

from PIL import Image, ImageDraw, ImageEnhance, ImageFilter, ImageOps


from vslot_asset_toolchain import verify_asset_toolchain

verify_asset_toolchain()

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "qa/source/vslot_theme_reel_glass_overlays_imagegen.png"
OUT_DIR = ROOT / "app/src/main/res/drawable-nodpi"
PREVIEW = ROOT / "qa/screenshots/theme_reel_glass_overlays_contact_sheet.png"
MOCKUP = ROOT / "qa/screenshots/theme_reel_glass_overlays_on_symbols_contact_sheet.png"

TARGET_SIZE = (960, 1020)
GRID_COLUMNS = 5

CELLS = [
    ("reel_glass_overlay_violet", "Violet", "vf_symbol_diamond.webp", "vf_symbol_v_wild.webp", "vf_symbol_star.webp"),
    ("reel_glass_overlay_roman", "Roman", "rr_symbol_laurel.webp", "rr_symbol_v_wild.webp", "rr_symbol_coin.webp"),
    ("reel_glass_overlay_neon", "Neon", "nn_symbol_neon_seven.webp", "nn_symbol_v_wild.webp", "nn_symbol_holo_chip.webp"),
    ("reel_glass_overlay_pharaoh", "Pharaoh", "pg_symbol_scarab.webp", "pg_symbol_v_wild.webp", "pg_symbol_ankh.webp"),
    ("reel_glass_overlay_ocean", "Ocean", "op_symbol_shell.webp", "op_symbol_v_wild.webp", "op_symbol_pearl.webp"),
]


def active_bbox(image: Image.Image) -> tuple[int, int, int, int]:
    gray = ImageOps.grayscale(image)
    mask = gray.point(lambda value: 255 if value > 10 else 0)
    bbox = mask.getbbox()
    if bbox is None:
        return (0, 0, image.width, image.height)
    x0, y0, x1, y1 = bbox
    pad_x = max(8, round((x1 - x0) * 0.045))
    pad_y = max(8, round((y1 - y0) * 0.045))
    return (
        max(0, x0 - pad_x),
        max(0, y0 - pad_y),
        min(image.width, x1 + pad_x),
        min(image.height, y1 + pad_y),
    )


def alpha_from_luminance(image: Image.Image) -> Image.Image:
    luminance = ImageOps.grayscale(image)
    alpha = luminance.point(
        lambda value: 0
        if value < 5
        else min(235, int(((value - 5) / 118) ** 1.05 * 255))
    )
    return alpha.filter(ImageFilter.GaussianBlur(radius=0.2))


def crop_panel(source: Image.Image, index: int) -> Image.Image:
    width, height = source.size
    x0 = round(index * width / GRID_COLUMNS)
    x1 = round((index + 1) * width / GRID_COLUMNS)
    cell = source.crop((x0, 0, x1, height)).convert("RGB")
    panel = cell.crop(active_bbox(cell))
    panel = panel.resize(TARGET_SIZE, Image.Resampling.LANCZOS)
    panel = ImageEnhance.Color(panel).enhance(1.08)
    panel = ImageEnhance.Contrast(panel).enhance(1.06)
    panel = ImageEnhance.Brightness(panel).enhance(1.02)
    rgba = panel.convert("RGBA")
    rgba.putalpha(alpha_from_luminance(panel))
    return rgba


def checkerboard(size: tuple[int, int]) -> Image.Image:
    image = Image.new("RGB", size, (8, 7, 18))
    draw = ImageDraw.Draw(image)
    cell = 28
    for y in range(0, size[1], cell):
        for x in range(0, size[0], cell):
            if (x // cell + y // cell) % 2 == 0:
                draw.rectangle((x, y, x + cell - 1, y + cell - 1), fill=(17, 15, 34))
    return image


def load_symbol(name: str, size: int) -> Image.Image:
    symbol = Image.open(OUT_DIR / name).convert("RGBA")
    symbol.thumbnail((size, size), Image.Resampling.LANCZOS)
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    canvas.alpha_composite(symbol, ((size - symbol.width) // 2, (size - symbol.height) // 2))
    return canvas


def symbol_grid(symbol_names: tuple[str, str, str], size: tuple[int, int]) -> Image.Image:
    width, height = size
    image = Image.new("RGBA", size, (6, 5, 18, 255))
    draw = ImageDraw.Draw(image)
    cell_w = width / 5
    cell_h = height / 3
    palette = [(17, 13, 38), (13, 11, 31)]
    symbols = [load_symbol(name, int(min(cell_w, cell_h) * 0.72)) for name in symbol_names]
    for row in range(3):
        for column in range(5):
            x0 = round(column * cell_w)
            y0 = round(row * cell_h)
            x1 = round((column + 1) * cell_w)
            y1 = round((row + 1) * cell_h)
            draw.rectangle((x0, y0, x1, y1), fill=palette[(row + column) % 2])
            symbol = symbols[(row + column) % len(symbols)]
            image.alpha_composite(symbol, (x0 + (x1 - x0 - symbol.width) // 2, y0 + (y1 - y0 - symbol.height) // 2))
    return image


def build_preview(panels: list[tuple[str, Image.Image]]) -> None:
    tile_w = 196
    tile_h = 210
    label_h = 28
    gap = 14
    width = gap + len(panels) * (tile_w + gap)
    height = gap + label_h + tile_h + gap
    preview = Image.new("RGB", (width, height), (6, 5, 16))
    draw = ImageDraw.Draw(preview)
    for index, (label, panel) in enumerate(panels):
        x = gap + index * (tile_w + gap)
        y = gap
        draw.text((x, y), label, fill=(246, 240, 255))
        base = checkerboard((tile_w, tile_h)).convert("RGBA")
        image = panel.copy()
        image.thumbnail((tile_w, tile_h), Image.Resampling.LANCZOS)
        base.alpha_composite(image, ((tile_w - image.width) // 2, (tile_h - image.height) // 2))
        preview.paste(base.convert("RGB"), (x, y + label_h))
    PREVIEW.parent.mkdir(parents=True, exist_ok=True)
    preview.save(PREVIEW)


def build_mockup(panels: list[tuple[str, Image.Image]]) -> None:
    tile_w = 214
    tile_h = 228
    label_h = 28
    gap = 14
    width = gap + len(panels) * (tile_w + gap)
    height = gap + label_h + tile_h + gap
    mockup = Image.new("RGBA", (width, height), (6, 5, 16, 255))
    draw = ImageDraw.Draw(mockup)
    for index, ((label, panel), cell) in enumerate(zip(panels, CELLS)):
        x = gap + index * (tile_w + gap)
        y = gap
        draw.text((x, y), label, fill=(246, 240, 255))
        base = symbol_grid(cell[2:], (tile_w, tile_h))
        glass = panel.copy()
        glass.thumbnail((tile_w, tile_h), Image.Resampling.LANCZOS)
        base.alpha_composite(glass, ((tile_w - glass.width) // 2, (tile_h - glass.height) // 2))
        mockup.alpha_composite(base, (x, y + label_h))
    MOCKUP.parent.mkdir(parents=True, exist_ok=True)
    mockup.convert("RGB").save(MOCKUP, quality=94)


def main() -> None:
    if not SOURCE.exists():
        raise SystemExit(f"Missing source image: {SOURCE}")
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    source = Image.open(SOURCE).convert("RGB")
    panels = []
    for index, (file_stem, label, *_symbols) in enumerate(CELLS):
        panel = crop_panel(source, index)
        out = OUT_DIR / f"{file_stem}.webp"
        panel.save(out, "WEBP", quality=92, method=6)
        panels.append((label, panel))
        print(f"{out.relative_to(ROOT)} {panel.size[0]}x{panel.size[1]} {out.stat().st_size} bytes")
    build_preview(panels)
    build_mockup(panels)
    print(PREVIEW.relative_to(ROOT))
    print(MOCKUP.relative_to(ROOT))


if __name__ == "__main__":
    main()
