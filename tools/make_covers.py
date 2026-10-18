"""Generate abstract 'glass archive' cover images for the Welkin demo site."""
import math, random
from PIL import Image, ImageDraw, ImageFilter, ImageChops

W, H = 1200, 900
OUT = r"D:\01-工作区\welkin-apk\site\source\images"

PALETTES = [
    # (top, bottom, accent, glow)
    ((247, 240, 232), (206, 194, 181), (198, 124, 74), (255, 252, 246)),
    ((232, 238, 240), (176, 189, 196), (86, 120, 138), (250, 252, 253)),
    ((243, 234, 224), (196, 176, 156), (150, 106, 62), (255, 246, 234)),
    ((228, 230, 236), (158, 162, 178), (96, 104, 136), (246, 247, 251)),
    ((241, 236, 226), (192, 180, 158), (128, 128, 96), (252, 250, 242)),
    ((236, 236, 232), (168, 172, 168), (110, 118, 110), (250, 250, 248)),
]


def lerp(a, b, t):
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(3))


def gradient(top, bottom):
    img = Image.new("RGB", (W, H))
    d = ImageDraw.Draw(img)
    for y in range(H):
        d.line([(0, y), (W, y)], fill=lerp(top, bottom, (y / H) ** 1.25))
    return img


def soft_shape(size, color, alpha, center, radius_list):
    layer = Image.new("L", size, 0)
    d = ImageDraw.Draw(layer)
    cx, cy = center
    for i, r in enumerate(radius_list):
        a = int(alpha * (1 - i / (len(radius_list) + 1)))
        d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=a)
    layer = layer.filter(ImageFilter.GaussianBlur(radius=42))
    tint = Image.new("RGB", size, color)
    return layer, tint


def make(idx):
    top, bottom, accent, glow = PALETTES[idx]
    random.seed(1000 + idx * 37)
    img = gradient(top, bottom)

    # large soft light pool
    mask, tint = soft_shape((W, H), glow, 235, (W * (0.34 + 0.06 * idx), H * 0.30), [520, 380, 240])
    img = Image.composite(tint, img, mask)

    # concentric thin arcs (archive ring motif)
    arcs = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(arcs)
    cx, cy = W * 0.63, H * 0.62
    for i in range(16):
        r = 90 + i * 47
        start = 18 + i * 3.1
        end = start + 118 + i * 2.7
        width = 2 if i % 3 else 3
        d.arc([cx - r * 1.22, cy - r * 0.86, cx + r * 1.22, cy + r * 0.86],
              start, end, fill=accent + (110 - i * 5,), width=width)
    arcs = arcs.filter(ImageFilter.GaussianBlur(0.8))
    img = Image.alpha_composite(img.convert("RGBA"), arcs)

    # translucent glass panes
    panes = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(panes)
    for i in range(5):
        x0 = -160 + i * 290 + random.randint(-40, 40)
        y0 = 120 + random.randint(-60, 60)
        w = random.randint(210, 330)
        h = random.randint(520, 760)
        skew = random.randint(-70, 70)
        poly = [(x0, y0), (x0 + w, y0 + skew), (x0 + w - 30, y0 + h), (x0 - 20, y0 + h - skew)]
        d.polygon(poly, fill=glow + (26,), outline=accent + (40,))
    panes = panes.filter(ImageFilter.GaussianBlur(1.1))
    img = Image.alpha_composite(img, panes)

    # horizon rule + tick marks
    rules = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(rules)
    hy = int(H * 0.735)
    d.line([(60, hy), (W - 60, hy)], fill=accent + (150,), width=2)
    for i in range(28):
        x = 60 + i * (W - 120) / 27
        long_tick = i % 5 == 0
        d.line([(x, hy), (x, hy + (16 if long_tick else 8))], fill=accent + (120,), width=2 if long_tick else 1)
    # small archive glyph block
    d.rectangle([86, 96, 210, 122], outline=accent + (170,), width=2)
    for i in range(3):
        d.line([(100, 106 + i * 0), (100, 112)], fill=accent + (190,), width=2)
    img = Image.alpha_composite(img, rules)

    # fine grain
    noise = Image.effect_noise((W, H), 26).convert("L").point(lambda v: 128 + (v - 128) * 0.55)
    grain = Image.merge("RGB", (noise, noise, noise))
    img = ImageChops.blend(img.convert("RGB"), grain, 0.055)

    # vignette
    vig = Image.new("L", (W, H), 0)
    dv = ImageDraw.Draw(vig)
    dv.ellipse([-W * 0.30, -H * 0.34, W * 1.30, H * 1.34], fill=255)
    vig = vig.filter(ImageFilter.GaussianBlur(180))
    dark = Image.new("RGB", (W, H), lerp(bottom, (40, 36, 32), 0.35))
    img = Image.composite(img, dark, vig)

    img = img.filter(ImageFilter.GaussianBlur(0.35))
    path = f"{OUT}\\cover-{idx + 1}.webp"
    img.save(path, "WEBP", quality=84, method=6)
    return path


if __name__ == "__main__":
    import os
    os.makedirs(OUT, exist_ok=True)
    for i in range(6):
        print(make(i))
