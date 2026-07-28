#!/usr/bin/env python3
from __future__ import annotations

from dataclasses import dataclass
from math import cos, pi, sin
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageFont

from vslot_asset_fonts import load_font, verify_font
from vslot_asset_toolchain import verify_asset_toolchain

ROOT = Path(__file__).resolve().parents[1]
DRAWABLE = ROOT / "app" / "src" / "main" / "res" / "drawable-nodpi"


@dataclass(frozen=True)
class Palette:
    prefix: str
    card_name: str
    title: str
    primary: tuple[int, int, int]
    secondary: tuple[int, int, int]
    accent: tuple[int, int, int]
    dark: tuple[int, int, int]


PALETTES = {
    "neon": Palette(
        prefix="nn",
        card_name="slot_card_neon_nights",
        title="НЕОНОВЫЕ НОЧИ",
        primary=(31, 225, 255),
        secondary=(255, 52, 187),
        accent=(255, 222, 87),
        dark=(8, 10, 34),
    ),
    "pharaoh": Palette(
        prefix="pg",
        card_name="slot_card_pharaoh_gold",
        title="ЗОЛОТО ФАРАОНА",
        primary=(255, 199, 62),
        secondary=(17, 207, 187),
        accent=(255, 126, 41),
        dark=(39, 20, 10),
    ),
    "ocean": Palette(
        prefix="op",
        card_name="slot_card_ocean_pearl",
        title="ОКЕАНСКАЯ ЖЕМЧУЖИНА",
        primary=(104, 229, 255),
        secondary=(255, 179, 229),
        accent=(255, 215, 94),
        dark=(3, 23, 46),
    ),
}


def font(size: int, black: bool = False) -> ImageFont.FreeTypeFont:
    return load_font(size, weight=900 if black else 700, width=78)


def rgba(color: tuple[int, int, int], alpha: int = 255) -> tuple[int, int, int, int]:
    return color[0], color[1], color[2], alpha


def canvas(size: int | tuple[int, int] = 240) -> Image.Image:
    if isinstance(size, int):
        size = (size, size)
    return Image.new("RGBA", size, (0, 0, 0, 0))


def paste_center(base: Image.Image, layer: Image.Image, center: tuple[int, int] | None = None) -> None:
    if center is None:
        center = (base.width // 2, base.height // 2)
    base.alpha_composite(layer, (center[0] - layer.width // 2, center[1] - layer.height // 2))


def drop_shadow(mask: Image.Image, color: tuple[int, int, int], blur: int, offset: tuple[int, int], alpha: int) -> Image.Image:
    shadow = Image.new("RGBA", mask.size, (0, 0, 0, 0))
    shadow.putalpha(mask.filter(ImageFilter.GaussianBlur(blur)).point(lambda p: p * alpha // 255))
    color_layer = Image.new("RGBA", mask.size, rgba(color, 255))
    shadow = ImageChops.multiply(color_layer, shadow)
    out = Image.new("RGBA", mask.size, (0, 0, 0, 0))
    out.alpha_composite(shadow, offset)
    return out


def glow_from_alpha(img: Image.Image, color: tuple[int, int, int], blur: int = 14, alpha: int = 165) -> Image.Image:
    mask = img.getchannel("A").filter(ImageFilter.GaussianBlur(blur)).point(lambda p: p * alpha // 255)
    glow = Image.new("RGBA", img.size, rgba(color, 255))
    glow.putalpha(mask)
    return glow


def radial_disc(size: int, inner: tuple[int, int, int], outer: tuple[int, int, int], alpha: int = 255) -> Image.Image:
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    px = img.load()
    cx = cy = (size - 1) / 2
    radius = size / 2
    for y in range(size):
        for x in range(size):
            dx = x - cx
            dy = y - cy
            dist = (dx * dx + dy * dy) ** 0.5 / radius
            if dist <= 1:
                t = min(1, dist)
                r = int(inner[0] * (1 - t) + outer[0] * t)
                g = int(inner[1] * (1 - t) + outer[1] * t)
                b = int(inner[2] * (1 - t) + outer[2] * t)
                edge = int(alpha * min(1, (1 - dist) * 10))
                px[x, y] = (r, g, b, edge)
    return img


def vertical_gradient(size: tuple[int, int], top: tuple[int, int, int], bottom: tuple[int, int, int], alpha: int = 255) -> Image.Image:
    w, h = size
    img = Image.new("RGBA", size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    for y in range(h):
        t = y / max(1, h - 1)
        color = tuple(int(top[i] * (1 - t) + bottom[i] * t) for i in range(3))
        draw.line((0, y, w, y), fill=rgba(color, alpha))
    return img


def draw_centered_text(
    draw: ImageDraw.ImageDraw,
    text: str,
    box: tuple[int, int, int, int],
    fill: tuple[int, int, int] = (255, 246, 226),
    stroke: tuple[int, int, int] = (55, 22, 88),
    max_size: int = 58,
    min_size: int = 24,
    black: bool = True,
) -> None:
    x0, y0, x1, y1 = box
    size = max_size
    selected = font(size, black=black)
    while size > min_size:
        selected = font(size, black=black)
        bbox = draw.textbbox((0, 0), text, font=selected, stroke_width=max(2, size // 14))
        if bbox[2] - bbox[0] <= x1 - x0 and bbox[3] - bbox[1] <= y1 - y0:
            break
        size -= 2
    stroke_width = max(2, size // 14)
    bbox = draw.textbbox((0, 0), text, font=selected, stroke_width=stroke_width)
    tx = x0 + ((x1 - x0) - (bbox[2] - bbox[0])) // 2 - bbox[0]
    ty = y0 + ((y1 - y0) - (bbox[3] - bbox[1])) // 2 - bbox[1]
    draw.text((tx, ty), text, font=selected, fill=rgba(fill), stroke_width=stroke_width, stroke_fill=rgba(stroke))


def save_webp(img: Image.Image, name: str, quality: int = 94) -> None:
    img.save(DRAWABLE / name, "WEBP", quality=quality, method=6, lossless=False)


def load_asset(name: str, size: int = 218) -> Image.Image:
    img = Image.open(DRAWABLE / name).convert("RGBA")
    img.thumbnail((size, size), Image.Resampling.LANCZOS)
    out = canvas(240)
    paste_center(out, img)
    return out


def symbol_base(glow: tuple[int, int, int]) -> Image.Image:
    img = canvas()
    aura = radial_disc(224, glow, (0, 0, 0), 128)
    aura = aura.filter(ImageFilter.GaussianBlur(12))
    paste_center(img, aura)
    return img


def add_bevel_highlight(img: Image.Image, bbox: tuple[int, int, int, int]) -> None:
    overlay = Image.new("RGBA", img.size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(overlay)
    draw.ellipse(bbox, outline=(255, 255, 255, 72), width=3)
    draw.arc((bbox[0] + 8, bbox[1] + 6, bbox[2] - 8, bbox[3] - 8), 200, 342, fill=(255, 255, 255, 112), width=6)
    img.alpha_composite(overlay)


def finish_symbol(shape: Image.Image, glow: tuple[int, int, int]) -> Image.Image:
    img = symbol_base(glow)
    img.alpha_composite(glow_from_alpha(shape, glow, blur=13, alpha=145))
    img.alpha_composite(drop_shadow(shape.getchannel("A"), (0, 0, 0), blur=9, offset=(0, 12), alpha=170))
    img.alpha_composite(shape)
    return img


def asset_symbol(name: str, glow: tuple[int, int, int]) -> Image.Image:
    return finish_symbol(load_asset(name), glow)


def polygon_symbol(points: list[tuple[int, int]], top: tuple[int, int, int], bottom: tuple[int, int, int], outline: tuple[int, int, int], glow: tuple[int, int, int]) -> Image.Image:
    layer = canvas()
    mask = Image.new("L", (240, 240), 0)
    ImageDraw.Draw(mask).polygon(points, fill=255)
    grad = vertical_gradient((240, 240), top, bottom, 255)
    grad.putalpha(mask)
    layer.alpha_composite(grad)
    draw = ImageDraw.Draw(layer)
    draw.polygon(points, outline=rgba(outline), width=5)
    shifted = [(x - 6, y + 6) for x, y in points]
    draw.line(shifted + [shifted[0]], fill=(255, 255, 255, 58), width=3)
    return finish_symbol(layer, glow)


def draw_neon_chip() -> Image.Image:
    layer = canvas()
    cx, cy = 120, 120
    outer = [(cx + int(cos(pi / 6 + i * pi / 3) * 82), cy + int(sin(pi / 6 + i * pi / 3) * 82)) for i in range(6)]
    inner = [(cx + int(cos(pi / 6 + i * pi / 3) * 55), cy + int(sin(pi / 6 + i * pi / 3) * 55)) for i in range(6)]
    draw = ImageDraw.Draw(layer)
    draw.polygon(outer, fill=(13, 229, 255, 255), outline=(231, 255, 255, 255))
    draw.polygon([(x, y + 8) for x, y in outer], outline=(0, 101, 132, 180), width=5)
    draw.polygon(inner, fill=(11, 18, 55, 255), outline=(132, 255, 255, 255))
    draw.line(outer + [outer[0]], fill=(255, 63, 214, 118), width=7)
    draw_centered_text(draw, "H", (78, 72, 162, 164), fill=(255, 255, 255), stroke=(12, 13, 55), max_size=70)
    return finish_symbol(layer, (26, 229, 255))


def draw_neon_seven() -> Image.Image:
    layer = canvas()
    draw = ImageDraw.Draw(layer)
    fnt = font(134, black=True)
    text = "7"
    bbox = draw.textbbox((0, 0), text, font=fnt, stroke_width=9)
    x = 120 - (bbox[2] - bbox[0]) // 2 - bbox[0]
    y = 111 - (bbox[3] - bbox[1]) // 2 - bbox[1]
    draw.rounded_rectangle((58, 46, 183, 188), radius=22, fill=(20, 10, 48, 255), outline=(255, 61, 202, 255), width=8)
    draw.text((x, y), text, font=fnt, fill=(255, 238, 104, 255), stroke_width=9, stroke_fill=(255, 45, 191, 255))
    draw.line((72, 60, 170, 60), fill=(255, 255, 255, 112), width=4)
    return finish_symbol(layer, (255, 61, 202))


def draw_pyramid_wild() -> Image.Image:
    layer = polygon_symbol([(120, 32), (210, 190), (30, 190)], (255, 219, 82), (162, 95, 20), (255, 246, 172), (255, 202, 61))
    draw = ImageDraw.Draw(layer)
    draw.line((120, 32, 120, 190), fill=(78, 41, 19, 96), width=4)
    draw.line((120, 32, 210, 190), fill=(255, 255, 255, 80), width=4)
    draw_centered_text(draw, "V", (72, 82, 168, 172), fill=(255, 255, 255), stroke=(80, 38, 10), max_size=88)
    return layer


def draw_scarab() -> Image.Image:
    layer = canvas()
    draw = ImageDraw.Draw(layer)
    draw.ellipse((70, 54, 170, 188), fill=(21, 203, 184, 255), outline=(255, 216, 86, 255), width=7)
    draw.pieslice((24, 72, 126, 190), 92, 260, fill=(19, 127, 150, 245), outline=(255, 216, 86, 255), width=5)
    draw.pieslice((114, 72, 216, 190), -80, 88, fill=(19, 127, 150, 245), outline=(255, 216, 86, 255), width=5)
    draw.ellipse((82, 36, 158, 98), fill=(34, 227, 202, 255), outline=(255, 236, 132, 255), width=5)
    for x in (82, 158):
        draw.line((x, 82, x + (-46 if x == 82 else 46), 54), fill=(255, 223, 92, 255), width=5)
        draw.line((x, 132, x + (-50 if x == 82 else 50), 124), fill=(255, 223, 92, 255), width=5)
    draw.line((120, 48, 120, 184), fill=(255, 246, 172, 180), width=5)
    draw.arc((78, 64, 162, 186), 200, 340, fill=(255, 255, 255, 112), width=5)
    return finish_symbol(layer, (255, 204, 60))


def draw_ankh() -> Image.Image:
    layer = canvas()
    draw = ImageDraw.Draw(layer)
    gold = (255, 214, 87, 255)
    shadow = (108, 55, 18, 255)
    draw.ellipse((80, 25, 160, 112), outline=shadow, width=18)
    draw.line((120, 100, 120, 198), fill=shadow, width=22)
    draw.line((66, 128, 174, 128), fill=shadow, width=20)
    draw.ellipse((80, 20, 160, 106), outline=gold, width=14)
    draw.line((120, 96, 120, 194), fill=gold, width=16)
    draw.line((70, 124, 170, 124), fill=gold, width=15)
    draw.arc((88, 30, 154, 98), 194, 328, fill=(255, 255, 210, 155), width=5)
    return finish_symbol(layer, (255, 202, 61))


def draw_sun() -> Image.Image:
    layer = canvas()
    draw = ImageDraw.Draw(layer)
    cx = cy = 120
    pts = []
    for i in range(24):
        r = 92 if i % 2 == 0 else 64
        a = -pi / 2 + i * pi / 12
        pts.append((cx + int(cos(a) * r), cy + int(sin(a) * r)))
    draw.polygon(pts, fill=(255, 149, 34, 255), outline=(255, 236, 118, 255))
    disc = radial_disc(120, (255, 248, 155), (255, 154, 32), 255)
    paste_center(layer, disc)
    add_bevel_highlight(layer, (60, 60, 180, 180))
    return finish_symbol(layer, (255, 196, 65))


def draw_lotus() -> Image.Image:
    layer = canvas()
    draw = ImageDraw.Draw(layer)
    colors = [(177, 84, 228), (236, 106, 224), (255, 208, 92)]
    for i, x in enumerate([70, 98, 120, 142, 170]):
        w = 42 if i in (0, 4) else 52
        y0 = 88 if i in (0, 4) else 62 if i in (1, 3) else 42
        draw.pieslice((x - w, y0, x + w, 196), 205, 335, fill=rgba(colors[i % 3]), outline=(255, 232, 134, 255), width=4)
    draw.arc((48, 122, 192, 216), 190, 350, fill=(255, 230, 116, 255), width=9)
    draw.line((64, 176, 178, 176), fill=(111, 54, 37, 220), width=5)
    return finish_symbol(layer, (255, 202, 61))


def draw_tablet() -> Image.Image:
    layer = canvas()
    draw = ImageDraw.Draw(layer)
    draw.rounded_rectangle((54, 38, 186, 194), radius=26, fill=(210, 154, 76, 255), outline=(255, 230, 139, 255), width=7)
    draw.rounded_rectangle((66, 52, 174, 182), radius=18, outline=(112, 67, 32, 180), width=4)
    for y in (76, 112, 148):
        draw.line((88, y, 152, y), fill=(112, 67, 32, 220), width=6)
    draw.arc((78, 63, 166, 158), 200, 330, fill=(255, 255, 205, 82), width=5)
    return finish_symbol(layer, (255, 202, 61))


def draw_pearl() -> Image.Image:
    layer = canvas()
    pearl = radial_disc(150, (255, 255, 255), (159, 215, 232), 255)
    paste_center(layer, pearl)
    draw = ImageDraw.Draw(layer)
    draw.ellipse((45, 45, 195, 195), outline=(255, 255, 255, 190), width=5)
    draw.ellipse((78, 64, 116, 102), fill=(255, 255, 255, 190))
    draw.arc((54, 62, 186, 190), 190, 332, fill=(244, 188, 228, 118), width=8)
    return finish_symbol(layer, (104, 229, 255))


def draw_trident() -> Image.Image:
    layer = canvas()
    draw = ImageDraw.Draw(layer)
    gold = (255, 215, 88, 255)
    dark = (105, 66, 19, 255)
    for dx in (-48, 0, 48):
        draw.line((120 + dx, 50, 120 + dx, 112), fill=dark, width=16)
        draw.line((120 + dx, 47, 120 + dx, 110), fill=gold, width=10)
    draw.arc((72, 40, 168, 142), 0, 180, fill=gold, width=10)
    draw.line((120, 72, 120, 198), fill=dark, width=18)
    draw.line((120, 68, 120, 194), fill=gold, width=12)
    draw.polygon([(120, 26), (108, 52), (132, 52)], fill=(255, 238, 142, 255))
    draw.line((88, 112, 152, 112), fill=gold, width=10)
    return finish_symbol(layer, (104, 229, 255))


def draw_starfish() -> Image.Image:
    layer = canvas()
    draw = ImageDraw.Draw(layer)
    cx = cy = 120
    pts = []
    for i in range(10):
        r = 86 if i % 2 == 0 else 36
        a = -pi / 2 + i * pi / 5
        pts.append((cx + int(cos(a) * r), cy + int(sin(a) * r)))
    draw.polygon(pts, fill=(235, 124, 61, 255), outline=(255, 210, 121, 255))
    for a in [i * 2 * pi / 5 - pi / 2 for i in range(5)]:
        draw.line((cx, cy, cx + int(cos(a) * 60), cy + int(sin(a) * 60)), fill=(255, 190, 112, 140), width=6)
    for a in [0, 1.1, 2.2, 3.5, 4.8]:
        draw.ellipse((cx + int(cos(a) * 34) - 4, cy + int(sin(a) * 34) - 4, cx + int(cos(a) * 34) + 4, cy + int(sin(a) * 34) + 4), fill=(255, 224, 156, 200))
    return finish_symbol(layer, (255, 181, 94))


def draw_shell() -> Image.Image:
    layer = canvas()
    draw = ImageDraw.Draw(layer)
    draw.pieslice((48, 42, 192, 208), 190, 350, fill=(239, 155, 213, 255), outline=(255, 237, 190, 255), width=6)
    for x in (78, 100, 120, 140, 162):
        draw.line((120, 70, x, 176), fill=(143, 82, 135, 130), width=5)
    draw.arc((58, 74, 182, 198), 205, 335, fill=(255, 255, 255, 116), width=5)
    draw.line((70, 176, 170, 176), fill=(255, 232, 174, 220), width=8)
    return finish_symbol(layer, (255, 179, 229))


def draw_anchor() -> Image.Image:
    layer = canvas()
    draw = ImageDraw.Draw(layer)
    steel = (183, 223, 234, 255)
    dark = (40, 86, 104, 255)
    draw.ellipse((96, 38, 144, 86), outline=dark, width=13)
    draw.line((120, 82, 120, 178), fill=dark, width=18)
    draw.line((78, 102, 162, 102), fill=dark, width=16)
    draw.arc((54, 116, 186, 218), 24, 156, fill=dark, width=16)
    draw.ellipse((99, 36, 141, 78), outline=steel, width=8)
    draw.line((120, 78, 120, 174), fill=steel, width=11)
    draw.line((82, 98, 158, 98), fill=steel, width=10)
    draw.arc((58, 112, 182, 212), 24, 156, fill=steel, width=10)
    draw.polygon([(58, 155), (82, 160), (66, 180)], fill=steel)
    draw.polygon([(182, 155), (158, 160), (174, 180)], fill=steel)
    return finish_symbol(layer, (104, 229, 255))


def draw_ocean_wild() -> Image.Image:
    layer = polygon_symbol([(120, 26), (204, 110), (120, 202), (36, 110)], (209, 250, 255), (74, 187, 217), (255, 255, 255), (104, 229, 255))
    draw = ImageDraw.Draw(layer)
    draw.line((36, 110, 204, 110), fill=(12, 83, 120, 85), width=4)
    draw.line((120, 26, 120, 202), fill=(255, 255, 255, 80), width=5)
    draw_centered_text(draw, "V", (76, 78, 164, 164), fill=(255, 255, 255), stroke=(22, 84, 115), max_size=84)
    return layer


SYMBOLS = {
    "nn_symbol_v_wild.webp": lambda: asset_symbol("vf_symbol_v_wild.webp", PALETTES["neon"].primary),
    "nn_symbol_holo_chip.webp": draw_neon_chip,
    "nn_symbol_neon_seven.webp": draw_neon_seven,
    "nn_symbol_credit.webp": lambda: asset_symbol("vf_symbol_coin.webp", PALETTES["neon"].primary),
    "nn_symbol_crown.webp": lambda: asset_symbol("vf_symbol_crown.webp", PALETTES["neon"].secondary),
    "nn_symbol_star.webp": lambda: asset_symbol("vf_symbol_star.webp", PALETTES["neon"].primary),
    "nn_symbol_cherry.webp": lambda: asset_symbol("vf_symbol_cherry.webp", PALETTES["neon"].secondary),
    "nn_symbol_bar.webp": lambda: asset_symbol("vf_symbol_bar.webp", PALETTES["neon"].primary),
    "pg_symbol_v_wild.webp": draw_pyramid_wild,
    "pg_symbol_scarab.webp": draw_scarab,
    "pg_symbol_ankh.webp": draw_ankh,
    "pg_symbol_coin.webp": lambda: asset_symbol("rr_symbol_coin.webp", PALETTES["pharaoh"].primary),
    "pg_symbol_crown.webp": lambda: asset_symbol("rr_symbol_crown.webp", PALETTES["pharaoh"].primary),
    "pg_symbol_sun.webp": draw_sun,
    "pg_symbol_lotus.webp": draw_lotus,
    "pg_symbol_tablet.webp": draw_tablet,
    "op_symbol_v_wild.webp": draw_ocean_wild,
    "op_symbol_pearl.webp": draw_pearl,
    "op_symbol_trident.webp": draw_trident,
    "op_symbol_coin.webp": lambda: asset_symbol("vf_symbol_coin.webp", PALETTES["ocean"].primary),
    "op_symbol_crown.webp": lambda: asset_symbol("vf_symbol_crown.webp", PALETTES["ocean"].primary),
    "op_symbol_starfish.webp": draw_starfish,
    "op_symbol_shell.webp": draw_shell,
    "op_symbol_anchor.webp": draw_anchor,
}


CARD_SYMBOLS = {
    "neon": ["nn_symbol_v_wild.webp", "nn_symbol_holo_chip.webp", "nn_symbol_neon_seven.webp", "nn_symbol_credit.webp", "nn_symbol_crown.webp"],
    "pharaoh": ["pg_symbol_v_wild.webp", "pg_symbol_scarab.webp", "pg_symbol_ankh.webp", "pg_symbol_coin.webp", "pg_symbol_crown.webp"],
    "ocean": ["op_symbol_v_wild.webp", "op_symbol_pearl.webp", "op_symbol_trident.webp", "op_symbol_coin.webp", "op_symbol_crown.webp"],
}


def rounded_panel_mask(size: tuple[int, int], radius: int) -> Image.Image:
    mask = Image.new("L", size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, size[0] - 1, size[1] - 1), radius=radius, fill=255)
    return mask


def draw_card(theme: str, pressed: bool = False) -> Image.Image:
    palette = PALETTES[theme]
    w, h = 980, 620
    img = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    mask = rounded_panel_mask((w, h), 42)
    bg = vertical_gradient((w, h), tuple(min(255, c + 36) for c in palette.dark), palette.dark, 255)
    bg2 = Image.new("RGBA", (w, h), rgba(palette.primary, 0))
    draw_bg = ImageDraw.Draw(bg2)
    draw_bg.ellipse((-130, 60, 520, 760), fill=rgba(palette.primary, 70))
    draw_bg.ellipse((500, -210, 1170, 450), fill=rgba(palette.secondary, 56))
    bg.alpha_composite(bg2.filter(ImageFilter.GaussianBlur(20)))
    bg.putalpha(mask)
    img.alpha_composite(bg)
    draw = ImageDraw.Draw(img)
    draw.rounded_rectangle((20, 20, w - 20, h - 20), radius=36, outline=rgba(palette.accent, 245), width=7)
    draw.rounded_rectangle((35, 35, w - 35, h - 35), radius=30, outline=rgba((255, 255, 255), 92), width=3)
    draw_centered_text(draw, palette.title, (58, 28, w - 58, 132), max_size=72, min_size=36)

    reel = (92, 154, 888, 402)
    draw.rounded_rectangle(reel, radius=28, fill=(10, 12, 39, 226), outline=rgba(palette.primary, 246), width=5)
    for i in range(5):
        x0 = 116 + i * 150
        draw.rounded_rectangle((x0, 184, x0 + 122, 354), radius=17, fill=(21, 23, 58, 216), outline=(255, 238, 168, 105), width=2)
        sym = Image.open(DRAWABLE / CARD_SYMBOLS[theme][i]).convert("RGBA")
        sym.thumbnail((154, 154), Image.Resampling.LANCZOS)
        paste_center(img, sym, (x0 + 61, 269))

    draw.rounded_rectangle((334, 446, 646, 538), radius=42, fill=rgba(palette.secondary, 206), outline=rgba(palette.accent, 255), width=6)
    draw_centered_text(draw, "ИГРАТЬ", (350, 454, 630, 530), max_size=48, min_size=30)
    for i in range(22):
        x = 55 + i * 41
        y = 576 + int(sin(i * 0.9) * 7)
        draw.ellipse((x, y, x + 8, y + 8), fill=rgba(palette.accent if i % 2 else palette.primary, 170))

    if pressed:
        shade = Image.new("RGBA", (w, h), (0, 0, 0, 68))
        shade.putalpha(mask.point(lambda p: min(p, 68)))
        img.alpha_composite(shade)
        img = img.transform((w, h), Image.Transform.AFFINE, (1, 0, 0, 0, 1, 4), resample=Image.Resampling.BICUBIC)
    return img


def main() -> None:
    verify_asset_toolchain()
    verify_font()
    for name, factory in SYMBOLS.items():
        save_webp(factory(), name)
    for theme, palette in PALETTES.items():
        save_webp(draw_card(theme, pressed=False), f"{palette.card_name}_default.webp", quality=93)
        save_webp(draw_card(theme, pressed=True), f"{palette.card_name}_pressed.webp", quality=92)


if __name__ == "__main__":
    main()
