from pathlib import Path

from PIL import Image, ImageDraw, ImageEnhance, ImageFilter, ImageOps


from vslot_asset_toolchain import verify_asset_toolchain

verify_asset_toolchain()

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "qa/source/vslot_theme_bonus_entry_portals_imagegen.png"
OUT_DIR = ROOT / "app/src/main/res/drawable-nodpi"
PREVIEW = ROOT / "qa/screenshots/theme_bonus_entry_portals_contact_sheet.png"
MOCKUP = ROOT / "qa/screenshots/theme_bonus_entry_portals_on_reels_contact_sheet.png"

GRID_COLUMNS = 5
TARGET_SIZE = (1000, 720)

CELLS = [
    ("bonus_entry_portal_violet", "Violet", "violet", ("vf_symbol_diamond.webp", "vf_symbol_v_wild.webp", "vf_symbol_star.webp")),
    ("bonus_entry_portal_roman", "Roman", "roman", ("rr_symbol_laurel.webp", "rr_symbol_v_wild.webp", "rr_symbol_coin.webp")),
    ("bonus_entry_portal_neon", "Neon", "neon", ("nn_symbol_neon_seven.webp", "nn_symbol_v_wild.webp", "nn_symbol_holo_chip.webp")),
    ("bonus_entry_portal_pharaoh", "Pharaoh", "pharaoh", ("pg_symbol_scarab.webp", "pg_symbol_v_wild.webp", "pg_symbol_ankh.webp")),
    ("bonus_entry_portal_ocean", "Ocean", "ocean", ("op_symbol_shell.webp", "op_symbol_v_wild.webp", "op_symbol_pearl.webp")),
]


def active_bbox(image: Image.Image) -> tuple[int, int, int, int]:
    gray = ImageOps.grayscale(image)
    mask = gray.point(lambda value: 255 if value > 7 else 0)
    bbox = mask.getbbox()
    if bbox is None:
        return (0, 0, image.width, image.height)
    x0, y0, x1, y1 = bbox
    pad_x = max(12, round((x1 - x0) * 0.08))
    pad_y = max(12, round((y1 - y0) * 0.06))
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
        if value < 6
        else min(235, int(((value - 6) / 128) ** 1.08 * 255))
    )
    return alpha.filter(ImageFilter.GaussianBlur(radius=0.32))


def fade_outer_frame(alpha: Image.Image) -> Image.Image:
    width, height = alpha.size
    pixels = alpha.load()
    hard_edge = 18
    fade_width = 76
    for y in range(height):
        for x in range(width):
            dist = min(x, y, width - 1 - x, height - 1 - y)
            if dist <= hard_edge:
                factor = 0.0
            elif dist >= hard_edge + fade_width:
                factor = 1.0
            else:
                factor = (dist - hard_edge) / fade_width
            if factor < 1.0:
                pixels[x, y] = int(pixels[x, y] * factor)
    return alpha.filter(ImageFilter.GaussianBlur(radius=0.18))


def crop_panel(source: Image.Image, index: int) -> Image.Image:
    width, height = source.size
    x0 = round(index * width / GRID_COLUMNS)
    x1 = round((index + 1) * width / GRID_COLUMNS)
    cell = source.crop((x0, 0, x1, height)).convert("RGB")
    panel = cell.crop(active_bbox(cell))
    panel = ImageOps.contain(panel, TARGET_SIZE, method=Image.Resampling.LANCZOS)

    canvas = Image.new("RGB", TARGET_SIZE, (0, 0, 0))
    canvas.paste(panel, ((TARGET_SIZE[0] - panel.width) // 2, (TARGET_SIZE[1] - panel.height) // 2))
    canvas = ImageEnhance.Color(canvas).enhance(1.12)
    canvas = ImageEnhance.Contrast(canvas).enhance(1.08)
    canvas = ImageEnhance.Brightness(canvas).enhance(1.03)

    rgba = canvas.convert("RGBA")
    alpha = fade_outer_frame(alpha_from_luminance(canvas))
    rgba.putalpha(alpha)
    return rgba


def checkerboard(size: tuple[int, int]) -> Image.Image:
    image = Image.new("RGB", size, (7, 6, 18))
    draw = ImageDraw.Draw(image)
    cell = 18
    for y in range(0, size[1], cell):
        for x in range(0, size[0], cell):
            if (x // cell + y // cell) % 2 == 0:
                draw.rectangle((x, y, x + cell - 1, y + cell - 1), fill=(16, 14, 34))
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
    palette = [(18, 14, 42), (12, 10, 30)]
    symbols = [load_symbol(name, int(min(cell_w, cell_h) * 0.74)) for name in symbol_names]
    for row in range(3):
        for column in range(5):
            x0 = round(column * cell_w)
            y0 = round(row * cell_h)
            x1 = round((column + 1) * cell_w)
            y1 = round((row + 1) * cell_h)
            draw.rounded_rectangle((x0 + 1, y0 + 1, x1 - 2, y1 - 2), radius=5, fill=palette[(row + column) % 2])
            symbol = symbols[(row + column) % len(symbols)]
            image.alpha_composite(symbol, (x0 + (x1 - x0 - symbol.width) // 2, y0 + (y1 - y0 - symbol.height) // 2))
    return image


def build_preview(panels: list[tuple[str, Image.Image]]) -> None:
    thumb_w = 232
    thumb_h = 168
    label_h = 24
    gap = 14
    width = gap + len(panels) * (thumb_w + gap)
    height = gap + label_h + thumb_h + gap
    preview = Image.new("RGB", (width, height), (6, 5, 16))
    draw = ImageDraw.Draw(preview)
    for index, (label, panel) in enumerate(panels):
        x = gap + index * (thumb_w + gap)
        y = gap
        draw.text((x, y), label, fill=(246, 240, 255))
        base = checkerboard((thumb_w, thumb_h)).convert("RGBA")
        thumb = panel.copy()
        thumb.thumbnail((thumb_w, thumb_h), Image.Resampling.LANCZOS)
        base.alpha_composite(thumb, ((thumb_w - thumb.width) // 2, (thumb_h - thumb.height) // 2))
        preview.paste(base.convert("RGB"), (x, y + label_h))
    PREVIEW.parent.mkdir(parents=True, exist_ok=True)
    preview.save(PREVIEW)


def build_mockup(panels: list[tuple[str, Image.Image]]) -> None:
    thumb_w = 244
    thumb_h = 156
    label_h = 24
    gap = 14
    width = gap + len(panels) * (thumb_w + gap)
    height = gap + label_h + thumb_h + gap
    mockup = Image.new("RGBA", (width, height), (6, 5, 16, 255))
    draw = ImageDraw.Draw(mockup)
    for index, ((label, portal), cell) in enumerate(zip(panels, CELLS)):
        x = gap + index * (thumb_w + gap)
        y = gap
        draw.text((x, y), label, fill=(246, 240, 255))
        base = symbol_grid(cell[3], (thumb_w, thumb_h))
        portal_preview = portal.copy()
        portal_preview.thumbnail((thumb_w, thumb_h), Image.Resampling.LANCZOS)
        base.alpha_composite(portal_preview, ((thumb_w - portal_preview.width) // 2, (thumb_h - portal_preview.height) // 2))
        glass = Image.open(OUT_DIR / f"reel_glass_overlay_{cell[2]}.webp").convert("RGBA")
        glass.thumbnail((thumb_w, thumb_h), Image.Resampling.LANCZOS)
        base.alpha_composite(glass, ((thumb_w - glass.width) // 2, (thumb_h - glass.height) // 2))
        mockup.alpha_composite(base, (x, y + label_h))
    MOCKUP.parent.mkdir(parents=True, exist_ok=True)
    mockup.convert("RGB").save(MOCKUP, quality=94)


def main() -> None:
    if not SOURCE.exists():
        raise SystemExit(f"Missing source image: {SOURCE}")
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    source = Image.open(SOURCE).convert("RGB")
    panels = []
    for index, (file_stem, label, *_rest) in enumerate(CELLS):
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
