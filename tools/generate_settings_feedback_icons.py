#!/usr/bin/env python3
"""Generate transparent bitmap icons for sound and haptic settings controls."""

from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter

from vslot_asset_toolchain import verify_asset_toolchain


ROOT = Path(__file__).resolve().parents[1]
OUTPUT_DIR = ROOT / "app/src/main/res/drawable-nodpi"
CANVAS = 512
TARGET = 256
GOLD = (255, 240, 168, 255)
CYAN = (101, 231, 255, 255)
MUTED = (184, 182, 200, 255)
OFF = (255, 123, 143, 255)


def canvas() -> tuple[Image.Image, ImageDraw.ImageDraw]:
    image = Image.new("RGBA", (CANVAS, CANVAS), (0, 0, 0, 0))
    return image, ImageDraw.Draw(image)


def glow(image: Image.Image, color: tuple[int, int, int, int]) -> Image.Image:
    alpha = image.getchannel("A").filter(ImageFilter.GaussianBlur(22))
    aura = Image.new("RGBA", image.size, color)
    aura.putalpha(alpha.point(lambda value: int(value * 0.34)))
    return Image.alpha_composite(aura, image)


def draw_speaker(enabled: bool) -> Image.Image:
    image, draw = canvas()
    primary = GOLD if enabled else MUTED
    draw.rounded_rectangle((90, 205, 174, 307), radius=16, fill=primary)
    draw.polygon([(166, 205), (280, 116), (280, 396), (166, 307)], fill=primary)
    if enabled:
        draw.arc((270, 155, 400, 357), start=-58, end=58, fill=CYAN, width=22)
        draw.arc((284, 104, 454, 408), start=-58, end=58, fill=CYAN, width=18)
    else:
        draw.line((310, 190, 420, 322), fill=OFF, width=28)
        draw.line((420, 190, 310, 322), fill=OFF, width=28)
    return glow(image, CYAN if enabled else OFF)


def draw_haptics(enabled: bool) -> Image.Image:
    image, draw = canvas()
    primary = GOLD if enabled else MUTED
    draw.rounded_rectangle((172, 78, 340, 434), radius=28, outline=primary, width=24)
    draw.rounded_rectangle((222, 382, 290, 398), radius=8, fill=CYAN if enabled else MUTED)
    if enabled:
        for x, length in ((130, 118), (88, 78), (382, 118), (424, 78)):
            top = (CANVAS - length) // 2
            draw.rounded_rectangle((x - 9, top, x + 9, top + length), radius=9, fill=CYAN)
    else:
        draw.line((118, 118, 394, 394), fill=OFF, width=30)
    return glow(image, CYAN if enabled else OFF)


def save(name: str, image: Image.Image) -> None:
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    target = image.resize((TARGET, TARGET), Image.Resampling.LANCZOS)
    target.save(OUTPUT_DIR / name, format="WEBP", lossless=True, method=6)
    print(f"generated {(OUTPUT_DIR / name).relative_to(ROOT)}")


def main() -> None:
    verify_asset_toolchain()
    save("settings_sound_on.webp", draw_speaker(enabled=True))
    save("settings_sound_off.webp", draw_speaker(enabled=False))
    save("settings_haptics_on.webp", draw_haptics(enabled=True))
    save("settings_haptics_off.webp", draw_haptics(enabled=False))


if __name__ == "__main__":
    main()
