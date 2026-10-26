#!/usr/bin/env python3
"""Draws Потсик's app icon and writes it to every place the app has one.

The character is drawn from the same numbers the app draws him with — the
geometry in `ui/potsik/PotsikBody.kt`, which is the PotSquad firmware's own
`drawBody()`, and the colours in `ui/potsik/PotsikPalette.kt` — so the icon is
the character and not a picture of something like him.

Run it from the repository root:

    python3 scripts/generate-app-icon.py

Needs Pillow (`pip install pillow`).
"""

from __future__ import annotations

import math
import struct
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

# --- The character's colours (PotsikLook.kt) ---------------------------------

FUR = (255, 228, 94, 255)          # #FFE45E, the firmware's FUR_MAIN
INK = (16, 16, 16, 255)            # the keyline
WHITE = (255, 255, 255, 255)
PINK = (244, 154, 193, 255)         # the tongue
LENS = (0, 56, 32, 255)             # the shades' lens: blend(BG, CYAN, 60)
CHEEK = (0xC8, 0x92, 0x39, 255)    # the fur, darkened
BRAND_A = (0x62, 0x00, 0xEE, 255)   # #6200EE
BRAND_B = (0x80, 0x63, 0xC5, 255)   # #8063C5

# --- The character's geometry, in body units (PotsikRenderer.kt) -------------

HEAD_W, HEAD_H = 24.0, 24.0
HEAD_R = 4.0
EAR = 4.0
MUZZLE = (14.0, 10.0, 3.0)
NOSE = (6.0, 5.0, 1.0)
EYE_X, EYE_Y = 6.0, 10.0
EYE_HALF = 3.5
MOUTH = (12.0, 6.0, 2.0)

# Where the head sits in the icon: the icon is a square, the head is a wide
# shape with a cowlick above it, so it is centred a little low.
HEAD_CENTER = (0.5, 0.54)
HEAD_SCALE = 0.62          # of the icon's width

SUPERSAMPLE = 4


def _darken(color, amount):
    return tuple(int(c * (1.0 - amount)) for c in color[:3]) + (color[3],)


def draw_head(draw: ImageDraw.ImageDraw, unit: float, ox: float, oy: float) -> None:
    """Draws Потсик's head, in the renderer's own coordinates.

    `unit` is how many pixels one body unit is; `ox`/`oy` are the origin: the
    character's horizontal centre and the top of his head.
    """

    def u(value: float) -> float:
        return value * unit

    def p(x: float, y: float) -> tuple[float, float]:
        return (ox + u(x), oy + u(y))

    def rounded(x, y, w, h, r, fill):
        draw.rounded_rectangle([p(x, y), p(x + w, y + h)], radius=u(r), fill=fill)

    # The cowlick: the short upright spike, the taller one leaning right,
    # and the two side tufts tucked inside the edge of the skull.
    draw.polygon([p(-7, 2), p(-4, -5), p(-1, 2)], fill=FUR)
    draw.polygon([p(-2, 2), p(6, -7.2), p(6, 2)], fill=FUR)
    draw.polygon([p(-10, 3), p(-7, -4), p(-4, 3)], fill=FUR)
    draw.polygon([p(4, 3), p(7, -4), p(10, 3)], fill=FUR)

    # Ears: small squares hugging the sides of the skull.
    for side in (-1, 1):
        ex = side * 11 - EAR / 2
        ey = 11
        rounded(ex, ey, EAR, EAR, 1.0, FUR)
        rounded(ex + 1, ey + 1, EAR - 2, EAR - 2, 0.6, _darken(FUR, 0.35))

    # The skull.
    rounded(-HEAD_W / 2, 0, HEAD_W, HEAD_H, HEAD_R, INK)
    rounded(-HEAD_W / 2 + 0.5, 0.5, HEAD_W - 1, HEAD_H - 1, HEAD_R - 0.4, FUR)

    # The muzzle patch.
    mw, mh, mr = MUZZLE
    rounded(-mw / 2, 13, mw, mh, mr, FUR)

    # Cheeks.
    for side in (-1, 1):
        rounded(side * 8 - 2, 15, 4, 4, 1.0, _darken(FUR, 0.22))

    # The nose: a dark keyhole with the fur back over the middle of it.
    nw, nh, nr = NOSE
    rounded(-nw / 2, 13, nw, nh, nr, INK)
    rounded(-2, 14, 4, 3, 0.7, FUR)

    # The shades: the removable glasses layer, which is what he wears by
    # default. Two frames, a bridge between them and the lenses inside.
    rounded(-12, 6, 10, 7, 2.0, INK)
    rounded(2, 6, 10, 7, 2.0, INK)
    draw.rectangle([p(-3, 8), p(2, 10)], fill=INK)
    rounded(-11, 7, 8, 5, 1.0, LENS)
    rounded(3, 7, 8, 5, 1.0, LENS)
    # The glint that sweeps across each lens.
    draw.rectangle([p(-10, 7), p(-9.4, 12)], fill=WHITE)
    draw.rectangle([p(4, 7), p(4.6, 12)], fill=WHITE)

    # The mouth: the resting grin, with the teeth on top and the tongue below.
    mw, mh, mr = MOUTH
    rounded(-mw / 2, 16, mw, mh, mr, INK)
    draw.rectangle([p(-mw / 2 + 1, 17), p(mw / 2 - 1, 18.8)], fill=WHITE)
    draw.rectangle([p(-mw / 2 + 1, 18.8), p(mw / 2 - 1, 19.7)], fill=INK)
    draw.rectangle([p(-mw / 2 + 1, 19.7), p(mw / 2 - 1, 21.2)], fill=(244, 154, 193, 255))


def render_icon(size: int, background: bool = True) -> Image.Image:
    """The whole icon: the brand's purple plate with the character on it."""
    big = size * SUPERSAMPLE
    image = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)

    if background:
        # The plate: the same two purples the old icon was made of, as a
        # gradient across the square.
        for y in range(big):
            t = y / max(big - 1, 1)
            color = tuple(int(BRAND_A[i] * (1 - t) + BRAND_B[i] * t) for i in range(3)) + (255,)
            draw.line([(0, y), (big, y)], fill=color)

    unit = big * HEAD_SCALE / (HEAD_W + 6)
    ox = big * HEAD_CENTER[0]
    oy = big * HEAD_CENTER[1] - HEAD_H * unit / 2
    draw_head(draw, unit, ox, oy)

    return image.resize((size, size), Image.LANCZOS)


def render_monochrome(size: int) -> Image.Image:
    """The character's silhouette, for the Android themed icon.

    One flat colour: the system tints it to match the user's wallpaper.
    """
    big = size * SUPERSAMPLE
    image = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    unit = big * HEAD_SCALE / (HEAD_W + 6)
    ox = big * HEAD_CENTER[0]
    oy = big * HEAD_CENTER[1] - HEAD_H * unit / 2

    def u(value: float) -> float:
        return value * unit

    def p(x: float, y: float) -> tuple[float, float]:
        return (ox + u(x), oy + u(y))

    solid = (0, 0, 0, 255)
    draw.polygon([p(-7, 2), p(-4, -5), p(-1, 2)], fill=solid)
    draw.polygon([p(-2, 2), p(6, -7.2), p(6, 2)], fill=solid)
    draw.polygon([p(-10, 3), p(-7, -4), p(-4, 3)], fill=solid)
    draw.polygon([p(4, 3), p(7, -4), p(10, 3)], fill=solid)
    for side in (-1, 1):
        ex = side * 11 - EAR / 2
        draw.rounded_rectangle([p(ex, 11), p(ex + EAR, 15)], radius=u(1), fill=solid)
    draw.rounded_rectangle(
        [p(-HEAD_W / 2, 0), p(HEAD_W / 2, HEAD_H)], radius=u(HEAD_R), fill=solid
    )
    # The lenses are holes in it, so the face still reads.
    for side in (-1, 1):
        cx = side * 7
        draw.rounded_rectangle(
            [p(cx - 4, 7), p(cx + 4, 12)], radius=u(1), fill=(0, 0, 0, 0)
        )
    return image.resize((size, size), Image.LANCZOS)


def write_ico(image: Image.Image, path: Path, sizes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    image.save(path, format="ICO", sizes=sizes)


def write_icns(images: dict[str, Image.Image], path: Path) -> None:
    """A real .icns container: a type/length chunk per size."""
    chunks = b""
    for icon_type, image in images.items():
        import io

        buffer = io.BytesIO()
        image.save(buffer, format="PNG")
        data = buffer.getvalue()
        chunks += icon_type.encode("ascii") + struct.pack(">I", len(data) + 8) + data
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(b"icns" + struct.pack(">I", len(chunks) + 8) + chunks)




# --- The vector sources ------------------------------------------------------

FUR_HEX = "#FFE45E"
INK_HEX = "#101010"
CHEEK_HEX = "#C89239"
PINK_HEX = "#F49AC1"
LENS_HEX = "#003820"
WHITE_HEX = "#FFFFFF"
BRAND_A_HEX = "#6200EE"
BRAND_B_HEX = "#8063C5"

# The Android adaptive icon's foreground is drawn in a 108 unit viewport, and
# only the middle 72 of it is guaranteed to survive the system's mask.
ANDROID_UNIT = 2.0
ANDROID_OX = 54.0
ANDROID_OY = 33.0


def _n(value: float) -> str:
    return f"{value:.2f}".rstrip("0").rstrip(".") or "0"


def _rounded_rect_path(x, y, w, h, r) -> str:
    return (
        f"M{_n(x + r)},{_n(y)} L{_n(x + w - r)},{_n(y)} "
        f"A{_n(r)},{_n(r)} 0 0 1 {_n(x + w)},{_n(y + r)} "
        f"L{_n(x + w)},{_n(y + h - r)} "
        f"A{_n(r)},{_n(r)} 0 0 1 {_n(x + w - r)},{_n(y + h)} "
        f"L{_n(x + r)},{_n(y + h)} "
        f"A{_n(r)},{_n(r)} 0 0 1 {_n(x)},{_n(y + h - r)} "
        f"L{_n(x)},{_n(y + r)} "
        f"A{_n(r)},{_n(r)} 0 0 1 {_n(x + r)},{_n(y)} Z"
    )


def _circle_path(cx, cy, r) -> str:
    return (
        f"M{_n(cx - r)},{_n(cy)} "
        f"a{_n(r)},{_n(r)} 0 1,0 {_n(2 * r)},0 "
        f"a{_n(r)},{_n(r)} 0 1,0 {_n(-2 * r)},0 Z"
    )


def svg_body() -> str:
    """The character's head, as SVG shapes in the renderer's own units."""
    return "\n".join(
        [
            f'  <polygon points="-7,2 -4,-5 -1,2" fill="{FUR_HEX}"/>',
            f'  <polygon points="-2,2 6,-7.2 6,2" fill="{FUR_HEX}"/>',
            f'  <polygon points="-10,3 -7,-4 -4,3" fill="{FUR_HEX}"/>',
            f'  <polygon points="4,3 7,-4 10,3" fill="{FUR_HEX}"/>',
            f'  <rect x="-13" y="11" width="4" height="4" rx="1" fill="{FUR_HEX}"/>',
            f'  <rect x="-12" y="12" width="2" height="2" rx="0.6" fill="{CHEEK_HEX}"/>',
            f'  <rect x="9" y="11" width="4" height="4" rx="1" fill="{FUR_HEX}"/>',
            f'  <rect x="10" y="12" width="2" height="2" rx="0.6" fill="{CHEEK_HEX}"/>',
            f'  <rect x="-12" y="0" width="24" height="24" rx="4" fill="{INK_HEX}"/>',
            f'  <rect x="-11.5" y="0.5" width="23" height="23" rx="3.6" fill="{FUR_HEX}"/>',
            f'  <rect x="-7" y="13" width="14" height="10" rx="3" fill="{FUR_HEX}"/>',
            f'  <rect x="-10" y="15" width="4" height="4" rx="2" fill="{CHEEK_HEX}"/>',
            f'  <rect x="6" y="15" width="4" height="4" rx="2" fill="{CHEEK_HEX}"/>',
            f'  <rect x="-3" y="13" width="6" height="5" rx="1" fill="{INK_HEX}"/>',
            f'  <rect x="-2" y="14" width="4" height="3" rx="0.7" fill="{FUR_HEX}"/>',
            f'  <rect x="-12" y="6" width="10" height="7" rx="2" fill="{INK_HEX}"/>',
            f'  <rect x="2" y="6" width="10" height="7" rx="2" fill="{INK_HEX}"/>',
            f'  <rect x="-3" y="8" width="5" height="2" fill="{INK_HEX}"/>',
            f'  <rect x="-11" y="7" width="8" height="5" rx="1" fill="{LENS_HEX}"/>',
            f'  <rect x="3" y="7" width="8" height="5" rx="1" fill="{LENS_HEX}"/>',
            f'  <rect x="-10" y="7" width="0.6" height="5" fill="{WHITE_HEX}"/>',
            f'  <rect x="4" y="7" width="0.6" height="5" fill="{WHITE_HEX}"/>',
            f'  <rect x="-6" y="16" width="12" height="6" rx="2" fill="{INK_HEX}"/>',
            f'  <rect x="-5" y="17" width="10" height="1.8" fill="{WHITE_HEX}"/>',
            f'  <rect x="-5" y="18.8" width="10" height="0.9" fill="{INK_HEX}"/>',
            f'  <rect x="-5" y="19.7" width="10" height="1.5" fill="{PINK_HEX}"/>',
        ]
    )


def svg_icon() -> str:
    """The icon as a vector, for the web and the desktop package."""
    unit = 512 * HEAD_SCALE / (HEAD_W + 6)
    ox = 512 * HEAD_CENTER[0]
    oy = 512 * HEAD_CENTER[1] - HEAD_H * unit / 2
    body = svg_body()
    return f"""<svg viewBox="0 0 512 512" xmlns="http://www.w3.org/2000/svg">
  <defs>
    <linearGradient id="pots" x1="0" y1="0" x2="1" y2="1">
      <stop offset="0" stop-color="{BRAND_A_HEX}"/>
      <stop offset="1" stop-color="{BRAND_B_HEX}"/>
    </linearGradient>
  </defs>
  <rect width="512" height="512" fill="url(#pots)"/>
  <g transform="translate({ox:.2f},{oy:.2f}) scale({unit:.4f})">
{body}
  </g>
</svg>
"""


def svg_head() -> str:
    """The character on its own, with nothing behind it.

    The places that put the character on their own background — the iOS launch
    screen, the site's mascot — take this one.
    """
    unit = 512 * HEAD_SCALE / (HEAD_W + 6)
    ox = 512 * HEAD_CENTER[0]
    oy = 512 * HEAD_CENTER[1] - HEAD_H * unit / 2
    return f"""<svg viewBox="0 0 512 512" xmlns="http://www.w3.org/2000/svg">
  <g transform="translate({ox:.2f},{oy:.2f}) scale({unit:.4f})">
{svg_body()}
  </g>
</svg>
"""


def android_foreground() -> str:
    """The Android adaptive icon's foreground: the character on its own."""
    u, ox, oy = ANDROID_UNIT, ANDROID_OX, ANDROID_OY

    def p(x, y):
        return (ox + x * u, oy + y * u)

    def poly(points):
        return "M" + " L".join(f"{_n(p(x, y)[0])},{_n(p(x, y)[1])}" for x, y in points) + " Z"

    parts = [
        f'    <path android:pathData="{poly([(-7, 2), (-4, -5), (-1, 2)])}" android:fillColor="{FUR_HEX}"/>',
        f'    <path android:pathData="{poly([(-2, 2), (6, -7.2), (6, 2)])}" android:fillColor="{FUR_HEX}"/>',
        f'    <path android:pathData="{poly([(-10, 3), (-7, -4), (-4, 3)])}" android:fillColor="{FUR_HEX}"/>',
        f'    <path android:pathData="{poly([(4, 3), (7, -4), (10, 3)])}" android:fillColor="{FUR_HEX}"/>',
        f'    <path android:pathData="{_rounded_rect_path(*p(-13, 11), 4 * u, 4 * u, 1 * u)}" android:fillColor="{FUR_HEX}"/>',
        f'    <path android:pathData="{_rounded_rect_path(*p(9, 11), 4 * u, 4 * u, 1 * u)}" android:fillColor="{FUR_HEX}"/>',
        f'    <path android:pathData="{_rounded_rect_path(*p(-12, 0), 24 * u, 24 * u, 4 * u)}" android:fillColor="{INK_HEX}"/>',
        f'    <path android:pathData="{_rounded_rect_path(*p(-11.5, 0.5), 23 * u, 23 * u, 3.6 * u)}" android:fillColor="{FUR_HEX}"/>',
        f'    <path android:pathData="{_rounded_rect_path(*p(-7, 13), 14 * u, 10 * u, 3 * u)}" android:fillColor="{FUR_HEX}"/>',
        f'    <path android:pathData="{_rounded_rect_path(*p(-10, 15), 4 * u, 4 * u, 1 * u)}" android:fillColor="{CHEEK_HEX}"/>',
        f'    <path android:pathData="{_rounded_rect_path(*p(6, 15), 4 * u, 4 * u, 1 * u)}" android:fillColor="{CHEEK_HEX}"/>',
        f'    <path android:pathData="{_rounded_rect_path(*p(-3, 13), 6 * u, 5 * u, 1 * u)}" android:fillColor="{INK_HEX}"/>',
        f'    <path android:pathData="{_rounded_rect_path(*p(-2, 14), 4 * u, 3 * u, 0.7 * u)}" android:fillColor="{FUR_HEX}"/>',
        f'    <path android:pathData="{_rounded_rect_path(*p(-12, 6), 10 * u, 7 * u, 2 * u)}" android:fillColor="{INK_HEX}"/>',
        f'    <path android:pathData="{_rounded_rect_path(*p(2, 6), 10 * u, 7 * u, 2 * u)}" android:fillColor="{INK_HEX}"/>',
        f'    <path android:pathData="M{_n(ox - 3 * u)},{_n(oy + 8 * u)} L{_n(ox + 2 * u)},{_n(oy + 8 * u)} L{_n(ox + 2 * u)},{_n(oy + 10 * u)} L{_n(ox - 3 * u)},{_n(oy + 10 * u)} Z" android:fillColor="{INK_HEX}"/>',
        f'    <path android:pathData="{_rounded_rect_path(*p(-11, 7), 8 * u, 5 * u, 1 * u)}" android:fillColor="{LENS_HEX}"/>',
        f'    <path android:pathData="{_rounded_rect_path(*p(3, 7), 8 * u, 5 * u, 1 * u)}" android:fillColor="{LENS_HEX}"/>',
        f'    <path android:pathData="M{_n(ox - 10 * u)},{_n(oy + 7 * u)} L{_n(ox - 9.4 * u)},{_n(oy + 7 * u)} L{_n(ox - 9.4 * u)},{_n(oy + 12 * u)} L{_n(ox - 10 * u)},{_n(oy + 12 * u)} Z" android:fillColor="{WHITE_HEX}"/>',
        f'    <path android:pathData="M{_n(ox + 4 * u)},{_n(oy + 7 * u)} L{_n(ox + 4.6 * u)},{_n(oy + 7 * u)} L{_n(ox + 4.6 * u)},{_n(oy + 12 * u)} L{_n(ox + 4 * u)},{_n(oy + 12 * u)} Z" android:fillColor="{WHITE_HEX}"/>',
        f'    <path android:pathData="{_rounded_rect_path(*p(-6, 16), 12 * u, 6 * u, 2 * u)}" android:fillColor="{INK_HEX}"/>',
        f'    <path android:pathData="M{_n(ox - 5 * u)},{_n(oy + 17 * u)} L{_n(ox + 5 * u)},{_n(oy + 17 * u)} L{_n(ox + 5 * u)},{_n(oy + 18.8 * u)} L{_n(ox - 5 * u)},{_n(oy + 18.8 * u)} Z" android:fillColor="{WHITE_HEX}"/>',
        f'    <path android:pathData="M{_n(ox - 5 * u)},{_n(oy + 19.7 * u)} L{_n(ox + 5 * u)},{_n(oy + 19.7 * u)} L{_n(ox + 5 * u)},{_n(oy + 21.2 * u)} L{_n(ox - 5 * u)},{_n(oy + 21.2 * u)} Z" android:fillColor="{PINK_HEX}"/>',
    ]
    return (
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    android:width="108dp"\n'
        '    android:height="108dp"\n'
        '    android:viewportWidth="108"\n'
        '    android:viewportHeight="108">\n'
        + "\n".join(parts)
        + "\n</vector>\n"
    )


def android_monochrome() -> str:
    """The Android themed icon: the character's silhouette, one flat colour.

    The eyes are holes in it, so the face still reads once the system has
    tinted it to match the wallpaper.
    """
    u, ox, oy = ANDROID_UNIT, ANDROID_OX, ANDROID_OY

    def p(x, y):
        return (ox + x * u, oy + y * u)

    def poly(points):
        return "M" + " L".join(f"{_n(p(x, y)[0])},{_n(p(x, y)[1])}" for x, y in points) + " Z"

    path = " ".join(
        [
            poly([(-7, 2), (-4, -5), (-1, 2)]),
            poly([(-2, 2), (6, -7.2), (6, 2)]),
            poly([(-10, 3), (-7, -4), (-4, 3)]),
            poly([(4, 3), (7, -4), (10, 3)]),
            _rounded_rect_path(*p(-13, 11), 4 * u, 4 * u, 1 * u),
            _rounded_rect_path(*p(9, 11), 4 * u, 4 * u, 1 * u),
            _rounded_rect_path(*p(-12, 0), 24 * u, 24 * u, 4 * u),
            _rounded_rect_path(*p(-11, 7), 8 * u, 5 * u, 1 * u),
            _rounded_rect_path(*p(3, 7), 8 * u, 5 * u, 1 * u),
        ]
    )
    return (
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    android:width="108dp"\n'
        '    android:height="108dp"\n'
        '    android:viewportWidth="108"\n'
        '    android:viewportHeight="108">\n'
        '    <path\n'
        f'        android:pathData="{path}"\n'
        '        android:fillColor="#000000"\n'
        '        android:fillType="evenOdd"/>\n'
        '</vector>\n'
    )


def android_notification() -> str:
    """The status bar icon: the character's head, in one flat colour.

    Android draws a notification's small icon as a silhouette — the system
    tints it and drops the colour — so this is the shape on its own.
    """
    # The whole head — the cowlick above, the chin below — has to fit in the
    # square the status bar shows, with a margin for the system's mask.
    u = 0.64
    ox, oy = 12.0, 7.0

    def p(x, y):
        return (ox + x * u, oy + y * u)

    def poly(points):
        return "M" + " L".join(f"{_n(p(x, y)[0])},{_n(p(x, y)[1])}" for x, y in points) + " Z"

    parts = [
        f'    <path android:pathData="{poly([(-7, 2), (-4, -5), (-1, 2)])}" android:fillColor="#FFFFFFFF"/>',
        f'    <path android:pathData="{poly([(-2, 2), (6, -7.2), (6, 2)])}" android:fillColor="#FFFFFFFF"/>',
        f'    <path android:pathData="{poly([(-10, 3), (-7, -4), (-4, 3)])}" android:fillColor="#FFFFFFFF"/>',
        f'    <path android:pathData="{poly([(4, 3), (7, -4), (10, 3)])}" android:fillColor="#FFFFFFFF"/>',
        f'    <path android:pathData="{_rounded_rect_path(*p(-13, 11), 4 * u, 4 * u, 1 * u)}" android:fillColor="#FFFFFFFF"/>',
        f'    <path android:pathData="{_rounded_rect_path(*p(9, 11), 4 * u, 4 * u, 1 * u)}" android:fillColor="#FFFFFFFF"/>',
        f'    <path android:pathData="{_rounded_rect_path(*p(-12, 0), 24 * u, 24 * u, 4 * u)}" android:fillColor="#FFFFFFFF"/>',
        f'    <path android:pathData="{_rounded_rect_path(*p(-11, 7), 8 * u, 5 * u, 1 * u)}" android:fillColor="#FFFFFFFF"/>',
        f'    <path android:pathData="{_rounded_rect_path(*p(3, 7), 8 * u, 5 * u, 1 * u)}" android:fillColor="#FFFFFFFF"/>',
    ]
    return (
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    android:width="24dp"\n'
        '    android:height="24dp"\n'
        '    android:viewportWidth="24"\n'
        '    android:viewportHeight="24">\n'
        + "\n".join(parts)
        + "\n</vector>\n"
    )


def svg_mascot() -> str:
    """The character, alive: the site's mascot and the README's logo.

    The same head, with the cowlick wagging and the whole body bobbing — the
    two things the firmware's idle pose does most of the time.
    """
    cowlick = "\n".join(
        [
            f'      <polygon points="-7,2 -4,-5 -1,2" fill="{FUR_HEX}"/>',
            f'      <polygon points="-2,2 6,-7.2 6,2" fill="{FUR_HEX}"/>',
            f'      <polygon points="-10,3 -7,-4 -4,3" fill="{FUR_HEX}"/>',
            f'      <polygon points="4,3 7,-4 10,3" fill="{FUR_HEX}"/>',
        ]
    )
    body = "\n".join(
        line for line in svg_body().splitlines() if "polygon" not in line
    )
    return f"""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 104 104" width="104" height="104" role="img" aria-label="Potsik">
  <g>
    <animateTransform attributeName="transform" type="translate"
                      values="0 0; 0 5; 0 0" dur="2.6s" repeatCount="indefinite"/>
    <g transform="translate(52,27.6) scale(3)">
      <g>
        <animateTransform attributeName="transform" type="rotate"
                          values="-5 0 2; 5 0 2; -5 0 2" dur="1.3s" repeatCount="indefinite"/>
{cowlick}
      </g>
{body}
    </g>
  </g>
</svg>
"""


def render_mascot_gif(size: int = 240, frames: int = 12) -> list[Image.Image]:
    """The character, alive, as frames: the README's logo."""
    pictures = []
    for index in range(frames):
        phase = index / frames * 2 * math.pi
        bob = math.sin(phase) * 0.05
        tilt = math.sin(phase) * 4.0
        big = size * SUPERSAMPLE
        image = Image.new("RGBA", (big, big), (0, 0, 0, 0))
        draw = ImageDraw.Draw(image)
        unit = big * HEAD_SCALE / (HEAD_W + 6)
        ox = big * HEAD_CENTER[0]
        oy = big * (HEAD_CENTER[1] + bob) - HEAD_H * unit / 2
        draw_head(draw, unit, ox, oy)
        rotated = image.rotate(tilt, resample=Image.BICUBIC, center=(ox, oy))
        pictures.append(rotated.resize((size, size), Image.LANCZOS))
    return pictures


def _font(size: int):
    """A font for the wordmark, from whatever the machine has."""
    for candidate in (
        "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
        "/usr/share/fonts/liberation/LiberationSans-Bold.ttf",
        "/usr/share/fonts/TTF/DejaVuSans-Bold.ttf",
        "/usr/share/fonts/noto/NotoSans-Bold.ttf",
        "/System/Library/Fonts/Helvetica.ttc",
    ):
        path = Path(candidate)
        if path.exists():
            try:
                return ImageFont.truetype(str(path), size)
            except OSError:
                continue
    return ImageFont.load_default(size=size)


def _gradient(width: int, height: int) -> Image.Image:
    """The brand's plate: the two purples, one over the other."""
    image = Image.new("RGB", (width, height))
    draw = ImageDraw.Draw(image)
    for y in range(height):
        t = y / max(height - 1, 1)
        color = tuple(int(BRAND_A[i] * (1 - t) + BRAND_B[i] * t) for i in range(3))
        draw.line([(0, y), (width, y)], fill=color)
    return image


def _draw_card(width, height, tagline, name_ratio, tag_ratio):
    """A card: the character, the name and the line under it.

    The character stands straight on the brand's plate — the gradient is the
    background, so the two are one picture rather than a square pasted on
    another one.
    """
    image = _gradient(width, height)
    draw = ImageDraw.Draw(image)

    character = render_icon(int(height * 0.62), background=False)
    left = int(width * 0.08)
    top = int(height * 0.19)
    image.paste(character, (left, top), character)

    name_font = _font(int(height * name_ratio))
    tag_font = _font(int(height * tag_ratio))
    x = left + character.width + int(width * 0.05)
    draw.text((x, int(height * 0.30)), "Pots", font=name_font, fill=(255, 255, 255))

    # The line under the name, wrapped so it never runs off the card.
    words = tagline.split()
    lines, current = [], ""
    for word in words:
        candidate = f"{current} {word}".strip()
        if draw.textlength(candidate, font=tag_font) > width - x - int(width * 0.06):
            lines.append(current)
            current = word
        else:
            current = candidate
    if current:
        lines.append(current)
    for index, line in enumerate(lines):
        draw.text(
            (x, int(height * 0.56) + index * int(height * tag_ratio * 1.35)),
            line,
            font=tag_font,
            fill=(240, 236, 255),
        )
    return image


def render_social_preview(width: int = 1200, height: int = 630) -> Image.Image:
    """The card the social networks show: the character and the name."""
    return _draw_card(
        width,
        height,
        "Open-source AI assistant with persistent memory",
        0.17,
        0.048,
    )


def render_feature_graphic(width: int = 1024, height: int = 500) -> Image.Image:
    """The store's feature graphic: the character and the name."""
    return _draw_card(
        width,
        height,
        "An AI assistant that remembers what matters to you",
        0.24,
        0.062,
    )


def write_pdf(svg: str, path: Path) -> None:
    """The vector source as a PDF, for the places that want a PDF."""
    path.parent.mkdir(parents=True, exist_ok=True)
    source = path.with_suffix(".svg.tmp")
    source.write_text(svg)
    try:
        import subprocess

        subprocess.run(
            ["rsvg-convert", "--format=pdf", "--output", str(path), str(source)],
            check=True,
        )
    except (OSError, subprocess.SubprocessError):
        # No SVG renderer: the raster version is still the right picture.
        render_icon(1024).convert("RGB").save(path, format="PDF", resolution=72.0)
    finally:
        source.unlink(missing_ok=True)


def write_aur_icon(root: Path, svg: str) -> None:
    """The Arch package's icon, written into the PKGBUILD.

    The package builds from the release tarball alone, so the icon has to be
    inside the PKGBUILD itself.
    """
    pkgbuild = root / "aur/PKGBUILD"
    if not pkgbuild.exists():
        return
    text = pkgbuild.read_text()
    start = text.find("    # Install icon")
    if start < 0:
        return
    end = text.find("EOF", start)
    if end < 0:
        return
    block = (
        "    # Install icon\n"
        "    install -Dm644 /dev/stdin \"${pkgdir}/usr/share/icons/hicolor/scalable/apps/pots.svg\" << 'EOF'\n"
        + svg.rstrip()
        + "\nEOF\n"
    )
    pkgbuild.write_text(text[:start] + block + text[end + len("EOF\n"):])


def main() -> None:
    root = Path(__file__).resolve().parent.parent

    master = render_icon(1024)
    plate = render_icon(512)

    # The desktop package: a PNG for Linux, an ICO for Windows, an ICNS for
    # macOS.
    plate.save(root / "composeApp/icon.png")
    write_ico(plate, root / "composeApp/icon.ico", [(16, 16), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
    write_icns(
        {
            "icp4": render_icon(16),
            "icp5": render_icon(32),
            "icp6": render_icon(64),
            "ic07": render_icon(128),
            "ic08": render_icon(256),
            "ic09": render_icon(512),
            "ic10": render_icon(1024),
        },
        root / "composeApp/icon.icns",
    )

    # The web build.
    web = root / "composeApp/src/wasmJsMain/resources"
    write_ico(render_icon(256), web / "favicon.ico", [(16, 16), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
    render_icon(180).save(web / "apple-icon-180x180.png")

    # The site's own icon.
    site = root / "site/img"
    write_ico(render_icon(256), site / "favicon.ico", [(16, 16), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])

    # iOS.
    render_icon(1024).save(root / "iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/icon2.png")

    # The stores.
    render_icon(512).save(root / "fastlane/metadata/android/en-US/images/icon.png")
    master.save(root / "fastlane/metadata/android/en-US/images/icon-1024.png")

    # The vector sources: the web and the desktop package.
    svg = svg_icon()
    (root / "composeApp/icon.svg").write_text(svg)
    (root / "composeApp/src/wasmJsMain/resources/icon.svg").write_text(svg)
    (root / "site/img/icon.svg").write_text(svg)

    # The PDFs: the desktop package and the iOS launch screen.
    write_pdf(svg, root / "composeApp/icon.pdf")
    write_pdf(
        svg_head(),
        root / "iosApp/iosApp/Assets.xcassets/LaunchLogo.imageset/logo_transparent.pdf",
    )

    # The character, alive: the site's mascot and the README's logo.
    mascot = svg_mascot()
    (root / "site/img/logo_animation.svg").write_text(mascot)
    frames = render_mascot_gif()
    frames[0].save(
        root / "site/img/logo_animation.gif",
        save_all=True,
        append_images=frames[1:],
        duration=90,
        loop=0,
        disposal=2,
        transparency=0,
    )

    # The social cards: what the networks and the stores show.
    preview = render_social_preview()
    preview.save(root / "site/img/preview.png")
    og = render_social_preview(1232, 848)
    og.save(root / "site/img/og-preview.png")
    og.save(root / "screenshots/og-preview.png")
    render_feature_graphic().save(
        root / "fastlane/metadata/android/en-US/images/featureGraphic.png"
    )

    # The Arch package's icon.
    write_aur_icon(root, svg)

    # Android's adaptive icon.
    foreground = android_foreground()
    monochrome = android_monochrome()
    for target in (
        root / "androidApp/src/main/res/drawable/ic_launcher_foreground.xml",
        root / "composeApp/src/androidMain/res/drawable/ic_launcher_foreground.xml",
    ):
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(foreground)
    for target in (
        root / "androidApp/src/main/res/drawable/ic_launcher_monochrome.xml",
        root / "composeApp/src/androidMain/res/drawable/ic_launcher_monochrome.xml",
    ):
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(monochrome)

    # The status bar's icon: the character in one flat colour.
    notification = android_notification()
    for target in (
        root / "androidApp/src/main/res/drawable/ic_notification.xml",
        root / "composeApp/src/androidMain/res/drawable/ic_notification.xml",
    ):
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(notification)

    print("Icons written.")


if __name__ == "__main__":
    main()
