from pathlib import Path

from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "qa" / "source" / "vslot_slam_stop_cue_imagegen.png"
OUT = ROOT / "app" / "src" / "main" / "res" / "drawable-nodpi" / "slam_stop_cue.webp"
PREVIEW = ROOT / "qa" / "screenshots" / "slam_stop_cue_contact_sheet.png"

TARGET_SIZE = (512, 512)


def remove_green_key(image: Image.Image) -> Image.Image:
    rgba = image.convert("RGBA")
    pixels = rgba.load()
    width, height = rgba.size
    for y in range(height):
        for x in range(width):
            r, g, b, a = pixels[x, y]
            green_distance = abs(r - 0) + abs(g - 255) + abs(b - 0)
            green_dominance = g - max(r, b)
            if green_distance <= 95 or (g >= 190 and green_dominance >= 80):
                alpha = 0
            elif g >= 145 and green_dominance >= 42:
                alpha = max(0, min(255, int(a * (green_distance - 42) / 165)))
            else:
                alpha = a
            if alpha == 0:
                pixels[x, y] = (0, 0, 0, 0)
            elif alpha != a:
                pixels[x, y] = (r, max(0, g - 35), b, alpha)
    return rgba


def alpha_crop(image: Image.Image, padding: int = 0) -> Image.Image:
    bbox = image.getchannel("A").getbbox()
    if bbox is None:
        return image
    left, top, right, bottom = bbox
    return image.crop(
        (
            max(0, left - padding),
            max(0, top - padding),
            min(image.width, right + padding),
            min(image.height, bottom + padding),
        )
    )


def square_pad(image: Image.Image, size: tuple[int, int]) -> Image.Image:
    cropped = alpha_crop(image, padding=18)
    cropped.thumbnail((size[0] - 24, size[1] - 24), Image.Resampling.LANCZOS)
    canvas = Image.new("RGBA", size, (0, 0, 0, 0))
    canvas.alpha_composite(cropped, ((size[0] - cropped.width) // 2, (size[1] - cropped.height) // 2))
    return canvas


def decontaminate_green_fringe(image: Image.Image) -> Image.Image:
    rgba = image.copy()
    pixels = rgba.load()
    width, height = rgba.size
    for y in range(height):
        for x in range(width):
            r, g, b, a = pixels[x, y]
            if a == 0:
                continue
            dominant = g - max(r, b)
            if dominant <= 8:
                continue
            if g >= 115 or a < 235:
                clean_g = max(r, b) + min(8, dominant // 4)
                clean_alpha = a
                if dominant >= 34 and a < 230:
                    clean_alpha = max(0, int(a * 0.7))
                pixels[x, y] = (r, clean_g, b, clean_alpha)
    return rgba


def open_asset(name: str) -> Image.Image:
    for folder in ("drawable-nodpi", "drawable"):
        root = ROOT / "app" / "src" / "main" / "res" / folder
        path = root / name
        if path.exists():
            return Image.open(path).convert("RGBA")
        for candidate in root.glob(f"{Path(name).stem}.*"):
            return Image.open(candidate).convert("RGBA")
    raise FileNotFoundError(name)


def labeled_card(title: str, image: Image.Image, size: tuple[int, int]) -> Image.Image:
    card = Image.new("RGBA", size, (12, 9, 22, 255))
    draw = ImageDraw.Draw(card)
    draw.text((24, 18), title, fill=(242, 220, 170, 255))
    fitted = image.copy()
    fitted.thumbnail((size[0] - 48, size[1] - 74), Image.Resampling.LANCZOS)
    card.alpha_composite(fitted, ((size[0] - fitted.width) // 2, 58))
    return card


def mock_spin_deck(cue: Image.Image, scale: float) -> Image.Image:
    canvas = Image.new("RGBA", (760, 300), (7, 5, 16, 255))
    deck = open_asset("slot_spin_deck_glow.webp").resize((760, 300), Image.Resampling.LANCZOS)
    spin = open_asset("spin_button_violet_default.webp").resize((360, 132), Image.Resampling.LANCZOS)
    impact = open_asset("spin_impact_flash.webp").resize((430, 170), Image.Resampling.LANCZOS)
    canvas.alpha_composite(deck)
    canvas.alpha_composite(spin, (200, 84))
    sized = cue.resize((int(140 * scale), int(140 * scale)), Image.Resampling.LANCZOS)
    canvas.alpha_composite(sized, (380 - sized.width // 2, 150 - sized.height // 2))
    soft = impact.copy()
    soft.putalpha(soft.getchannel("A").point(lambda value: min(128, value * 128 // 255)))
    canvas.alpha_composite(soft, (165, 65))
    return canvas


def main() -> None:
    source = Image.open(SOURCE)
    cue = decontaminate_green_fringe(square_pad(remove_green_key(source), TARGET_SIZE))
    OUT.parent.mkdir(parents=True, exist_ok=True)
    cue.save(OUT, "WEBP", quality=95, method=6)

    preview = Image.new("RGBA", (1600, 900), (9, 7, 18, 255))
    preview.alpha_composite(labeled_card("imagegen source on chroma key", source.convert("RGBA"), (520, 520)), (0, 0))
    preview.alpha_composite(labeled_card("transparent slam stop cue", cue, (520, 520)), (540, 0))
    preview.alpha_composite(labeled_card("spin deck mock 1x", mock_spin_deck(cue, 1.0), (800, 360)), (0, 540))
    preview.alpha_composite(labeled_card("spin deck mock compact", mock_spin_deck(cue, 0.76), (800, 360)), (800, 540))
    PREVIEW.parent.mkdir(parents=True, exist_ok=True)
    preview.convert("RGB").save(PREVIEW, quality=94)

    print(f"wrote {OUT.relative_to(ROOT)} {OUT.stat().st_size} bytes")
    print(f"wrote {PREVIEW.relative_to(ROOT)}")


if __name__ == "__main__":
    raise SystemExit(
        "NONCANONICAL_HISTORICAL_SLICER: retained for provenance only; "
        "running it would overwrite reviewed application artwork"
    )
