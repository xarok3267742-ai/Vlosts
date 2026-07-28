#!/usr/bin/env python3
from pathlib import Path

from PIL import Image, ImageDraw


from vslot_asset_toolchain import verify_asset_toolchain

verify_asset_toolchain()

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "qa" / "source" / "vslot_daily_bonus_home_buttons_imagegen.png"
OUT_DIR = ROOT / "app" / "src" / "main" / "res" / "drawable-nodpi"
PREVIEW = ROOT / "qa" / "screenshots" / "daily_bonus_home_buttons_contact_sheet.png"

TARGET_SIZE = (1600, 276)
PANELS = {
    "daily_bonus_ready_imagegen.webp": (0.015, 0.075, 0.985, 0.365),
    "daily_bonus_wait_imagegen.webp": (0.015, 0.535, 0.985, 0.825),
}


def crop_panel(source: Image.Image, box_fractions: tuple[float, float, float, float]) -> Image.Image:
    width, height = source.size
    left, top, right, bottom = box_fractions
    box = (
        int(width * left),
        int(height * top),
        int(width * right),
        int(height * bottom),
    )
    return source.crop(box).resize(TARGET_SIZE, Image.Resampling.LANCZOS)


def main() -> None:
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    PREVIEW.parent.mkdir(parents=True, exist_ok=True)
    source = Image.open(SOURCE).convert("RGB")
    rendered: list[tuple[str, Image.Image]] = []

    for filename, box in PANELS.items():
        panel = crop_panel(source, box)
        panel.save(OUT_DIR / filename, quality=96, method=6)
        rendered.append((filename, panel))

    preview = Image.new("RGB", (TARGET_SIZE[0], TARGET_SIZE[1] * len(rendered) + 36 * len(rendered)), (10, 8, 18))
    draw = ImageDraw.Draw(preview)
    y = 0
    for filename, panel in rendered:
        draw.text((12, y + 10), filename, fill=(245, 238, 210))
        preview.paste(panel, (0, y + 36))
        y += TARGET_SIZE[1] + 36
    preview.save(PREVIEW, quality=95)

    for filename, panel in rendered:
        path = OUT_DIR / filename
        print(f"{filename}: {path.stat().st_size} bytes, {panel.size[0]}x{panel.size[1]}")
    print(f"preview: {PREVIEW}")


if __name__ == "__main__":
    main()
