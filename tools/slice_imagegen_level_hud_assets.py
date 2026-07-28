#!/usr/bin/env python3
from __future__ import annotations

from collections import deque
from pathlib import Path

from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "qa/source/vslot_level_hud_imagegen.png"
OUT_DIR = ROOT / "app/src/main/res/drawable-nodpi"
PREVIEW = ROOT / "qa/screenshots/level_hud_imagegen_contact_sheet.png"


ASSETS = {
    "level_progress_panel.webp": {
        "box": (100, 34, 1682, 356),
        "size": (560, 160),
        "mode": "transparent",
    },
    "level_progress_track_glow.webp": {
        "box": (294, 156, 1492, 270),
        "size": (360, 64),
        "mode": "transparent",
    },
    "level_progress_fill.webp": {
        "box": (166, 402, 1516, 476),
        "size": (420, 42),
        "mode": "transparent",
    },
    "level_progress_milestones.webp": {
        "box": (208, 532, 1546, 622),
        "size": (420, 64),
        "mode": "transparent",
    },
    "home_xp_readout_plate.webp": {
        "box": (376, 646, 900, 834),
        "size": (300, 108),
        "mode": "transparent",
    },
    "level_progress_pulse.webp": {
        "box": (1104, 640, 1368, 856),
        "size": (160, 160),
        "mode": "transparent",
    },
    "level_progress_cap.webp": {
        "box": (1104, 640, 1368, 856),
        "size": (112, 112),
        "mode": "transparent",
    },
}


def is_edge_background(pixel: tuple[int, int, int, int]) -> bool:
    r, g, b, _ = pixel
    bright = max(r, g, b)
    spread = bright - min(r, g, b)
    return bright >= 214 and spread <= 22


def remove_edge_checkerboard(image: Image.Image) -> Image.Image:
    rgba = image.convert("RGBA")
    pixels = rgba.load()
    width, height = rgba.size
    seen: set[tuple[int, int]] = set()
    queue: deque[tuple[int, int]] = deque()

    def maybe_add(x: int, y: int) -> None:
        if (x, y) in seen:
            return
        seen.add((x, y))
        if is_edge_background(pixels[x, y]):
            queue.append((x, y))

    for x in range(width):
        maybe_add(x, 0)
        maybe_add(x, height - 1)
    for y in range(height):
        maybe_add(0, y)
        maybe_add(width - 1, y)

    transparent = set(queue)
    while queue:
        x, y = queue.popleft()
        for nx, ny in ((x - 1, y), (x + 1, y), (x, y - 1), (x, y + 1)):
            if nx < 0 or nx >= width or ny < 0 or ny >= height or (nx, ny) in seen:
                continue
            seen.add((nx, ny))
            if is_edge_background(pixels[nx, ny]):
                transparent.add((nx, ny))
                queue.append((nx, ny))

    for x, y in transparent:
        r, g, b, _ = pixels[x, y]
        pixels[x, y] = (r, g, b, 0)

    for y in range(height):
        for x in range(width):
            r, g, b, a = pixels[x, y]
            bright = max(r, g, b)
            spread = bright - min(r, g, b)
            if a > 0 and bright >= 230 and spread <= 16:
                pixels[x, y] = (r, g, b, 0)

    return rgba


def build_assets() -> list[tuple[str, Image.Image]]:
    source = Image.open(SOURCE)
    rendered: list[tuple[str, Image.Image]] = []
    for filename, spec in ASSETS.items():
        crop = source.crop(spec["box"])
        if spec["mode"] == "transparent":
            crop = remove_edge_checkerboard(crop)
        asset = crop.resize(spec["size"], Image.Resampling.LANCZOS)
        out = OUT_DIR / filename
        asset.save(out, "WEBP", quality=96, method=6)
        rendered.append((filename, asset))
    return rendered


def save_preview(rendered: list[tuple[str, Image.Image]]) -> None:
    PREVIEW.parent.mkdir(parents=True, exist_ok=True)
    cell_w, cell_h = 360, 150
    sheet = Image.new("RGB", (cell_w * 2, cell_h * 4), (16, 12, 34))
    draw = ImageDraw.Draw(sheet)
    for index, (name, image) in enumerate(rendered):
        col = index % 2
        row = index // 2
        x = col * cell_w
        y = row * cell_h
        draw.rectangle((x + 8, y + 8, x + cell_w - 8, y + cell_h - 8), fill=(28, 22, 54))
        preview = image.copy()
        preview.thumbnail((cell_w - 34, cell_h - 46), Image.Resampling.LANCZOS)
        px = x + (cell_w - preview.width) // 2
        py = y + 16 + (cell_h - 46 - preview.height) // 2
        sheet.paste(preview, (px, py), preview)
        draw.text((x + 16, y + cell_h - 24), name, fill=(225, 218, 255))
    sheet.save(PREVIEW)


def main() -> None:
    if not SOURCE.exists():
        raise SystemExit(f"Missing imagegen source: {SOURCE}")
    rendered = build_assets()
    save_preview(rendered)
    for name, image in rendered:
        out = OUT_DIR / name
        print(f"{name}\t{out.stat().st_size}\t{image.width}x{image.height}")
    print(f"preview\t{PREVIEW}")


if __name__ == "__main__":
    raise SystemExit(
        "NONCANONICAL_HISTORICAL_SLICER: retained for provenance only; "
        "running it would overwrite reviewed application artwork"
    )
