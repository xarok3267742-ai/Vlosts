from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter

from vslot_asset_toolchain import verify_asset_toolchain


ROOT = Path(__file__).resolve().parents[1]
DRAWABLE = ROOT / "app" / "src" / "main" / "res" / "drawable-nodpi"
PREVIEW = ROOT / "qa" / "screenshots" / "slot_symbol_spin_blur_contact_sheet.png"

THEME_PREFIXES = ("vf", "rr", "nn", "pg", "op")
BYTE_EXACT_SPIN_BLUR_SYMBOLS = frozenset(
    {
        "nn_symbol_bar",
        "nn_symbol_cherry",
        "nn_symbol_credit",
        "nn_symbol_crown",
        "nn_symbol_star",
        "nn_symbol_v_wild",
        "op_symbol_anchor",
        "op_symbol_coin",
        "op_symbol_crown",
        "op_symbol_pearl",
        "op_symbol_shell",
        "op_symbol_starfish",
        "op_symbol_trident",
        "pg_symbol_ankh",
        "pg_symbol_coin",
        "pg_symbol_crown",
        "pg_symbol_lotus",
        "pg_symbol_scarab",
        "pg_symbol_sun",
        "pg_symbol_tablet",
        "rr_symbol_coin",
        "rr_symbol_column",
        "rr_symbol_crown",
        "rr_symbol_gem",
        "rr_symbol_laurel",
        "rr_symbol_lightning",
        "rr_symbol_shield",
        "rr_symbol_v_wild",
        "vf_symbol_bar",
        "vf_symbol_cherry",
        "vf_symbol_coin",
        "vf_symbol_crown",
        "vf_symbol_diamond",
        "vf_symbol_ruby",
        "vf_symbol_star",
        "vf_symbol_v_wild",
    }
)


def motion_blur_symbol(source: Image.Image) -> Image.Image:
    base = source.convert("RGBA")
    width, height = base.size
    blur = base.filter(ImageFilter.GaussianBlur(radius=2.2))
    canvas = Image.new("RGBA", base.size, (0, 0, 0, 0))

    offsets = (-30, -22, -15, -9, -4, 0, 4, 9, 15, 22, 30)
    weights = (22, 30, 44, 62, 82, 112, 82, 62, 44, 30, 22)
    for offset, alpha in zip(offsets, weights):
        layer = blur.copy()
        layer.putalpha(blur.getchannel("A").point(lambda value, a=alpha: min(255, value * a // 140)))
        canvas.alpha_composite(layer, (0, offset))

    center = base.filter(ImageFilter.GaussianBlur(radius=0.7))
    center.putalpha(base.getchannel("A").point(lambda value: min(255, value * 92 // 100)))
    canvas.alpha_composite(center)

    glow = base.filter(ImageFilter.GaussianBlur(radius=7.5))
    glow.putalpha(base.getchannel("A").point(lambda value: min(180, value * 38 // 100)))
    combined = Image.alpha_composite(glow, canvas)
    return combined.resize((width, height), Image.Resampling.LANCZOS)


def symbol_assets() -> list[Path]:
    return sorted(
        path for path in DRAWABLE.glob("*_symbol_*.webp")
        if (
            path.stem.split("_", 1)[0] in THEME_PREFIXES
            and path.stem in BYTE_EXACT_SPIN_BLUR_SYMBOLS
        )
    )


def preview_card(original: Image.Image, blurred: Image.Image, title: str) -> Image.Image:
    card = Image.new("RGBA", (320, 188), (12, 10, 22, 255))
    draw = ImageDraw.Draw(card)
    draw.text((12, 10), title[:34], fill=(238, 220, 178, 255))
    card.alpha_composite(original.resize((112, 112), Image.Resampling.LANCZOS), (32, 54))
    card.alpha_composite(blurred.resize((112, 112), Image.Resampling.LANCZOS), (176, 54))
    draw.text((50, 166), "crisp", fill=(156, 178, 220, 255))
    draw.text((194, 166), "spin blur", fill=(156, 178, 220, 255))
    return card


def main() -> None:
    verify_asset_toolchain()
    assets = symbol_assets()
    if not assets:
        raise SystemExit("No slot symbol assets found.")

    preview_rows = (len(assets) + 3) // 4
    preview = Image.new("RGBA", (320 * 4, 188 * preview_rows), (8, 7, 16, 255))
    for index, path in enumerate(assets):
        original = Image.open(path).convert("RGBA")
        blurred = motion_blur_symbol(original)
        out = path.with_name(f"{path.stem}_spin_blur.webp")
        blurred.save(out, quality=92, method=6)
        card = preview_card(original, blurred, path.stem)
        preview.alpha_composite(card, ((index % 4) * 320, (index // 4) * 188))

    PREVIEW.parent.mkdir(parents=True, exist_ok=True)
    preview.convert("RGB").save(PREVIEW, quality=94)
    print(f"wrote {len(assets)} spin blur assets")
    print(f"wrote {PREVIEW.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
