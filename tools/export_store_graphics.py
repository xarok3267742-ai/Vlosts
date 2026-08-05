#!/usr/bin/env python3
"""Deterministically export V Slot store and adaptive-icon raster assets."""

from __future__ import annotations

import argparse
import hashlib
import json
from io import BytesIO
from pathlib import Path

from PIL import Image, ImageChops, ImageFilter, PngImagePlugin

from vslot_asset_toolchain import verify_asset_toolchain


ROOT = Path(__file__).resolve().parent.parent
ICON_MASTER = ROOT / "docs/store/assets/v-slot-icon-master-v2.png"
FEATURE_MASTER = ROOT / "docs/store/assets/v-slot-feature-graphic-master-v1.png"
OUTPUTS = {
    ROOT / "docs/store/assets/v-slot-icon-512-v2.png": "store_icon",
    ROOT / "docs/store/assets/v-slot-feature-graphic-1024x500-v1.png": "feature_graphic",
    ROOT / "app/src/main/res/drawable-nodpi/app_icon_art_v2.png": "adaptive_icon_background",
    ROOT / "app/src/main/res/drawable-nodpi/app_icon_foreground_v2.png": "adaptive_icon_foreground",
}
MANIFEST = ROOT / "docs/store/assets/store-graphics-export-manifest.json"


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def sha256_file(path: Path) -> str:
    return sha256_bytes(path.read_bytes())


def png_bytes(image: Image.Image) -> bytes:
    output = BytesIO()
    png_info = PngImagePlugin.PngInfo()
    png_info.add(b"sRGB", b"\x00")
    image.save(
        output,
        format="PNG",
        optimize=False,
        compress_level=9,
        pnginfo=png_info,
    )
    return output.getvalue()


def render(kind: str) -> bytes:
    if kind == "store_icon":
        master = Image.open(ICON_MASTER).convert("RGB")
        icon = master.resize((512, 512), Image.Resampling.LANCZOS).convert("RGBA")
        return png_bytes(icon)
    if kind == "feature_graphic":
        master = Image.open(FEATURE_MASTER).convert("RGB")
        feature = master.resize((1024, 500), Image.Resampling.LANCZOS)
        return png_bytes(feature)
    if kind == "adaptive_icon_background":
        master = Image.open(ICON_MASTER).convert("RGB")
        blurred = master.resize((432, 432), Image.Resampling.LANCZOS).filter(
            ImageFilter.GaussianBlur(radius=28)
        )
        background = Image.blend(
            blurred,
            Image.new("RGB", blurred.size, (2, 3, 14)),
            0.68,
        )
        return png_bytes(background)
    if kind == "adaptive_icon_foreground":
        master = Image.open(ICON_MASTER).convert("RGB")
        mark = master.resize((220, 220), Image.Resampling.LANCZOS)
        red, green, blue = mark.split()
        intensity = ImageChops.lighter(red, ImageChops.lighter(green, blue))
        alpha = intensity.point(
            lambda value: 0
            if value <= 18
            else 255
            if value >= 42
            else round((value - 18) * 255 / 24)
        )
        mark = mark.convert("RGBA")
        mark.putalpha(alpha)
        foreground = Image.new("RGBA", (432, 432), (0, 0, 0, 0))
        foreground.alpha_composite(mark, (106, 97))
        return png_bytes(foreground)
    raise ValueError(f"Unsupported export kind: {kind}")


def manifest_bytes(rendered: dict[Path, bytes]) -> bytes:
    entries = []
    for output, kind in sorted(
        OUTPUTS.items(), key=lambda item: item[0].relative_to(ROOT).as_posix()
    ):
        source = FEATURE_MASTER if kind == "feature_graphic" else ICON_MASTER
        entries.append(
            {
                "kind": kind,
                "source": source.relative_to(ROOT).as_posix(),
                "source_sha256": sha256_file(source),
                "output": output.relative_to(ROOT).as_posix(),
                "output_sha256": sha256_bytes(rendered[output]),
            }
        )
    document = {
        "schema_version": 1,
        "exporter": "tools/export_store_graphics.py",
        "exporter_sha256": sha256_file(Path(__file__)),
        "resampling": "Pillow LANCZOS",
        "entries": entries,
    }
    return (json.dumps(document, ensure_ascii=True, indent=2) + "\n").encode("utf-8")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    verify_asset_toolchain()
    rendered = {output: render(kind) for output, kind in OUTPUTS.items()}
    expected_manifest = manifest_bytes(rendered)
    stale: list[Path] = []
    for output, expected in rendered.items():
        if args.check:
            if not output.is_file() or output.read_bytes() != expected:
                stale.append(output)
        else:
            output.parent.mkdir(parents=True, exist_ok=True)
            output.write_bytes(expected)
            print(f"Wrote {output.relative_to(ROOT)}")
    if args.check:
        if not MANIFEST.is_file() or MANIFEST.read_bytes() != expected_manifest:
            stale.append(MANIFEST)
    else:
        MANIFEST.write_bytes(expected_manifest)
        print(f"Wrote {MANIFEST.relative_to(ROOT)}")
    if stale:
        for output in stale:
            print(f"Stale store export: {output.relative_to(ROOT)}")
        print("Run python3 tools/export_store_graphics.py")
        return 1
    if args.check:
        print("Store and adaptive-icon exports are current.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
