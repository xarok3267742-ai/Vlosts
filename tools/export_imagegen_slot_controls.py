#!/usr/bin/env python3
"""Export the canonical imagegen slot-control kit into Android resources."""

from __future__ import annotations

from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter

from vslot_asset_toolchain import verify_asset_toolchain


ROOT = Path(__file__).resolve().parents[1]
SOURCE_DIR = ROOT / "qa" / "source" / "vslot-controls"
OUTPUT_DIR = ROOT / "app" / "src" / "main" / "res" / "drawable-nodpi"

EXPORTS = {
    "btn_autospin_default.webp": "vslot_btn_auto.png",
    "btn_autospin_pressed.webp": "vslot_btn_auto_pressed.png",
    "btn_autospin_disabled.webp": "vslot_btn_auto_disabled.png",
    "btn_autospin_active.webp": "vslot_btn_auto.png",
    "btn_autospin_active_pressed.webp": "vslot_btn_auto_pressed.png",
    "btn_bet_minus.webp": "vslot_btn_minus.png",
    "btn_bet_minus_pressed.webp": "vslot_btn_minus_pressed.png",
    "btn_bet_minus_disabled.webp": "vslot_btn_minus_disabled.png",
    "btn_bet_plus.webp": "vslot_btn_plus.png",
    "btn_bet_plus_pressed.webp": "vslot_btn_plus_pressed.png",
    "btn_bet_plus_disabled.webp": "vslot_btn_plus_disabled.png",
    "btn_max_lines_default.webp": "vslot_btn_max.png",
    "btn_max_lines_pressed.webp": "vslot_btn_max_pressed.png",
    "btn_max_lines_disabled.webp": "vslot_btn_max_disabled.png",
    "btn_back_default.webp": "vslot_btn_back.png",
    "btn_back_pressed.webp": "vslot_btn_back_pressed.png",
    "paytable_button.webp": "vslot_btn_paytable.png",
    "spin_button_violet_default.webp": "vslot_btn_spin.png",
    "spin_button_violet_pressed.webp": "vslot_btn_spin_pressed.png",
    "spin_button_violet_disabled.webp": "vslot_btn_spin_disabled.png",
    "spin_button_violet_free_spins_default.png": "vslot_btn_spin.png",
    "spin_button_violet_free_spins_pressed.png": "vslot_btn_spin_pressed.png",
    "spin_button_violet_free_spins_disabled.png": "vslot_btn_spin_disabled.png",
}

# Reviewed against the canonical PNG source. This large gradient-heavy control
# uses the same high-quality lossy WebP profile as the themed button exporter.
HIGH_QUALITY_LOSSY_WEBP_OUTPUTS = frozenset({"spin_button_violet_default.webp"})
HIGH_QUALITY_LOSSY_WEBP_QUALITY = 94


def add_state_treatment(image: Image.Image, output_name: str) -> Image.Image:
    overlay = Image.new("RGBA", image.size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(overlay)
    width, height = image.size
    if "autospin_active" in output_name:
        inset = max(24, min(width, height) // 12)
        line_width = max(5, min(width, height) // 38)
        draw.ellipse(
            (inset, inset, width - inset, height - inset),
            outline=(74, 237, 255, 220),
            width=line_width,
        )
    elif "free_spins" in output_name:
        x_inset = max(58, width // 12)
        y_inset = max(54, height // 6)
        line_width = max(6, height // 40)
        draw.rounded_rectangle(
            (x_inset, y_inset, width - x_inset, height - y_inset),
            radius=max(34, height // 3),
            outline=(102, 255, 201, 220),
            width=line_width,
        )
    else:
        return image
    glow = overlay.filter(ImageFilter.GaussianBlur(max(7, min(width, height) // 25)))
    image.alpha_composite(glow)
    image.alpha_composite(overlay)
    return image


def export(output_name: str, source_name: str) -> None:
    source_path = SOURCE_DIR / source_name
    output_path = OUTPUT_DIR / output_name
    with Image.open(source_path) as source:
        image = add_state_treatment(source.convert("RGBA"), output_name)
        if output_path.suffix == ".webp":
            if output_name in HIGH_QUALITY_LOSSY_WEBP_OUTPUTS:
                image.save(
                    output_path,
                    "WEBP",
                    quality=HIGH_QUALITY_LOSSY_WEBP_QUALITY,
                    method=6,
                )
            else:
                image.save(output_path, "WEBP", lossless=True, method=6)
        elif output_path.suffix == ".png":
            image.save(output_path, "PNG", optimize=True)
        else:
            raise RuntimeError(f"Unsupported control export format: {output_path.suffix}")


def main() -> None:
    verify_asset_toolchain()
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    for output_name, source_name in EXPORTS.items():
        export(output_name, source_name)
        print(output_name)


if __name__ == "__main__":
    main()
