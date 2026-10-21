#!/usr/bin/env python3
"""Launcher icon: 24×24 pixel-art folder with a gold key, nearest-neighbour scaled. Run from the repo root."""
from PIL import Image
import os

PAL = {'K': (0, 0, 0), 'W': (255, 255, 255), 'Y': (255, 255, 128), 'L': (255, 255, 224), 'o': (224, 192, 64),
       'y': (128, 128, 0), 'S': (255, 215, 0), 'O': (176, 104, 32), 'd': (128, 128, 128)}
ART = [
    "........................",
    "........................",
    "..yyyyyyy...............",
    ".yLLLLLLLy..............",
    "yLYYYYYYYLyyyyyyyyyyy...",
    "yLoooooooooooooooooooy..",
    "yLooooooooooooooooooooy.",
    "yLyyyyyyyyyyyyyyyyyyyyyK",
    "yLLLLLLLLLLLLLLLLLLLLLyK",
    "yLYYYYYYYYYYYYYYYYYYYYyK",
    "yLYYYKKKKYYYYYYYYYYYYYyK",
    "yLYYKSSSSKYYYYYYYYYYYYyK",
    "yLYKSSKKSSKYYYYYYYYYYYyK",
    "yLYKSKYYKSKKKKKKKKKKYYyK",
    "yLYKSKYYKSSSSSSSSSSSKYyK",
    "yLYKSSKKSSKKKKSKSKSKYYyK",
    "yLYYKSSSSKYYYYKYKYKYYYyK",
    "yLYYYKKKKYYYYYYYYYYYYYyK",
    "yLYYYYYYYYYYYYYYYYYYYYyK",
    "yLYYYYYYYYYYYYYYYYYYYYyK",
    "yoooooooooooooooooooooyK",
    "yyyyyyyyyyyyyyyyyyyyyyyK",
    ".KKKKKKKKKKKKKKKKKKKKKKK",
    "........................",
]
assert all(len(r) == 24 for r in ART) and len(ART) == 24
TEAL = (0, 128, 128, 255)

def art(scale):
    im = Image.new("RGBA", (24, 24), (0, 0, 0, 0))
    for y, row in enumerate(ART):
        for x, c in enumerate(row):
            if c in PAL:
                im.putpixel((x, y), PAL[c] + (255,))
    return im.resize((24 * scale, 24 * scale), Image.NEAREST)

def legacy(px):
    # square teal tile with a gray bevel and the art centred
    im = Image.new("RGBA", (px, px), TEAL)
    u = max(1, px // 48)
    for i in range(2 * u):
        for x in range(px):
            im.putpixel((x, i), (255, 255, 255, 255) if i < u else (223, 223, 223, 255))
            im.putpixel((x, px - 1 - i), (0, 0, 0, 255) if i < u else (128, 128, 128, 255))
        for y in range(px):
            im.putpixel((i, y), (255, 255, 255, 255) if i < u else (223, 223, 223, 255))
            im.putpixel((px - 1 - i, y), (0, 0, 0, 255) if i < u else (128, 128, 128, 255))
    s = max(1, (px * 3 // 4) // 24)
    a = art(s)
    im.alpha_composite(a, ((px - a.width) // 2, (px - a.height) // 2))
    return im

def foreground(px):
    im = Image.new("RGBA", (px, px), (0, 0, 0, 0))
    s = max(1, int(px * 0.55) // 24)
    a = art(s)
    im.alpha_composite(a, ((px - a.width) // 2, (px - a.height) // 2))
    return im

res = "app/src/main/res"
for name, d in [("mdpi", 1), ("hdpi", 1.5), ("xhdpi", 2), ("xxhdpi", 3), ("xxxhdpi", 4)]:
    os.makedirs(f"{res}/mipmap-{name}", exist_ok=True)
    legacy(int(48 * d)).save(f"{res}/mipmap-{name}/ic_launcher.png")
    foreground(int(108 * d)).save(f"{res}/mipmap-{name}/ic_launcher_foreground.png")
os.makedirs(f"{res}/mipmap-anydpi", exist_ok=True)
open(f"{res}/mipmap-anydpi/ic_launcher.xml", "w").write(
    '<?xml version="1.0" encoding="utf-8"?>\n<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
    '    <background android:drawable="@color/icon_bg" />\n    <foreground android:drawable="@mipmap/ic_launcher_foreground" />\n'
    '    <monochrome android:drawable="@mipmap/ic_launcher_foreground" />\n</adaptive-icon>\n')
os.makedirs(f"{res}/values", exist_ok=True)
open(f"{res}/values/colors.xml", "w").write('<?xml version="1.0" encoding="utf-8"?>\n<resources>\n    <color name="icon_bg">#008080</color>\n</resources>\n')
legacy(512).save("art/icon-512.png")
print("ok")
