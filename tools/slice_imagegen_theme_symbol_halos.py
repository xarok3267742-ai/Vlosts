from pathlib import Path

from PIL import Image, ImageDraw, ImageEnhance, ImageFilter, ImageOps


from vslot_asset_toolchain import verify_asset_toolchain

verify_asset_toolchain()

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "qa/source/vslot_theme_symbol_halos_imagegen.png"
OUT_DIR = ROOT / "app/src/main/res/drawable-nodpi"
PREVIEW = ROOT / "qa/screenshots/theme_symbol_halos_contact_sheet.png"

TARGET_SIZE = (640, 640)
GRID_COLUMNS = 2
GRID_ROWS = 5

CELLS = [
    ("symbol_win_halo_violet", "Violet win", 0, 0),
    ("symbol_win_halo_roman", "Roman win", 1, 0),
    ("symbol_win_halo_neon", "Neon win", 0, 1),
    ("symbol_win_halo_pharaoh", "Pharaoh win", 1, 1),
    ("symbol_win_halo_ocean", "Ocean win", 0, 2),
    ("symbol_bonus_scatter_halo_violet", "Violet bonus", 1, 2),
    ("symbol_bonus_scatter_halo_roman", "Roman bonus", 0, 3),
    ("symbol_bonus_scatter_halo_neon", "Neon bonus", 1, 3),
    ("symbol_bonus_scatter_halo_pharaoh", "Pharaoh bonus", 0, 4),
    ("symbol_bonus_scatter_halo_ocean", "Ocean bonus", 1, 4),
]


def alpha_from_luminance(image: Image.Image) -> Image.Image:
    luminance = ImageOps.grayscale(image)
    alpha = luminance.point(
        lambda value: 0
        if value < 7
        else min(236, int(((value - 7) / 120) ** 1.15 * 255))
    )
    return alpha.filter(ImageFilter.GaussianBlur(radius=0.25))


def remove_vertical_bleed(image: Image.Image) -> Image.Image:
    alpha = image.getchannel("A")
    width, height = alpha.size
    raw = alpha.tobytes()
    minimum_active_pixels = max(18, int(width * 0.03))
    row_counts = [
        sum(1 for value in raw[y * width : (y + 1) * width] if value > 18)
        for y in range(height)
    ]
    runs = []
    start = None
    score = 0
    for y, count in enumerate(row_counts):
        if count >= minimum_active_pixels:
            if start is None:
                start = y
                score = 0
            score += count
        elif start is not None:
            runs.append((start, y - 1, score))
            start = None
            score = 0
    if start is not None:
        runs.append((start, height - 1, score))
    if len(runs) <= 1:
        return image

    keep_start, keep_end, _ = max(runs, key=lambda run: run[2])
    keep_start = max(0, keep_start - 22)
    keep_end = min(height - 1, keep_end + 22)
    if keep_start == 0 and keep_end == height - 1:
        return image

    cleaned_alpha = Image.new("L", (width, height), 0)
    cleaned_alpha.paste(alpha.crop((0, keep_start, width, keep_end + 1)), (0, keep_start))
    cleaned = image.copy()
    cleaned.putalpha(cleaned_alpha)
    return cleaned


def crop_cell(source: Image.Image, column: int, row: int) -> Image.Image:
    width, height = source.size
    x0 = round(column * width / GRID_COLUMNS)
    x1 = round((column + 1) * width / GRID_COLUMNS)
    y0 = round(row * height / GRID_ROWS)
    y1 = round((row + 1) * height / GRID_ROWS)
    inset_x = max(2, round((x1 - x0) * 0.018))
    inset_y = max(2, round((y1 - y0) * 0.018))
    panel = source.crop((x0 + inset_x, y0 + inset_y, x1 - inset_x, y1 - inset_y)).convert("RGB")
    square = ImageOps.pad(panel, TARGET_SIZE, method=Image.Resampling.LANCZOS, color=(0, 0, 0), centering=(0.5, 0.5))
    square = ImageEnhance.Color(square).enhance(1.16)
    square = ImageEnhance.Contrast(square).enhance(1.08)
    square = ImageEnhance.Brightness(square).enhance(1.04)
    rgba = square.convert("RGBA")
    rgba.putalpha(alpha_from_luminance(square))
    return remove_vertical_bleed(rgba)


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
    columns = 5
    tile = 210
    label = 30
    gap = 14
    width = columns * tile + (columns + 1) * gap
    rows = 2
    height = rows * (tile + label + gap) + gap
    preview = Image.new("RGB", (width, height), (7, 5, 16))
    draw = ImageDraw.Draw(preview)
    for index, (name, panel) in enumerate(panels):
        column = index % columns
        row = index // columns
        x = gap + column * (tile + gap)
        y = gap + row * (tile + label + gap)
        draw.text((x, y), name, fill=(246, 239, 255))
        base = checkerboard((tile, tile))
        image = panel.copy()
        image.thumbnail((tile, tile), Image.Resampling.LANCZOS)
        px = (tile - image.width) // 2
        py = (tile - image.height) // 2
        base.paste(image, (px, py), image)
        preview.paste(base, (x, y + label))
    PREVIEW.parent.mkdir(parents=True, exist_ok=True)
    preview.save(PREVIEW)


def main() -> None:
    if not SOURCE.exists():
        raise SystemExit(f"Missing source image: {SOURCE}")
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    source = Image.open(SOURCE)
    panels = []
    for file_stem, label, column, row in CELLS:
        panel = crop_cell(source, column, row)
        out = OUT_DIR / f"{file_stem}.webp"
        panel.save(out, "WEBP", quality=92, method=6)
        panels.append((label, panel))
        print(f"{out.relative_to(ROOT)} {panel.size[0]}x{panel.size[1]} {out.stat().st_size} bytes")
    build_preview(panels)
    print(PREVIEW.relative_to(ROOT))


if __name__ == "__main__":
    main()
