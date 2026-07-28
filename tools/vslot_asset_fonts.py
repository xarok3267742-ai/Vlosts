"""Pinned open-font loader for deterministic V Slot raster generators."""

from __future__ import annotations

import hashlib
from pathlib import Path

from PIL import ImageFont

from vslot_asset_toolchain import verify_asset_toolchain


ROOT = Path(__file__).resolve().parent.parent
FONT_PATH = ROOT / "tools/fonts/noto-sans/NotoSans[wdth,wght].ttf"
FONT_SHA256 = "bfb7bb691513f12e734dc346c03a03f784912432d7e3fa8e56efcf906fe86b3d"
_verified = False


def verify_font() -> None:
    global _verified
    if _verified:
        return
    verify_asset_toolchain()
    if not FONT_PATH.is_file():
        raise RuntimeError(f"Required pinned font is missing: {FONT_PATH}")
    actual = hashlib.sha256(FONT_PATH.read_bytes()).hexdigest()
    if actual != FONT_SHA256:
        raise RuntimeError(
            f"Pinned Noto Sans SHA-256 mismatch: expected {FONT_SHA256}, got {actual}"
        )
    _verified = True


def load_font(
    size: int,
    *,
    weight: int = 700,
    width: int = 100,
) -> ImageFont.FreeTypeFont:
    if not 100 <= weight <= 900:
        raise ValueError("Noto Sans weight must be in 100..900")
    if not 62 <= width <= 100:
        raise ValueError("Noto Sans width must be in 62..100")
    verify_font()
    selected = ImageFont.truetype(str(FONT_PATH), size=size)
    selected.set_variation_by_axes([weight, width])
    return selected
