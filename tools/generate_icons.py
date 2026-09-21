#!/usr/bin/env python3
"""
Alutube icon pipeline.

Generates the Android launcher icon set from a single square source image
(project root images.jpeg, 554x554, red artwork on white background).

Outputs (committed to app/src/main/res/):
  mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.png          legacy launcher (48/72/96/144/192)
  mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher_foreground.png  adaptive foreground (108dp canvas)
  mipmap-xhdpi/alutube_tv_banner.png                               TV banner 320x180
  colors.xml ic_launcher_background                                solid background layer

Usage:
  python3 tools/generate_icons.py [source] [res_dir]

Run with the repository root as the working directory.
Dependencies: Pillow (PIL).
"""

import os
import sys
from PIL import Image

# ImageAssetStudio-compatible canvas: adaptive foreground is a 108dp canvas.
# Safe zone for arbitrary mask shapes is the inner 66dp circle; artwork size
# was chosen so the main glyph sits inside it.
FOREGROUND_CANVAS_BASE = 108
LEGACY_BASE = 48  # mdpi legacy icon base
DENSITIES = {
    "mdpi": 1.0,
    "hdpi": 1.5,
    "xhdpi": 2.0,
    "xxhdpi": 3.0,
    "xxxhdpi": 4.0,
}

# Default solid background for the adaptive icon: pure white, matching the
# artwork's own background so masked corners look seamless.
BACKGROUND_HEX = "#FFFFFF"
BACKGROUND_RGB = (255, 255, 255)


def parse_hex(hex_color: str):
    hex_color = hex_color.lstrip("#")
    return tuple(int(hex_color[i:i + 2], 16) for i in (0, 2, 4))


def load_source(path: str) -> Image.Image:
    im = Image.open(path).convert("RGB")
    return im


def content_bbox(im: Image.Image) -> tuple:
    """Bounding box of non-near-white pixels (the artwork glyph)."""
    px = im.load()
    w, h = im.size
    min_x, min_y, max_x, max_y = w, h, -1, -1
    threshold = 235
    for y in range(h):
        for x in range(w):
            r, g, b = px[x, y]
            if not (r > threshold and g > threshold and b > threshold):
                if x < min_x:
                    min_x = x
                if x > max_x:
                    max_x = x
                if y < min_y:
                    min_y = y
                if y > max_y:
                    max_y = y
    print(f"content bbox: ({min_x},{min_y})-({max_x},{max_y}) "
          f"size {max_x - min_x + 1}x{max_y - min_y + 1}")
    return min_x, min_y, max_x, max_y


def generate_legacy(im: Image.Image, size: int, target: str) -> None:
    """Legacy pre-adaptive launcher: full-bleed square, artwork ~90%."""
    canvas = Image.new("RGB", (size, size), BACKGROUND_RGB)
    fit = int(size * 0.90)
    thumb = im.copy()
    thumb.thumbnail((fit, fit), Image.LANCZOS)
    x = (size - thumb.size[0]) // 2
    y = (size - thumb.size[1]) // 2
    canvas.paste(thumb, (x, y))
    canvas.save(target, "PNG")
    print(f"legacy -> {target} ({canvas.size})")


def generate_foreground(im: Image.Image, bbox: tuple, canvas_px: int, target: str) -> None:
    """Adaptive foreground: transparent canvas, artwork placed in safe zone."""
    min_x, min_y, max_x, max_y = bbox
    glyph = im.crop((min_x, min_y, max_x + 1, max_y + 1))

    # Fit the glyph inside ~58% of the canvas (within the 66dp safe zone at 108dp).
    fit = int(canvas_px * 0.58)
    thumb = glyph.copy()
    thumb.thumbnail((fit, fit), Image.LANCZOS)

    canvas = Image.new("RGBA", (canvas_px, canvas_px), (0, 0, 0, 0))
    x = (canvas_px - thumb.size[0]) // 2
    y = (canvas_px - thumb.size[1]) // 2
    canvas.paste(thumb, (x, y))
    # Collapse alpha so compositing against the solid background is predictable.
    canvas.convert("RGBA").save(target, "PNG")
    print(f"foreground -> {target} ({canvas.size})")


def generate_banner(im: Image.Image, target: str, width: int = 320, height: int = 180) -> None:
    """TV banner 16:9: white background + artwork ~78% height."""
    canvas = Image.new("RGB", (width, height), BACKGROUND_RGB)
    fit = int(height * 0.78)
    thumb = im.copy()
    thumb.thumbnail((fit, fit), Image.LANCZOS)
    x = (width - thumb.size[0]) // 2
    y = (height - thumb.size[1]) // 2
    canvas.paste(thumb, (x, y))
    canvas.save(target, "PNG")
    print(f"banner -> {target} ({canvas.size})")


def main() -> int:
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    source = sys.argv[1] if len(sys.argv) > 1 else os.path.join(root, "images.jpeg")
    res_dir = sys.argv[2] if len(sys.argv) > 2 else os.path.join(root, "app", "src", "main", "res")

    if not os.path.exists(source):
        print(f"source not found: {source}")
        return 1

    im = load_source(source)
    bbox = content_bbox(im)
    print(f"source: {source} {im.size}")

    for density, scale in DENSITIES.items():
        mipdir = os.path.join(res_dir, f"mipmap-{density}")
        os.makedirs(mipdir, exist_ok=True)
        legacy_size = int(LEGACY_BASE * scale)
        generate_legacy(im, legacy_size, os.path.join(mipdir, "ic_launcher.png"))
        canvas_px = int(FOREGROUND_CANVAS_BASE * scale)
        generate_foreground(im, bbox, canvas_px, os.path.join(mipdir, "ic_launcher_foreground.png"))

    xhdpi_dir = os.path.join(res_dir, "mipmap-xhdpi")
    os.makedirs(xhdpi_dir, exist_ok=True)
    banner_path = os.path.join(xhdpi_dir, "alutube_tv_banner.png")
    generate_banner(im, banner_path)

    # Update the adaptive background color to match the artwork background.
    colors_path = os.path.join(res_dir, "values", "colors.xml")
    if os.path.exists(colors_path):
        with open(colors_path, encoding="utf-8") as fh:
            colors = fh.read()
        import re
        new_colors = re.sub(
            r'<color name="ic_launcher_background">[^<]*</color>',
            f'<color name="ic_launcher_background">{BACKGROUND_HEX}</color>',
            colors,
        )
        with open(colors_path, "w", encoding="utf-8") as fh:
            fh.write(new_colors)
        print(f"colors.xml: ic_launcher_background -> {BACKGROUND_HEX}")

    print("done.")
    return 0


if __name__ == "__main__":
    sys.exit(main())