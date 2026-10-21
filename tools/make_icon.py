"""Ritar JPFM-ikonen: samma familj som JPTV (mörk botten, "JP" i vitt) men med norrskensgrön "FM"
och ett svagt norrsken över. Kör: python3 tools/make_icon.py app/src/main/res"""
import math
import os
import sys

from PIL import Image, ImageDraw, ImageFilter, ImageFont

FONT = "/usr/share/fonts/opentype/inter/Inter-Black.otf"
S = 1024
ACCENT = (79, 227, 176)


def render() -> Image.Image:
    img = Image.new("RGB", (S, S))
    px = img.load()
    top, bot = (20, 25, 36), (8, 10, 14)
    for y in range(S):
        for x in range(S):
            t = x / S * 0.4 + y / S * 0.6
            px[x, y] = tuple(int(top[i] * (1 - t) + bot[i] * t) for i in range(3))

    # Norrsken: några mjuka band som böljar över övre halvan.
    aurora = Image.new("L", (S, S), 0)
    d = ImageDraw.Draw(aurora)
    for band in range(3):
        pts = []
        for x in range(-40, S + 41, 16):
            y = S * (0.18 + band * 0.07) + math.sin(x / S * math.pi * 2.2 + band * 1.3) * S * 0.06
            pts.append((x, y))
        d.line(pts, fill=230 - band * 50, width=int(S * (0.07 - band * 0.015)))
    aurora = aurora.filter(ImageFilter.GaussianBlur(42))
    img = Image.composite(Image.new("RGB", (S, S), ACCENT), img, aurora.point(lambda v: int(v * 0.85)))

    glow = Image.new("L", (S, S), 0)
    ImageDraw.Draw(glow).ellipse((int(S * 0.2), int(S * 0.55), int(S * 1.2), int(S * 1.5)), fill=110)
    glow = glow.filter(ImageFilter.GaussianBlur(160))
    img = Image.composite(Image.new("RGB", (S, S), (40, 120, 160)), img, glow.point(lambda v: int(v * 0.45)))

    d = ImageDraw.Draw(img)
    font = ImageFont.truetype(FONT, 330)
    for text, color, cy in [("JP", (244, 246, 249), 0.38), ("FM", ACCENT, 0.68)]:
        b = d.textbbox((0, 0), text, font=font)
        w, h = b[2] - b[0], b[3] - b[1]
        d.text(((S - w) / 2 - b[0], S * cy - h / 2 - b[1]), text, font=font, fill=color)
    return img


def main(res: str) -> None:
    img = render()
    mask = Image.new("L", (S, S), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, S, S), radius=int(S * 0.22), fill=255)
    out = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    out.paste(img, (0, 0), mask)
    for name, size in [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)]:
        folder = os.path.join(res, f"mipmap-{name}")
        os.makedirs(folder, exist_ok=True)
        out.resize((size, size), Image.LANCZOS).save(os.path.join(folder, "ic_launcher.png"), optimize=True)
    out.resize((512, 512), Image.LANCZOS).save(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "docs", "icon.png"))
    print("ok")


if __name__ == "__main__":
    main(sys.argv[1])
