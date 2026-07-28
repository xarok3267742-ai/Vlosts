"""Version gate for byte-exact V Slot raster generation."""

from __future__ import annotations

import platform

import PIL
from PIL import features


EXPECTED = {
    "python": "3.9.6",
    "pillow": "11.3.0",
    "freetype": "2.13.3",
    "libwebp": "1.5.0",
}


def actual_versions() -> dict[str, str]:
    return {
        "python": platform.python_version(),
        "pillow": PIL.__version__,
        "freetype": features.version_module("freetype2") or "UNAVAILABLE",
        "libwebp": features.version_module("webp") or "UNAVAILABLE",
    }


def verify_asset_toolchain() -> None:
    actual = actual_versions()
    if actual != EXPECTED:
        raise RuntimeError(
            "Unsupported raster toolchain: "
            f"expected {EXPECTED}, got {actual}. "
            "Use the pinned asset-generation environment before regenerating media."
        )
