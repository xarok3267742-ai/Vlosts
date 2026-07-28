#!/usr/bin/env python3
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter

from vslot_asset_toolchain import verify_asset_toolchain


ROOT = Path(__file__).resolve().parents[1]
DRAWABLE = ROOT / "app" / "src" / "main" / "res" / "drawable-nodpi"


@dataclass(frozen=True)
class ChromeTheme:
    suffix: str
    dark: tuple[int, int, int]
    primary: tuple[int, int, int]
    secondary: tuple[int, int, int]
    accent: tuple[int, int, int]


THEMES = (
    ChromeTheme(
        suffix="neon",
        dark=(7, 10, 36),
        primary=(31, 225, 255),
        secondary=(255, 52, 187),
        accent=(255, 226, 86),
    ),
    ChromeTheme(
        suffix="pharaoh",
        dark=(38, 21, 9),
        primary=(255, 199, 62),
        secondary=(18, 206, 185),
        accent=(255, 119, 36),
    ),
    ChromeTheme(
        suffix="ocean",
        dark=(3, 27, 48),
        primary=(104, 229, 255),
        secondary=(184, 255, 246),
        accent=(255, 216, 96),
    ),
)

SOURCE_TO_OUTPUT_STEMS = {
    "slot_machine_frame_violet.webp": "slot_machine_frame",
    "slot_marquee_glass.webp": "slot_marquee_glass",
    "slot_cabinet_lights.webp": "slot_cabinet_lights",
    "slot_cabinet_chase_lights.webp": "slot_cabinet_chase_lights",
    "reel_depth_dividers.webp": "reel_depth_dividers",
    "reel_window_depth_mask.webp": "reel_window_depth_mask",
    "free_spins_mode_overlay_violet.webp": "free_spins_mode_overlay",
    "slot_spin_deck_glow_violet.webp": "slot_spin_deck_glow",
    "spin_button_ready_glow_violet.webp": "spin_button_ready_glow",
    "slot_paytable_dock_glow_violet.webp": "slot_paytable_dock_glow",
    "slot_control_console_backplane_violet.webp": "slot_control_console_backplane",
    "bet_panel.webp": "bet_panel",
    "slot_control_meter_glow.webp": "slot_control_meter_glow",
    "active_lines_badge.webp": "active_lines_badge",
    "free_spins_badge.webp": "free_spins_badge",
    "reel_cell_backdrop.webp": "reel_cell_backdrop",
}


def rgba(color: tuple[int, int, int], alpha: int = 255) -> tuple[int, int, int, int]:
    return color[0], color[1], color[2], alpha


def mix(a: tuple[int, int, int], b: tuple[int, int, int], t: float) -> tuple[int, int, int]:
    t = max(0.0, min(1.0, t))
    return tuple(int(a[i] * (1 - t) + b[i] * t) for i in range(3))


def theme_ramp(theme: ChromeTheme, luma: int) -> tuple[int, int, int]:
    t = luma / 255.0
    if t < 0.42:
        return mix(theme.dark, theme.primary, t / 0.42 * 0.78)
    if t < 0.78:
        return mix(theme.primary, theme.secondary, (t - 0.42) / 0.36 * 0.52)
    return mix(theme.secondary, theme.accent, (t - 0.78) / 0.22)


def colorize(source: Image.Image, theme: ChromeTheme, intensity: float = 0.84) -> Image.Image:
    src = source.convert("RGBA")
    out = Image.new("RGBA", src.size, (0, 0, 0, 0))
    src_px = src.load()
    out_px = out.load()
    for y in range(src.height):
        for x in range(src.width):
            r, g, b, a = src_px[x, y]
            if a == 0:
                continue
            luma = int(r * 0.299 + g * 0.587 + b * 0.114)
            themed = theme_ramp(theme, luma)
            preserve = 0.18 + (1.0 - intensity) * 0.45
            nr = int(themed[0] * intensity + r * preserve)
            ng = int(themed[1] * intensity + g * preserve)
            nb = int(themed[2] * intensity + b * preserve)
            if luma > 214:
                boost = (luma - 214) / 41.0
                nr = int(nr * (1 - boost * 0.34) + 255 * boost * 0.34)
                ng = int(ng * (1 - boost * 0.34) + 255 * boost * 0.34)
                nb = int(nb * (1 - boost * 0.34) + 255 * boost * 0.34)
            out_px[x, y] = (min(255, nr), min(255, ng), min(255, nb), a)
    return out


def add_theme_lighting(img: Image.Image, theme: ChromeTheme, name: str) -> Image.Image:
    width, height = img.size
    overlay = Image.new("RGBA", img.size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(overlay)

    if "slot_machine_frame" in name:
        draw.rounded_rectangle(
            (width * 0.035, height * 0.035, width * 0.965, height * 0.965),
            radius=max(18, width // 22),
            outline=rgba(theme.primary, 138),
            width=max(3, width // 150),
        )
        draw.arc(
            (width * 0.08, height * -0.04, width * 0.92, height * 0.5),
            18,
            164,
            fill=rgba(theme.accent, 112),
            width=max(2, width // 170),
        )
    elif "slot_control_console_backplane" in name or "bet_panel" in name:
        draw.rounded_rectangle(
            (width * 0.035, height * 0.18, width * 0.965, height * 0.88),
            radius=max(14, width // 28),
            outline=rgba(theme.primary, 122),
            width=max(3, width // 170),
        )
        draw.line((width * 0.12, height * 0.3, width * 0.88, height * 0.3), fill=rgba(theme.accent, 80), width=max(2, height // 42))
    elif "free_spins" in name:
        draw.rounded_rectangle(
            (width * 0.08, height * 0.2, width * 0.92, height * 0.82),
            radius=max(12, width // 18),
            outline=rgba(theme.accent, 130),
            width=max(3, width // 150),
        )
    else:
        draw.line((width * 0.12, height * 0.12, width * 0.88, height * 0.12), fill=rgba(theme.primary, 58), width=max(2, height // 50))
        draw.line((width * 0.16, height * 0.88, width * 0.84, height * 0.88), fill=rgba(theme.accent, 52), width=max(2, height // 58))

    glow = overlay.filter(ImageFilter.GaussianBlur(max(4, min(width, height) // 36)))
    img.alpha_composite(glow)
    img.alpha_composite(overlay)
    return img


def save_theme_asset(source_name: str, output_stem: str, theme: ChromeTheme) -> None:
    source = Image.open(DRAWABLE / source_name).convert("RGBA")
    output = colorize(source, theme)
    output = add_theme_lighting(output, theme, output_stem)
    output.save(DRAWABLE / f"{output_stem}_{theme.suffix}.webp", "WEBP", quality=94, method=6)


def main() -> None:
    verify_asset_toolchain()
    for theme in THEMES:
        for source_name, output_stem in SOURCE_TO_OUTPUT_STEMS.items():
            save_theme_asset(source_name, output_stem, theme)
            print(f"{output_stem}_{theme.suffix}.webp")


if __name__ == "__main__":
    main()
