#!/usr/bin/env python3
"""Draws Wander TV's launcher icon and Fire TV banner: Lumo the moth in a lamp glow, on ink.

Needs Pillow only. Everything is drawn at 4x and downsampled, so edges stay soft at launcher sizes.

    python tools/gen_art.py                 # writes app/src/main/res/drawable-*/ic_launcher.png + banner.png
    python tools/gen_art.py --extras DIR    # also a 512 px store icon and a 1280x720 hero image in DIR
"""

import argparse
import math
import os

from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageFont, ImageOps

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(HERE, ".."))
RES = os.path.join(ROOT, "app", "src", "main", "res")
FONT = os.path.join(ROOT, "app", "src", "main", "assets", "fonts", "YoungSerif-Regular.ttf")

# Palette (matches ui/Theme.kt)
INK = (18, 14, 28)
NIGHT = (27, 21, 41)
DUSK = (38, 30, 58)
CREAM = (247, 239, 226)
LAMP = (255, 181, 71)
BERRY = (255, 93, 143)
PLUM = (122, 46, 102)
TAN = (226, 206, 176)

SS = 4  # supersampling factor


def lerp(a, b, t):
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(len(a)))


def bezier(p0, p1, p2, p3, steps=48):
    pts = []
    for i in range(steps + 1):
        t = i / steps
        u = 1 - t
        x = u ** 3 * p0[0] + 3 * u * u * t * p1[0] + 3 * u * t * t * p2[0] + t ** 3 * p3[0]
        y = u ** 3 * p0[1] + 3 * u * u * t * p1[1] + 3 * u * t * t * p2[1] + t ** 3 * p3[1]
        pts.append((x, y))
    return pts


def path(segments):
    """segments: list of (p0, c1, c2, p1) cubic curves, each starting where the last ended."""
    pts = []
    for seg in segments:
        pts.extend(bezier(*seg)[:-1])
    pts.append(segments[-1][3])
    return pts


def radial(size, center, radius, inner, outer, falloff=1.0):
    """RGBA radial gradient from [inner] (RGBA) at center to [outer] at [radius]."""
    w, h = size
    # Pillow's gradient is 0 at the center and 255 in the corners (distance 181); rescale it so 255 sits
    # on the inscribed circle (distance 128) and the paste blends seamlessly into the 255 surround.
    base = Image.radial_gradient("L").point(lambda v: min(255, int(v * 181 / 128.0)))
    scale = radius / 128.0
    big = base.resize((max(1, int(256 * scale)), max(1, int(256 * scale))), Image.BILINEAR)
    field = Image.new("L", size, 255)
    field.paste(big, (int(center[0] - big.width / 2), int(center[1] - big.height / 2)))
    if falloff != 1.0:
        field = field.point(lambda v: int(255 * (v / 255.0) ** falloff))
    layers = []
    for ch in range(4):
        a, b = inner[ch], outer[ch]
        layers.append(field.point(lambda v, a=a, b=b: int(a + (b - a) * v / 255.0)))
    return Image.merge("RGBA", layers)


class Moth:
    """Lumo, spread-winged, drawn in a unit box: x,y in [-1, 1], thorax at the origin, y down."""

    def __init__(self, img, cx, cy, scale):
        self.img = img
        self.cx, self.cy, self.s = cx, cy, scale

    def p(self, x, y):
        return (self.cx + x * self.s, self.cy + y * self.s)

    def pts(self, unit_pts, mirror=False):
        return [self.p(-x if mirror else x, y) for x, y in unit_pts]

    def shape_mask(self, polys):
        mask = Image.new("L", self.img.size, 0)
        d = ImageDraw.Draw(mask)
        for poly in polys:
            d.polygon(poly, fill=255)
        return mask

    def fill_gradient(self, mask, center, radius, inner, outer, falloff=1.0):
        grad = radial(self.img.size, center, radius, inner + (255,), outer + (255,), falloff)
        layer = Image.new("RGBA", self.img.size, (0, 0, 0, 0))
        layer.paste(grad, (0, 0), mask)
        self.img.alpha_composite(layer)

    def draw(self, detail=1.0):
        s = self.s
        fore = path([
            ((0.05, -0.12), (0.22, -0.62), (0.70, -0.86), (0.97, -0.60)),
            ((0.97, -0.60), (1.07, -0.34), (0.97, -0.06), (0.70, 0.07)),
            ((0.70, 0.07), (0.46, 0.13), (0.20, 0.08), (0.05, 0.03)),
        ])
        hind = path([
            ((0.05, 0.02), (0.38, 0.02), (0.76, 0.14), (0.71, 0.42)),
            ((0.71, 0.42), (0.66, 0.70), (0.36, 0.82), (0.19, 0.63)),
            ((0.19, 0.63), (0.09, 0.51), (0.04, 0.28), (0.03, 0.12)),
        ])

        # Soft shadow under the wings so they lift off the glow.
        shadow = self.shape_mask([self.pts(fore), self.pts(fore, True), self.pts(hind), self.pts(hind, True)])
        shadow = shadow.filter(ImageFilter.GaussianBlur(s * 0.06))
        sh = Image.new("RGBA", self.img.size, INK + (0,))
        sh.putalpha(shadow.point(lambda v: int(v * 0.55)))
        self.img.alpha_composite(sh, (int(s * 0.0), int(s * 0.05)))

        body_c = self.p(0, -0.02)
        # Hindwings: berry fading to plum at the rim.
        self.fill_gradient(self.shape_mask([self.pts(hind), self.pts(hind, True)]), body_c, s * 0.85, (255, 150, 112), (184, 58, 122), 0.9)
        # Forewings: warm lamp amber by the body, berry toward the tips.
        self.fill_gradient(self.shape_mask([self.pts(fore), self.pts(fore, True)]), body_c, s * 1.05, LAMP, BERRY, 1.1)

        d = ImageDraw.Draw(self.img)
        # A darker rim along the outer margin of the forewings.
        rim = path([((0.97, -0.60), (1.07, -0.34), (0.97, -0.06), (0.70, 0.07))])
        for mirror in (False, True):
            d.line(self.pts(rim, mirror), fill=PLUM + (200,), width=max(1, int(s * 0.035)), joint="curve")
        # Veins: a few faint strokes from the body.
        if detail >= 0.5:
            for (x1, y1, x2, y2) in ((0.10, -0.08, 0.80, -0.62), (0.12, -0.02, 0.88, -0.30), (0.12, 0.02, 0.70, 0.02),
                                     (0.08, 0.08, 0.55, 0.40), (0.06, 0.12, 0.30, 0.62)):
                for mirror in (False, True):
                    sx = -1 if mirror else 1
                    d.line([self.p(sx * x1, y1), self.p(sx * x2, y2)], fill=PLUM + (70,), width=max(1, int(s * 0.012)))

        # Eye-spots: the moth's "don't eat me" eyes, cream ring around an ink pupil.
        for mirror in (False, True):
            sx = -1 if mirror else 1
            ex, ey = self.p(sx * 0.60, -0.36)
            for r, col in ((0.155, INK), (0.12, CREAM), (0.085, LAMP), (0.05, INK)):
                rr = r * s
                d.ellipse([ex - rr, ey - rr, ex + rr, ey + rr], fill=col + (255,))
            hr = 0.022 * s
            d.ellipse([ex - 0.03 * s - hr, ey - 0.03 * s - hr, ex - 0.03 * s + hr, ey - 0.03 * s + hr], fill=CREAM + (255,))
            # A small hindwing spot.
            hx, hy = self.p(sx * 0.42, 0.40)
            rr = 0.06 * s
            d.ellipse([hx - rr, hy - rr, hx + rr, hy + rr], fill=CREAM + (150,))

        # Antennae: feathery, curling up and out.
        for mirror in (False, True):
            sx = -1 if mirror else 1
            stem = bezier((sx * 0.03, -0.27), (sx * 0.05, -0.55), (sx * 0.20, -0.74), (sx * 0.38, -0.80), 40)
            pts = [self.p(x, y) for x, y in stem]
            d.line(pts, fill=CREAM + (255,), width=max(1, int(s * 0.03)), joint="curve")
            if detail >= 0.5:
                for i in range(6, len(stem) - 2, 4):
                    x, y = stem[i]
                    x0, y0 = stem[i - 1]
                    ang = math.atan2(y - y0, x - x0)
                    length = 0.09 * (1 - i / len(stem)) + 0.03
                    for side in (-1, 1):
                        a = ang + side * 1.1
                        d.line([self.p(x, y), self.p(x + math.cos(a) * length, y + math.sin(a) * length)],
                               fill=CREAM + (210,), width=max(1, int(s * 0.016)))
            tip = self.p(sx * 0.38, -0.80)
            tr = 0.028 * s
            d.ellipse([tip[0] - tr, tip[1] - tr, tip[0] + tr, tip[1] + tr], fill=CREAM + (255,))

        # Fuzzy body: abdomen, thorax, head. Drawn on its own layer with a soft halo for fuzz.
        body = Image.new("RGBA", self.img.size, (0, 0, 0, 0))
        b = ImageDraw.Draw(body)
        ax, ay = self.p(0, 0.27)
        b.ellipse([ax - 0.095 * s, ay - 0.30 * s, ax + 0.095 * s, ay + 0.30 * s], fill=CREAM + (255,))
        tx, ty = self.p(0, -0.04)
        b.ellipse([tx - 0.14 * s, ty - 0.14 * s, tx + 0.14 * s, ty + 0.14 * s], fill=CREAM + (255,))
        halo = body.filter(ImageFilter.GaussianBlur(s * 0.025))
        self.img.alpha_composite(halo)
        self.img.alpha_composite(body)
        d = ImageDraw.Draw(self.img)
        # Abdomen segments.
        for k in range(4):
            yy = ay - 0.06 * s + k * 0.085 * s
            half = 0.085 * s * (1 - k * 0.14)
            d.arc([ax - half, yy - 0.03 * s, ax + half, yy + 0.03 * s], 20, 160, fill=TAN + (255,), width=max(1, int(s * 0.014)))
        # Head with two big dark eyes and a glint.
        hx, hy = self.p(0, -0.22)
        hr = 0.105 * s
        d.ellipse([hx - hr, hy - hr, hx + hr, hy + hr], fill=CREAM + (255,), outline=TAN + (255,), width=max(1, int(s * 0.01)))
        for sx in (-1, 1):
            ex, ey = hx + sx * 0.045 * s, hy - 0.005 * s
            er = 0.034 * s
            d.ellipse([ex - er, ey - er, ex + er, ey + er], fill=INK + (255,))
            gr = 0.012 * s
            d.ellipse([ex - 0.012 * s - gr, ey - 0.012 * s - gr, ex - 0.012 * s + gr, ey - 0.012 * s + gr], fill=CREAM + (255,))


def lamp_glow(img, center, radius, strength=1.0):
    glow = radial(img.size, center, radius, LAMP + (int(105 * strength),), LAMP + (0,), 0.6)
    img.alpha_composite(glow)
    core = radial(img.size, center, radius * 0.4, (255, 220, 160, int(110 * strength)), LAMP + (0,), 0.9)
    img.alpha_composite(core)


def dust(img, area, count, seed, max_r, alpha=140):
    import random

    rnd = random.Random(seed)
    d = ImageDraw.Draw(img)
    x0, y0, x1, y1 = area
    for _ in range(count):
        x = rnd.uniform(x0, x1)
        y = rnd.uniform(y0, y1)
        r = rnd.uniform(0.3, 1.0) * max_r
        a = int(alpha * rnd.uniform(0.25, 1.0))
        d.ellipse([x - r, y - r, x + r, y + r], fill=CREAM + (a,))


def background(size):
    img = Image.new("RGBA", size, INK + (255,))
    w, h = size
    img.alpha_composite(radial(size, (w * 0.5, h * 0.5), max(w, h) * 0.75, DUSK + (255,), INK + (255,), 1.0))
    img.alpha_composite(radial(size, (w * 0.95, h * 1.1), max(w, h) * 0.7, BERRY + (46,), BERRY + (0,), 0.9))
    return img


def icon(size):
    big = size * SS
    img = background((big, big))
    lamp_glow(img, (big * 0.5, big * 0.44), big * 0.62, 1.0)
    dust(img, (big * 0.08, big * 0.06, big * 0.92, big * 0.94), 22, 5, big * 0.008, 120)
    Moth(img, big * 0.5, big * 0.53, big * 0.40).draw(detail=1.0 if size >= 128 else 0.4)
    # Rounded-square mask so the icon reads as a tile on any launcher.
    mask = Image.new("L", (big, big), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, big - 1, big - 1], radius=int(big * 0.22), fill=255)
    out = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    out.paste(img, (0, 0), mask)
    return out.resize((size, size), Image.LANCZOS)


def banner(w, h, title="Wander", subline="BROWSE TOGETHER"):
    W, H = w * SS, h * SS
    img = background((W, H))
    lamp_glow(img, (W * 0.2, H * 0.40), H * 0.9, 1.0)
    dust(img, (0, 0, W * 0.5, H), 40, 11, H * 0.006, 130)
    dust(img, (W * 0.5, 0, W, H), 18, 12, H * 0.004, 70)
    Moth(img, W * 0.205, H * 0.53, H * 0.30).draw(detail=1.0)

    d = ImageDraw.Draw(img)
    tx = int(W * 0.415)
    max_w = W * 0.95 - tx
    size = int(H * 0.30)
    font = ImageFont.truetype(FONT, size)
    while d.textlength(title, font=font) > max_w and size > 10:
        size -= 2
        font = ImageFont.truetype(FONT, size)
    box = d.textbbox((0, 0), title, font=font)
    title_w = box[2] - box[0]
    text_h = box[3] - box[1]
    ty = int(H * 0.44 - text_h / 2 - box[1])
    # Warm glow behind the wordmark, as if lit by the lamp.
    glow = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    ImageDraw.Draw(glow).text((tx, ty), title, font=font, fill=LAMP + (140,))
    img.alpha_composite(glow.filter(ImageFilter.GaussianBlur(H * 0.03)))
    d.text((tx, ty), title, font=font, fill=CREAM + (255,))
    # The tagline, letterspaced in lamp amber, exactly as wide as the wordmark.
    small = ImageFont.truetype(FONT, max(8, int(H * 0.075)))
    glyphs = sum(d.textlength(ch, font=small) for ch in subline)
    gap = (title_w - glyphs) / max(1, len(subline) - 1)
    sx = tx + box[0]
    sy = ty + box[3] + int(H * 0.06)
    for ch in subline:
        d.text((sx, sy), ch, font=small, fill=LAMP + (255,))
        sx += d.textlength(ch, font=small) + gap
    return img.resize((w, h), Image.LANCZOS)


def hero(w, h):
    """A 16:9 still for the README / store listing."""
    return banner(w, h)


def save(img, *parts):
    path = os.path.join(RES, *parts)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path, optimize=True)
    print("wrote", os.path.relpath(path, ROOT), img.size)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--extras", help="also write icon_512.png and hero_1280x720.png here")
    args = parser.parse_args()

    save(icon(96), "drawable-mdpi", "ic_launcher.png")
    save(icon(144), "drawable-hdpi", "ic_launcher.png")
    save(icon(192), "drawable-xhdpi", "ic_launcher.png")
    save(icon(288), "drawable-xxhdpi", "ic_launcher.png")
    save(banner(320, 180), "drawable-xhdpi", "banner.png")
    save(banner(640, 360), "drawable-xxhdpi", "banner.png")
    placeholder = os.path.join(RES, "drawable", "ic_launcher.xml")
    if os.path.exists(placeholder):
        os.remove(placeholder)
        print("removed placeholder", os.path.relpath(placeholder, ROOT))
    if args.extras:
        os.makedirs(args.extras, exist_ok=True)
        icon(512).save(os.path.join(args.extras, "icon_512.png"), optimize=True)
        hero(1280, 720).save(os.path.join(args.extras, "hero_1280x720.png"), optimize=True)
        print("wrote extras to", args.extras)


if __name__ == "__main__":
    main()
