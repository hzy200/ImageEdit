"""Generate deterministic synthetic test images for the M1 pipeline.

Run with:
    conda run -n agent python tools/test-images/generate_test_images.py

Real photos are still required for portrait/backlight/P3 checks; see README.md.
"""

from __future__ import annotations

import json
import math
import random
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

OUT = Path(__file__).resolve().parent / "out"
OUT.mkdir(parents=True, exist_ok=True)
random.seed(20261103)

SPECS = [
    ("01_small_320x240.jpg", 320, 240, "gradient"),
    ("02_square_1000x1000.jpg", 1000, 1000, "gradient"),
    ("03_gradient_640x480.png", 640, 480, "gradient"),
    ("04_gradient_1920x1080.jpg", 1920, 1080, "gradient"),
    ("05_color_bars_1280x720.png", 1280, 720, "bars"),
    ("06_checker_1024x1024.png", 1024, 1024, "checker"),
    ("07_noise_1200x800.jpg", 1200, 800, "noise"),
    ("08_portrait_1080x1920.png", 1080, 1920, "portrait"),
    ("09_backlight_1600x1200.jpg", 1600, 1200, "backlight"),
    ("10_texture_1400x1000.png", 1400, 1000, "texture"),
    ("11_text_800x600.png", 800, 600, "text"),
    ("12_transparent_800x600.png", 800, 600, "transparent"),
    ("13_wide_2400x1000.jpg", 2400, 1000, "gradient"),
    ("14_tall_1000x2400.jpg", 1000, 2400, "gradient"),
    ("15_12mp_4032x3024.jpg", 4032, 3024, "gradient"),
    ("16_12mp_portrait_3024x4032.jpg", 3024, 4032, "portrait"),
    ("17_24mp_6000x4000.jpg", 6000, 4000, "gradient"),
    ("18_color_bars_3840x2160.jpg", 3840, 2160, "bars"),
    ("19_noise_2000x1500.jpg", 2000, 1500, "noise"),
    ("20_portrait_1440x2560.png", 1440, 2560, "portrait"),
    ("21_checker_2048x1536.png", 2048, 1536, "checker"),
    ("22_texture_1800x1200.jpg", 1800, 1200, "texture"),
    ("23_text_1600x1200.png", 1600, 1200, "text"),
    ("24_transparent_1200x900.png", 1200, 900, "transparent"),
    ("25_exif_orientation_6_1600x1200.jpg", 1600, 1200, "gradient", 6),
    ("26_exif_orientation_8_1600x1200.jpg", 1600, 1200, "gradient", 8),
    ("27_odd_777x555.png", 777, 555, "gradient"),
]


def gradient(width: int, height: int, rgba: bool = False) -> Image.Image:
    image = Image.new("RGBA" if rgba else "RGB", (width, height))
    draw = ImageDraw.Draw(image)
    for y in range(height):
        t = y / max(1, height - 1)
        r = int(20 + 200 * t)
        g = int(80 + 120 * (1 - t))
        b = int(180 - 100 * t)
        draw.line([(0, y), (width, y)], fill=(r, g, b, 255) if rgba else (r, g, b))
    return image


def bars(width: int, height: int) -> Image.Image:
    image = Image.new("RGB", (width, height), "black")
    draw = ImageDraw.Draw(image)
    colors = [
        (255, 255, 255), (255, 255, 0), (0, 255, 255), (0, 255, 0),
        (255, 0, 255), (255, 0, 0), (0, 0, 255), (0, 0, 0),
    ]
    step = width / len(colors)
    for i, color in enumerate(colors):
        draw.rectangle(
            [int(i * step), 0, int((i + 1) * step) - 1, height - 1],
            fill=color,
        )
    return image


def checker(width: int, height: int) -> Image.Image:
    image = Image.new("RGB", (width, height), "white")
    draw = ImageDraw.Draw(image)
    cell = max(8, min(width, height) // 16)
    for y in range(0, height, cell):
        for x in range(0, width, cell):
            if ((x // cell) + (y // cell)) % 2 == 0:
                draw.rectangle([x, y, x + cell - 1, y + cell - 1], fill=(32, 32, 32))
    return image


def noise(width: int, height: int) -> Image.Image:
    base = Image.effect_noise((width, height), 48).convert("RGB")
    return base


def portrait(width: int, height: int) -> Image.Image:
    image = gradient(width, height)
    draw = ImageDraw.Draw(image)
    cx = width // 2
    head_r = int(min(width, height) * 0.12)
    head_y = int(height * 0.32)
    draw.ellipse(
        [cx - head_r, head_y - head_r, cx + head_r, head_y + head_r],
        fill=(224, 186, 160),
    )
    draw.rectangle(
        [cx - int(head_r * 1.8), head_y + head_r, cx + int(head_r * 1.8), int(height * 0.92)],
        fill=(40, 70, 140),
    )
    draw.ellipse(
        [cx - head_r // 3, head_y - head_r // 3, cx + head_r // 3, head_y + head_r // 3],
        fill=(250, 210, 180),
    )
    return image


def backlight(width: int, height: int) -> Image.Image:
    image = Image.new("RGB", (width, height), (245, 235, 210))
    draw = ImageDraw.Draw(image)
    for radius in range(min(width, height) // 2, 0, -12):
        shade = int(255 - radius / max(1, min(width, height) // 2) * 120)
        draw.ellipse(
            [width // 2 - radius, height // 2 - radius, width // 2 + radius, height // 2 + radius],
            fill=(shade, shade - 10, shade - 30),
        )
    draw.ellipse(
        [width // 2 - 80, height // 2 - 120, width // 2 + 80, height // 2 + 40],
        fill=(50, 45, 40),
    )
    draw.rectangle(
        [width // 2 - 160, height // 2 + 30, width // 2 + 160, height - 100],
        fill=(35, 32, 30),
    )
    return image


def texture(width: int, height: int) -> Image.Image:
    image = Image.new("RGB", (width, height), (190, 170, 150))
    draw = ImageDraw.Draw(image)
    step = max(16, min(width, height) // 24)
    for y in range(0, height, step):
        for x in range(0, width, step):
            shade = 130 + random.randint(-30, 30)
            draw.rectangle(
                [x, y, x + step - 1, y + step - 1],
                fill=(shade, shade - 15, shade - 30),
            )
    for x in range(0, width, step * 2):
        draw.line([(x, 0), (x, height)], fill=(90, 70, 55), width=2)
    return image


def text_image(width: int, height: int, label: str) -> Image.Image:
    image = Image.new("RGB", (width, height), (24, 24, 32))
    draw = ImageDraw.Draw(image)
    font = ImageFont.load_default()
    draw.text((24, 24), label, fill=(255, 255, 255), font=font)
    draw.text((24, 24 + 20), f"{width} x {height}", fill=(180, 220, 255), font=font)
    draw.rounded_rectangle(
        [24, 90, width - 24, height - 24],
        radius=24,
        outline=(120, 200, 255),
        width=4,
    )
    return image


def transparent(width: int, height: int) -> Image.Image:
    image = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    draw.ellipse(
        [width * 0.1, height * 0.2, width * 0.7, height * 0.9],
        fill=(255, 80, 80, 190),
    )
    draw.rectangle(
        [width * 0.45, height * 0.1, width * 0.9, height * 0.7],
        fill=(40, 120, 255, 160),
    )
    return image


def make_image(width: int, height: int, kind: str) -> Image.Image:
    if kind == "bars":
        return bars(width, height)
    if kind == "checker":
        return checker(width, height)
    if kind == "noise":
        return noise(width, height)
    if kind == "portrait":
        return portrait(width, height)
    if kind == "backlight":
        return backlight(width, height)
    if kind == "texture":
        return texture(width, height)
    if kind == "text":
        return text_image(width, height, "IMAGE EDIT TEST")
    if kind == "transparent":
        return transparent(width, height)
    return gradient(width, height, rgba=kind == "transparent")


def save(image: Image.Image, path: Path, quality: int, orientation: int | None = None) -> None:
    if orientation is None:
        if path.suffix.lower() == ".png":
            image.save(path, "PNG", optimize=True)
        else:
            image.convert("RGB").save(path, "JPEG", quality=quality, optimize=True)
        return

    exif = Image.Exif()
    exif[274] = orientation
    image.convert("RGB").save(
        path,
        "JPEG",
        quality=quality,
        optimize=True,
        exif=exif,
    )


def main() -> None:
    manifest = []
    for spec in SPECS:
        name, width, height, kind = spec[:4]
        orientation = spec[4] if len(spec) > 4 else None
        path = OUT / name
        image = make_image(width, height, kind)
        save(image, path, quality=88, orientation=orientation)
        manifest.append(
            {
                "file": name,
                "width": width,
                "height": height,
                "pattern": kind,
                "exif_orientation": orientation,
                "bytes": path.stat().st_size,
            }
        )

    (OUT / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    total = sum(item["bytes"] for item in manifest)
    print(f"generated {len(manifest)} images, {total / 1024 / 1024:.1f} MiB")
    for item in manifest:
        print(f"{item['file']:38s} {item['width']}x{item['height']} {item['bytes'] / 1024:.0f} KiB")


if __name__ == "__main__":
    main()
