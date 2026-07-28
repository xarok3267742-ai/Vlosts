from pathlib import Path

from PIL import Image, ImageDraw, ImageEnhance, ImageFilter, ImageOps


from vslot_asset_toolchain import verify_asset_toolchain

verify_asset_toolchain()

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "qa/source/vslot_paytable_theme_modal_panels_imagegen.png"
OUT_DIR = ROOT / "app/src/main/res/drawable-nodpi"
PREVIEW = ROOT / "qa/screenshots/paytable_theme_modal_panels_contact_sheet.png"
MOCKUP = ROOT / "qa/screenshots/paytable_theme_modal_panels_mockup_contact_sheet.png"

GRID_COLUMNS = 5
TARGET_SIZE = (1000, 1400)

CELLS = [
    ("paytable_modal_panel_violet", "Violet", "paytable_cabinet_lattice.webp", "paytable_payline_guide.webp", "paytable_row_panel.webp"),
    ("paytable_modal_panel_roman", "Roman", "paytable_cabinet_lattice_roman.webp", "paytable_payline_guide_roman.webp", "paytable_row_panel_roman.webp"),
    ("paytable_modal_panel_neon", "Neon", "paytable_cabinet_lattice_neon.webp", "paytable_payline_guide_neon.webp", "paytable_row_panel_neon.webp"),
    ("paytable_modal_panel_pharaoh", "Pharaoh", "paytable_cabinet_lattice_pharaoh.webp", "paytable_payline_guide_pharaoh.webp", "paytable_row_panel_pharaoh.webp"),
    ("paytable_modal_panel_ocean", "Ocean", "paytable_cabinet_lattice_ocean.webp", "paytable_payline_guide_ocean.webp", "paytable_row_panel_ocean.webp"),
]


def active_bbox(image: Image.Image) -> tuple[int, int, int, int]:
    gray = ImageOps.grayscale(image)
    mask = gray.point(lambda value: 255 if value > 7 else 0)
    bbox = mask.getbbox()
    if bbox is None:
        return (0, 0, image.width, image.height)
    x0, y0, x1, y1 = bbox
    pad_x = max(10, round((x1 - x0) * 0.035))
    pad_y = max(10, round((y1 - y0) * 0.03))
    return (
        max(0, x0 - pad_x),
        max(0, y0 - pad_y),
        min(image.width, x1 + pad_x),
        min(image.height, y1 + pad_y),
    )


def rounded_panel_mask(size: tuple[int, int]) -> Image.Image:
    width, height = size
    scale = 3
    mask = Image.new("L", (width * scale, height * scale), 0)
    draw = ImageDraw.Draw(mask)
    inset = 14 * scale
    radius = 52 * scale
    draw.rounded_rectangle(
        (inset, inset, width * scale - inset, height * scale - inset),
        radius=radius,
        fill=247,
    )
    return mask.resize(size, Image.Resampling.LANCZOS).filter(ImageFilter.GaussianBlur(radius=0.2))


def panel_source(source: Image.Image, index: int) -> Image.Image:
    width, height = source.size
    x0 = round(index * width / GRID_COLUMNS)
    x1 = round((index + 1) * width / GRID_COLUMNS)
    cell = source.crop((x0, 0, x1, height)).convert("RGB")
    return cell.crop(active_bbox(cell))


def compose_panel(panel: Image.Image) -> Image.Image:
    image = ImageOps.fit(panel, TARGET_SIZE, method=Image.Resampling.LANCZOS, centering=(0.5, 0.48))
    image = ImageEnhance.Color(image).enhance(1.08)
    image = ImageEnhance.Contrast(image).enhance(1.08)
    image = ImageEnhance.Brightness(image).enhance(1.02)
    rgba = image.convert("RGBA")
    rgba.putalpha(rounded_panel_mask(TARGET_SIZE))
    return rgba


def checkerboard(size: tuple[int, int]) -> Image.Image:
    image = Image.new("RGB", size, (8, 7, 18))
    draw = ImageDraw.Draw(image)
    cell = 18
    for y in range(0, size[1], cell):
        for x in range(0, size[0], cell):
            if (x // cell + y // cell) % 2 == 0:
                draw.rectangle((x, y, x + cell - 1, y + cell - 1), fill=(18, 15, 34))
    return image


def load_thumb(asset: str, box: tuple[int, int]) -> Image.Image:
    image = Image.open(OUT_DIR / asset).convert("RGBA")
    image.thumbnail(box, Image.Resampling.LANCZOS)
    canvas = Image.new("RGBA", box, (0, 0, 0, 0))
    canvas.alpha_composite(image, ((box[0] - image.width) // 2, (box[1] - image.height) // 2))
    return canvas


def build_preview(panels: list[tuple[str, Image.Image]]) -> None:
    thumb_w = 160
    thumb_h = 224
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
    thumb_w = 190
    thumb_h = 266
    label_h = 24
    gap = 14
    width = gap + len(panels) * (thumb_w + gap)
    height = gap + label_h + thumb_h + gap
    mockup = Image.new("RGBA", (width, height), (6, 5, 16, 255))
    draw = ImageDraw.Draw(mockup)
    for index, ((label, panel), cell) in enumerate(zip(panels, CELLS)):
        x = gap + index * (thumb_w + gap)
        y = gap
        draw.text((x, y), label, fill=(246, 240, 255))
        base = checkerboard((thumb_w, thumb_h)).convert("RGBA")
        panel_thumb = panel.copy()
        panel_thumb.thumbnail((thumb_w, thumb_h), Image.Resampling.LANCZOS)
        base.alpha_composite(panel_thumb, ((thumb_w - panel_thumb.width) // 2, (thumb_h - panel_thumb.height) // 2))

        inset_x = 20
        top = 18
        lattice = load_thumb(cell[2], (thumb_w - inset_x * 2, 72))
        base.alpha_composite(lattice, (inset_x, top))
        guide = load_thumb(cell[3], (thumb_w - inset_x * 2, 24))
        base.alpha_composite(guide, (inset_x, top + 54))
        row_asset = cell[4]
        for row in range(4):
            row_image = load_thumb(row_asset, (thumb_w - inset_x * 2, 22))
            base.alpha_composite(row_image, (inset_x, top + 86 + row * 25))
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
        panel = compose_panel(panel_source(source, index))
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
