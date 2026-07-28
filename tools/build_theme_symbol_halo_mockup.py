from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter


ROOT = Path(__file__).resolve().parents[1]
DRAWABLE = ROOT / "app/src/main/res/drawable-nodpi"
OUT = ROOT / "qa/screenshots/theme_symbol_halos_on_symbols_contact_sheet.png"

THEMES = [
    ("Violet", "violet", "vf_symbol_diamond.webp", "vf_symbol_v_wild.webp"),
    ("Roman", "roman", "rr_symbol_laurel.webp", "rr_symbol_v_wild.webp"),
    ("Neon", "neon", "nn_symbol_neon_seven.webp", "nn_symbol_v_wild.webp"),
    ("Pharaoh", "pharaoh", "pg_symbol_scarab.webp", "pg_symbol_v_wild.webp"),
    ("Ocean", "ocean", "op_symbol_shell.webp", "op_symbol_v_wild.webp"),
]


def load_rgba(name: str) -> Image.Image:
    path = DRAWABLE / name
    if not path.exists():
        raise SystemExit(f"Missing drawable: {path}")
    return Image.open(path).convert("RGBA")


def fit(image: Image.Image, size: int) -> Image.Image:
    copy = image.copy()
    copy.thumbnail((size, size), Image.Resampling.LANCZOS)
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    canvas.alpha_composite(copy, ((size - copy.width) // 2, (size - copy.height) // 2))
    return canvas


def draw_tile(draw: ImageDraw.ImageDraw, canvas: Image.Image, x: int, y: int, title: str, halo: Image.Image, symbol: Image.Image) -> None:
    tile_w = 260
    tile_h = 292
    radius = 10
    draw.rounded_rectangle((x, y, x + tile_w, y + tile_h), radius=radius, fill=(13, 9, 31), outline=(88, 73, 140), width=1)
    inner = Image.new("RGBA", (tile_w - 28, tile_w - 28), (0, 0, 0, 0))
    plate = Image.new("RGBA", inner.size, (10, 8, 26, 232))
    plate = plate.filter(ImageFilter.GaussianBlur(radius=0.2))
    inner.alpha_composite(plate)
    halo_img = fit(halo, 218)
    symbol_img = fit(symbol, 128)
    inner.alpha_composite(halo_img, ((inner.width - halo_img.width) // 2, 7))
    inner.alpha_composite(symbol_img, ((inner.width - symbol_img.width) // 2, 52))
    canvas.alpha_composite(inner, (x + 14, y + 44))
    draw.text((x + 14, y + 14), title, fill=(246, 240, 255))


def main() -> None:
    OUT.parent.mkdir(parents=True, exist_ok=True)
    gap = 16
    tile_w = 260
    tile_h = 292
    width = gap + len(THEMES) * (tile_w + gap)
    height = gap + 2 * (tile_h + gap)
    canvas = Image.new("RGBA", (width, height), (6, 4, 16, 255))
    draw = ImageDraw.Draw(canvas)
    for index, (label, key, win_symbol, bonus_symbol) in enumerate(THEMES):
        x = gap + index * (tile_w + gap)
        draw_tile(
            draw,
            canvas,
            x,
            gap,
            f"{label} win halo",
            load_rgba(f"symbol_win_halo_{key}.webp"),
            load_rgba(win_symbol),
        )
        draw_tile(
            draw,
            canvas,
            x,
            gap + tile_h + gap,
            f"{label} bonus halo",
            load_rgba(f"symbol_bonus_scatter_halo_{key}.webp"),
            load_rgba(bonus_symbol),
        )
    canvas.convert("RGB").save(OUT, quality=94)
    print(OUT.relative_to(ROOT))


if __name__ == "__main__":
    main()
