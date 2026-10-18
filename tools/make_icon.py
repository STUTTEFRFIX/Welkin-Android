"""Generate the Welkin archive launcher icon at every density."""
import os
from PIL import Image, ImageDraw, ImageFilter

S = 1024
BASE = (234, 229, 225)
INK = (43, 39, 36)
ACCENT = (198, 124, 74)
OUT = r"D:\01-工作区\welkin-apk\android\app\src\main\res"

DENSITIES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}


def rounded_rect(draw, box, radius, **kwargs):
    draw.rounded_rectangle(box, radius=radius, **kwargs)


def build():
    img = Image.new("RGB", (S, S), BASE)
    d = ImageDraw.Draw(img)

    # soft light pool
    pool = Image.new("L", (S, S), 0)
    p = ImageDraw.Draw(pool)
    p.ellipse([-S * 0.2, -S * 0.35, S * 0.95, S * 0.8], fill=210)
    pool = pool.filter(ImageFilter.GaussianBlur(150))
    light = Image.new("RGB", (S, S), (252, 249, 244))
    img = Image.composite(light, img, pool)

    d = ImageDraw.Draw(img)

    # three glass archive cards, back to front
    cards = [
        (0.16, 0.30, 0.34, 0.52, 0.86, (240, 234, 227), (206, 196, 185)),
        (0.30, 0.22, 0.50, 0.60, 0.90, (246, 241, 235), (214, 203, 191)),
        (0.46, 0.32, 0.62, 0.70, 0.78, (250, 246, 240), (222, 211, 199)),
    ]
    for x0, y0, x1, y1, alpha, top, bottom in cards:
        box = (int(S * x0), int(S * y0), int(S * x1), int(S * y1))
        w, h = box[2] - box[0], box[3] - box[1]
        sheet = Image.new("RGB", (max(w, 1), max(h, 1)))
        sd = ImageDraw.Draw(sheet)
        for y in range(max(h, 1)):
            t = y / max(h - 1, 1)
            sd.line([(0, y), (w, y)], fill=tuple(int(top[i] + (bottom[i] - top[i]) * t) for i in range(3)))
        # cut corner like an archive card
        mask = Image.new("L", (max(w, 1), max(h, 1)), 255)
        md = ImageDraw.Draw(mask)
        cut = int(w * 0.26)
        md.polygon([(w - cut, 0), (w, 0), (w, cut)], fill=0)
        img.paste(sheet, (box[0], box[1]), mask)

    # thin accent rules on the front card
    fx0, fy0, fx1, fy1 = 0.52, 0.40, 0.66, 0.62
    d = ImageDraw.Draw(img)
    d.line([(int(S * fx0), int(S * fy0)), (int(S * fx1), int(S * fy0))], fill=ACCENT + (0,), width=5)
    d.line([(int(S * fx0), int(S * fy0) + 34), (int(S * fx1) - 40, int(S * fy0) + 34)], fill=(150, 120, 100), width=3)
    d.line([(int(S * fx0), int(S * fy0) + 66), (int(S * fx1) - 80, int(S * fy0) + 66)], fill=(170, 150, 130), width=3)

    # concentric arc motif bottom-left
    arcs = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    ad = ImageDraw.Draw(arcs)
    cx, cy = S * 0.30, S * 0.86
    for i in range(6):
        r = S * (0.10 + i * 0.075)
        ad.arc([cx - r, cy - r * 0.82, cx + r, cy + r * 0.82], 200, 340,
               fill=ACCENT + (120 - i * 14,), width=max(2, 6 - i))
    img = Image.alpha_composite(img.convert("RGBA"), arcs).convert("RGB")

    # vignette + grain
    vig = Image.new("L", (S, S), 0)
    ImageDraw.Draw(vig).ellipse([-S * 0.25, -S * 0.25, S * 1.25, S * 1.25], fill=255)
    vig = vig.filter(ImageFilter.GaussianBlur(120))
    dark = Image.new("RGB", (S, S), (222, 215, 208))
    img = Image.composite(img, dark, vig)
    img = img.filter(ImageFilter.GaussianBlur(0.6))

    master = img
    for density, size in DENSITIES.items():
        folder = os.path.join(OUT, "mipmap-" + density)
        os.makedirs(folder, exist_ok=True)
        master.resize((size, size), Image.LANCZOS).save(os.path.join(folder, "ic_launcher.png"), "PNG", optimize=True)
        print(os.path.join(folder, "ic_launcher.png"), size)

    preview = os.path.join(r"D:\01-工作区\welkin-apk\shots", "icon-512.png")
    os.makedirs(os.path.dirname(preview), exist_ok=True)
    master.resize((512, 512), Image.LANCZOS).save(preview, "PNG")
    print(preview)


if __name__ == "__main__":
    build()
