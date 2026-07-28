#!/usr/bin/env python3
"""Deterministically remove the retained app-logo chroma background."""

from __future__ import annotations

import argparse
import hashlib
import json
from io import BytesIO
from pathlib import Path
from statistics import median

from PIL import Image

from vslot_asset_toolchain import verify_asset_toolchain


ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / "qa/source/vslot_app_logo_mark_chroma_imagegen.png"
OUTPUT = ROOT / "app/src/main/res/drawable-nodpi/app_logo_mark_v2.png"
MANIFEST = ROOT / "qa/source/vslot_app_logo_mark_export.json"
TRANSPARENT_THRESHOLD = 12.0
OPAQUE_THRESHOLD = 220.0
ALPHA_NOISE_FLOOR = 8


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def sha256_file(path: Path) -> str:
    return sha256_bytes(path.read_bytes())


def border_key(image: Image.Image) -> tuple[int, int, int]:
    width, height = image.size
    pixels = image.load()
    band = max(1, min(width, height, 6))
    step = max(1, min(width, height) // 256)
    samples: list[tuple[int, int, int]] = []
    for x in range(0, width, step):
        for y in range(band):
            samples.append(pixels[x, y][:3])
            samples.append(pixels[x, height - 1 - y][:3])
    for y in range(0, height, step):
        for x in range(band):
            samples.append(pixels[x, y][:3])
            samples.append(pixels[width - 1 - x, y][:3])
    return tuple(
        int(round(median(sample[channel] for sample in samples)))
        for channel in range(3)
    )


def clamp_channel(value: float) -> int:
    return max(0, min(255, int(round(value))))


def soft_alpha(distance: int) -> int:
    if distance <= TRANSPARENT_THRESHOLD:
        return 0
    if distance >= OPAQUE_THRESHOLD:
        return 255
    ratio = (distance - TRANSPARENT_THRESHOLD) / (
        OPAQUE_THRESHOLD - TRANSPARENT_THRESHOLD
    )
    smooth = ratio * ratio * (3.0 - 2.0 * ratio)
    return clamp_channel(255.0 * smooth)


def green_dominance_alpha(rgb: tuple[int, int, int], key: tuple[int, int, int]) -> int:
    non_green = max(rgb[0], rgb[2])
    dominance = float(rgb[1] - non_green)
    if dominance <= 0:
        return 255
    denominator = max(1.0, float(max(key)) - non_green)
    return clamp_channel((1.0 - min(1.0, dominance / denominator)) * 255.0)


def render() -> tuple[bytes, tuple[int, int, int]]:
    with Image.open(SOURCE) as source:
        output = source.convert("RGBA")
    key = border_key(output)
    pixels = output.load()
    for y in range(output.height):
        for x in range(output.width):
            red, green, blue, _ = pixels[x, y]
            rgb = (red, green, blue)
            distance = max(abs(rgb[channel] - key[channel]) for channel in range(3))
            key_like = distance <= 32 or green - max(red, blue) >= 16
            alpha = min(soft_alpha(distance), green_dominance_alpha(rgb, key)) if key_like else 255
            if 0 < alpha <= ALPHA_NOISE_FLOOR:
                alpha = 0
            if alpha == 0:
                pixels[x, y] = (0, 0, 0, 0)
                continue
            if key_like and alpha < 252:
                green = min(green, max(0, max(red, blue) - 1))
            pixels[x, y] = (red, green, blue, alpha)
    encoded = BytesIO()
    output.save(encoded, format="PNG")
    return encoded.getvalue(), key


def manifest_bytes(output_bytes: bytes, key: tuple[int, int, int]) -> bytes:
    document = {
        "schema_version": 1,
        "exporter": "tools/export_app_logo_mark.py",
        "exporter_sha256": sha256_file(Path(__file__)),
        "source": SOURCE.relative_to(ROOT).as_posix(),
        "source_sha256": sha256_file(SOURCE),
        "output": OUTPUT.relative_to(ROOT).as_posix(),
        "output_sha256": sha256_bytes(output_bytes),
        "sampled_key_rgb": list(key),
        "transparent_threshold": TRANSPARENT_THRESHOLD,
        "opaque_threshold": OPAQUE_THRESHOLD,
        "despill": True,
    }
    return (json.dumps(document, ensure_ascii=True, indent=2) + "\n").encode("utf-8")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    verify_asset_toolchain()
    output_bytes, key = render()
    expected_manifest = manifest_bytes(output_bytes, key)
    stale = []
    if not OUTPUT.is_file() or OUTPUT.read_bytes() != output_bytes:
        stale.append(OUTPUT)
    if not MANIFEST.is_file() or MANIFEST.read_bytes() != expected_manifest:
        stale.append(MANIFEST)
    if args.check:
        if stale:
            for path in stale:
                print(f"Stale app-logo export: {path.relative_to(ROOT)}")
            print("Run python3 tools/export_app_logo_mark.py")
            return 1
        print("Transparent app-logo export is current.")
        return 0
    OUTPUT.write_bytes(output_bytes)
    MANIFEST.write_bytes(expected_manifest)
    print(f"Wrote {OUTPUT.relative_to(ROOT)}")
    print(f"Wrote {MANIFEST.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
