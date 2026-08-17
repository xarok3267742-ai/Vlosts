#!/usr/bin/env python3
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter

from vslot_asset_toolchain import verify_asset_toolchain


ROOT = Path(__file__).resolve().parents[1]
DRAWABLE_NODPI = ROOT / "app" / "src" / "main" / "res" / "drawable-nodpi"
DRAWABLE = ROOT / "app" / "src" / "main" / "res" / "drawable"


@dataclass(frozen=True)
class ButtonTheme:
    suffix: str
    dark: tuple[int, int, int]
    primary: tuple[int, int, int]
    secondary: tuple[int, int, int]
    accent: tuple[int, int, int]


THEMES = (
    ButtonTheme("neon", (8, 12, 40), (30, 229, 255), (255, 48, 190), (255, 231, 82)),
    ButtonTheme("pharaoh", (44, 24, 10), (255, 199, 62), (18, 205, 185), (255, 119, 36)),
    ButtonTheme("ocean", (4, 27, 48), (104, 229, 255), (187, 255, 247), (255, 216, 96)),
)


ASSET_SOURCES = {
    "spin_button_{suffix}_default": "spin_button_violet_default.webp",
    "spin_button_{suffix}_pressed": "spin_button_violet_pressed.webp",
    "spin_button_{suffix}_disabled": "spin_button_violet_disabled.webp",
    "spin_button_{suffix}_free_spins_default": "spin_button_violet_free_spins_default.png",
    "spin_button_{suffix}_free_spins_pressed": "spin_button_violet_free_spins_pressed.png",
    "spin_button_{suffix}_free_spins_disabled": "spin_button_violet_free_spins_disabled.png",
    "btn_autospin_{suffix}_default": "btn_autospin_default.webp",
    "btn_autospin_{suffix}_pressed": "btn_autospin_pressed.webp",
    "btn_autospin_{suffix}_disabled": "btn_autospin_disabled.webp",
    "btn_autospin_{suffix}_active": "btn_autospin_active.webp",
    "btn_autospin_{suffix}_active_pressed": "btn_autospin_active_pressed.webp",
    "btn_bet_minus_{suffix}": "btn_bet_minus.webp",
    "btn_bet_minus_{suffix}_pressed": "btn_bet_minus_pressed.webp",
    "btn_bet_minus_{suffix}_disabled": "btn_bet_minus_disabled.webp",
    "btn_bet_plus_{suffix}": "btn_bet_plus.webp",
    "btn_bet_plus_{suffix}_pressed": "btn_bet_plus_pressed.webp",
    "btn_bet_plus_{suffix}_disabled": "btn_bet_plus_disabled.webp",
    "btn_max_lines_{suffix}_default": "btn_max_lines_default.webp",
    "btn_max_lines_{suffix}_pressed": "btn_max_lines_pressed.webp",
    "btn_max_lines_{suffix}_disabled": "btn_max_lines_disabled.webp",
    "paytable_button_{suffix}": "paytable_button.webp",
    "label_paytable_button_{suffix}": "label_paytable_button.webp",
    "auto_spin_active_halo_{suffix}": "auto_spin_active_halo.webp",
}


SELECTORS = {
    "spin_button_{suffix}_selector": (
        "spin_button_{suffix}_disabled",
        "spin_button_{suffix}_pressed",
        "spin_button_{suffix}_default",
    ),
    "spin_button_{suffix}_free_spins_selector": (
        "spin_button_{suffix}_free_spins_disabled",
        "spin_button_{suffix}_free_spins_pressed",
        "spin_button_{suffix}_free_spins_default",
    ),
    "btn_autospin_{suffix}_selector": (
        "btn_autospin_{suffix}_disabled",
        "btn_autospin_{suffix}_pressed",
        "btn_autospin_{suffix}_default",
    ),
    "btn_autospin_{suffix}_active_selector": (
        "btn_autospin_{suffix}_disabled",
        "btn_autospin_{suffix}_active_pressed",
        "btn_autospin_{suffix}_active",
    ),
    "btn_bet_minus_{suffix}_selector": (
        "btn_bet_minus_{suffix}_disabled",
        "btn_bet_minus_{suffix}_pressed",
        "btn_bet_minus_{suffix}",
    ),
    "btn_bet_plus_{suffix}_selector": (
        "btn_bet_plus_{suffix}_disabled",
        "btn_bet_plus_{suffix}_pressed",
        "btn_bet_plus_{suffix}",
    ),
    "btn_max_lines_{suffix}_selector": (
        "btn_max_lines_{suffix}_disabled",
        "btn_max_lines_{suffix}_pressed",
        "btn_max_lines_{suffix}_default",
    ),
}


def rgba(color: tuple[int, int, int], alpha: int = 255) -> tuple[int, int, int, int]:
    return color[0], color[1], color[2], alpha


def mix(a: tuple[int, int, int], b: tuple[int, int, int], t: float) -> tuple[int, int, int]:
    t = max(0.0, min(1.0, t))
    return tuple(int(a[i] * (1 - t) + b[i] * t) for i in range(3))


def ramp(theme: ButtonTheme, luma: int) -> tuple[int, int, int]:
    t = luma / 255.0
    if t < 0.34:
        return mix(theme.dark, theme.primary, t / 0.34 * 0.55)
    if t < 0.72:
        return mix(theme.primary, theme.secondary, (t - 0.34) / 0.38)
    return mix(theme.secondary, theme.accent, (t - 0.72) / 0.28)


def colorize(source: Image.Image, theme: ButtonTheme, disabled: bool = False) -> Image.Image:
    source = source.convert("RGBA")
    out = Image.new("RGBA", source.size, (0, 0, 0, 0))
    src_px = source.load()
    out_px = out.load()
    for y in range(source.height):
        for x in range(source.width):
            r, g, b, a = src_px[x, y]
            if a == 0:
                continue
            luma = int(r * 0.299 + g * 0.587 + b * 0.114)
            themed = ramp(theme, luma)
            if luma > 205:
                keep = 0.74 if not disabled else 0.58
            elif luma > 164:
                keep = 0.38 if not disabled else 0.5
            else:
                keep = 0.16 if not disabled else 0.42
            nr = int(themed[0] * (1 - keep) + r * keep)
            ng = int(themed[1] * (1 - keep) + g * keep)
            nb = int(themed[2] * (1 - keep) + b * keep)
            if disabled:
                gray = int(nr * 0.299 + ng * 0.587 + nb * 0.114)
                nr = int(gray * 0.66 + theme.primary[0] * 0.16)
                ng = int(gray * 0.66 + theme.primary[1] * 0.16)
                nb = int(gray * 0.66 + theme.primary[2] * 0.16)
                a = int(a * 0.86)
            out_px[x, y] = (min(255, nr), min(255, ng), min(255, nb), a)
    return out


def add_button_polish(img: Image.Image, theme: ButtonTheme, name: str) -> Image.Image:
    width, height = img.size
    overlay = Image.new("RGBA", img.size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(overlay)
    line_w = max(2, min(width, height) // 44)

    if name.startswith("spin_button"):
        draw.rounded_rectangle(
            (width * 0.055, height * 0.14, width * 0.945, height * 0.86),
            radius=max(18, height // 3),
            outline=rgba(theme.primary, 104),
            width=line_w,
        )
        draw.line(
            (width * 0.16, height * 0.22, width * 0.84, height * 0.22),
            fill=rgba(theme.secondary, 70),
            width=line_w,
        )
        draw.line(
            (width * 0.2, height * 0.82, width * 0.8, height * 0.82),
            fill=rgba(theme.accent, 72),
            width=line_w,
        )
    else:
        draw.ellipse(
            (width * 0.12, height * 0.12, width * 0.88, height * 0.88),
            outline=rgba(theme.primary, 96),
            width=line_w,
        )
        draw.arc(
            (width * 0.2, height * 0.2, width * 0.8, height * 0.8),
            205,
            335,
            fill=rgba(theme.accent, 90),
            width=line_w,
        )

    glow = overlay.filter(ImageFilter.GaussianBlur(max(3, min(width, height) // 18)))
    img.alpha_composite(glow)
    img.alpha_composite(overlay)
    return img


def save_asset(output_stem: str, source_name: str, theme: ButtonTheme) -> None:
    disabled = output_stem.endswith("_disabled")
    img = Image.open(DRAWABLE_NODPI / source_name).convert("RGBA")
    out = add_button_polish(colorize(img, theme, disabled), theme, output_stem)
    out.save(DRAWABLE_NODPI / f"{output_stem}.webp", "WEBP", quality=94, method=6)


def write_selector(selector_stem: str, states: tuple[str, str, str], suffix: str) -> None:
    disabled, pressed, normal = (state.format(suffix=suffix) for state in states)
    selector_name = selector_stem.format(suffix=suffix)
    (DRAWABLE / f"{selector_name}.xml").write_text(
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
        "<selector xmlns:android=\"http://schemas.android.com/apk/res/android\">\n"
        f"    <item android:state_enabled=\"false\" android:drawable=\"@drawable/{disabled}\" />\n"
        f"    <item android:state_pressed=\"true\" android:drawable=\"@drawable/{pressed}\" />\n"
        f"    <item android:drawable=\"@drawable/{normal}\" />\n"
        "</selector>\n",
        encoding="utf-8",
    )


def main() -> None:
    verify_asset_toolchain()
    for theme in THEMES:
        for output_template, source_name in ASSET_SOURCES.items():
            output_stem = output_template.format(suffix=theme.suffix)
            save_asset(output_stem, source_name, theme)
            print(f"{output_stem}.webp")
        for selector_template, states in SELECTORS.items():
            write_selector(selector_template, states, theme.suffix)
            print(f"{selector_template.format(suffix=theme.suffix)}.xml")


if __name__ == "__main__":
    main()
