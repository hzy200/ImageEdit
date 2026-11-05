"""Generate M2 regression samples.

Includes Display-P3 ICC, sRGB ICC, a stylized portrait with hair strands,
semi-transparent glass, and garment silhouettes with plaid/floral patterns.

Run:
    conda run -n agent python tools/test-images/generate_m2_samples.py
"""

from __future__ import annotations

import random
from pathlib import Path

from PIL import Image, ImageCms, ImageDraw, ImageFilter

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "tools/test-images/out"
PROFILES = ROOT / "tools/test-images/profiles"
OUT.mkdir(parents=True, exist_ok=True)
random.seed(20261103)


def color_chart(width: int, height: int) -> Image.Image:
    image = Image.new("RGB", (width, height), (24, 24, 28))
    draw = ImageDraw.Draw(image)
    colors = [
        (230, 40, 40), (240, 140, 30), (240, 220, 40), (60, 200, 80),
        (30, 180, 220), (40, 80, 230), (180, 60, 220), (250, 250, 250),
        (160, 160, 160), (90, 90, 90), (40, 40, 40), (10, 10, 10),
    ]
    cols = 4
    cell_w = width // cols
    cell_h = height // ((len(colors) + cols - 1) // cols)
    for index, color in enumerate(colors):
        x = (index % cols) * cell_w
        y = (index // cols) * cell_h
        draw.rectangle([x, y, x + cell_w - 4, y + cell_h - 4], fill=color)
    return image


def hair_portrait(width: int, height: int) -> Image.Image:
    image = Image.new("RGB", (width, height), (210, 220, 230))
    draw = ImageDraw.Draw(image)
    for y in range(height):
        t = y / max(1, height - 1)
        draw.line([(0, y), (width, y)], fill=(int(210 - 60 * t), int(220 - 50 * t), int(230 - 30 * t)))

    cx = width // 2
    head_r = int(min(width, height) * 0.14)
    head_y = int(height * 0.33)
    # hair mass
    draw.ellipse(
        [cx - head_r * 1.5, head_y - head_r * 1.4, cx + head_r * 1.5, head_y + head_r * 1.9],
        fill=(35, 28, 26),
    )
    # hair strands
    for index in range(160):
        angle = random.uniform(-1.1, 1.1) - 1.57
        length = random.uniform(head_r * 1.2, head_r * 3.2)
        start_x = cx + random.uniform(-head_r * 1.25, head_r * 1.25)
        start_y = head_y - head_r * random.uniform(0.6, 1.2)
        end_x = start_x + random.uniform(-head_r * 0.6, head_r * 0.6)
        end_y = start_y + length
        shade = random.randint(20, 70)
        draw.line([(start_x, start_y), (end_x, end_y)], fill=(shade, shade - 5, shade - 8), width=random.randint(1, 3))

    # face
    draw.ellipse(
        [cx - head_r, head_y - head_r, cx + head_r, head_y + head_r],
        fill=(226, 184, 156),
    )
    draw.ellipse(
        [cx - head_r * 0.65, head_y - head_r * 0.2, cx - head_r * 0.25, head_y + head_r * 0.15],
        fill=(40, 35, 35),
    )
    draw.ellipse(
        [cx + head_r * 0.25, head_y - head_r * 0.2, cx + head_r * 0.65, head_y + head_r * 0.15],
        fill=(40, 35, 35),
    )
    draw.arc(
        [cx - head_r * 0.35, head_y + head_r * 0.15, cx + head_r * 0.35, head_y + head_r * 0.7],
        10,
        170,
        fill=(150, 70, 70),
        width=4,
    )
    # neck / shoulders
    draw.rectangle(
        [cx - head_r * 0.42, head_y + head_r * 0.8, cx + head_r * 0.42, head_y + head_r * 1.4],
        fill=(210, 165, 140),
    )
    draw.rectangle(
        [cx - head_r * 2.2, head_y + head_r * 1.35, cx + head_r * 2.2, height],
        fill=(44, 78, 130),
    )
    return image


def glass(width: int, height: int) -> Image.Image:
    image = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    # background hint
    draw.rectangle([0, 0, width, height], fill=(238, 242, 246, 255))
    # glass body
    draw.rounded_rectangle(
        [width * 0.3, height * 0.18, width * 0.7, height * 0.9],
        radius=width * 0.08,
        fill=(180, 220, 235, 80),
        outline=(230, 250, 255, 200),
        width=6,
    )
    # water
    draw.rounded_rectangle(
        [width * 0.32, height * 0.45, width * 0.68, height * 0.88],
        radius=width * 0.06,
        fill=(90, 160, 210, 90),
    )
    # highlights
    draw.line(
        [(width * 0.38, height * 0.24), (width * 0.36, height * 0.8)],
        fill=(255, 255, 255, 190),
        width=10,
    )
    draw.line(
        [(width * 0.62, height * 0.3), (width * 0.64, height * 0.75)],
        fill=(255, 255, 255, 110),
        width=5,
    )
    return image


def dress(width: int, height: int, floral: bool) -> Image.Image:
    image = Image.new("RGB", (width, height), (232, 232, 236))
    draw = ImageDraw.Draw(image)
    cx = width // 2
    mask = Image.new("L", (width, height), 0)
    mask_draw = ImageDraw.Draw(mask)
    points = [
        (cx - width * 0.18, height * 0.18),
        (cx - width * 0.30, height * 0.45),
        (cx - width * 0.42, height * 0.92),
        (cx + width * 0.42, height * 0.92),
        (cx + width * 0.30, height * 0.45),
        (cx + width * 0.18, height * 0.18),
        (cx + width * 0.10, height * 0.10),
        (cx - width * 0.10, height * 0.10),
    ]
    mask_draw.polygon(points, fill=255)

    pattern = Image.new("RGB", (width, height), (190, 40, 60))
    pdraw = ImageDraw.Draw(pattern)
    if floral:
        for _ in range(220):
            x = random.randint(0, width)
            y = random.randint(0, height)
            r = random.randint(4, 14)
            color = random.choice([(255, 220, 120), (250, 250, 250), (240, 140, 170)])
            for angle in range(0, 360, 60):
                px = x + r * __import__("math").cos(angle * 3.14159 / 180)
                py = y + r * __import__("math").sin(angle * 3.14159 / 180)
                pdraw.ellipse([px - r * 0.55, py - r * 0.55, px + r * 0.55, py + r * 0.55], fill=color)
            pdraw.ellipse([x - 3, y - 3, x + 3, y + 3], fill=(255, 240, 140))
    else:
        for x in range(-height, width, 64):
            pdraw.line([(x, 0), (x + height, height)], fill=(80, 40, 140), width=14)
            pdraw.line([(x + 32, 0), (x + 32 + height, height)], fill=(240, 200, 80), width=8)
        for x in range(0, width, 64):
            pdraw.line([(x, 0), (x, height)], fill=(80, 40, 140), width=5)
        for y in range(0, height, 64):
            pdraw.line([(0, y), (width, y)], fill=(80, 40, 140), width=5)

    image.paste(pattern, (0, 0), mask)
    # folds / shadows
    fold = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    fdraw = ImageDraw.Draw(fold)
    for _ in range(30):
        x = random.randint(int(cx - width * 0.4), int(cx + width * 0.4))
        fdraw.line(
            [(x, height * 0.3), (x + random.randint(-40, 40), height * 0.94)],
            fill=(0, 0, 0, random.randint(20, 55)),
            width=random.randint(4, 14),
        )
    fold = fold.filter(ImageFilter.GaussianBlur(3))
    image = Image.alpha_composite(image.convert("RGBA"), fold).convert("RGB")

    # neckline
    draw = ImageDraw.Draw(image)
    draw.arc(
        [cx - width * 0.16, height * 0.06, cx + width * 0.16, height * 0.24],
        0,
        180,
        fill=(232, 232, 236),
        width=8,
    )
    return image


def save_with_profile(image: Image.Image, path: Path, profile: ImageCms.ImageCmsProfile) -> None:
    image.convert("RGB").save(
        path,
        "JPEG",
        quality=92,
        optimize=True,
        icc_profile=profile.tobytes(),
    )


def main() -> None:
    srgb_profile = ImageCms.getOpenProfile(str(PROFILES / "sRGB-v2-magic.icc"))
    p3_profile = ImageCms.getOpenProfile(str(PROFILES / "DisplayP3-v4.icc"))

    chart = color_chart(1600, 1200)
    save_with_profile(chart, OUT / "28_srgb_icc_1600x1200.jpg", srgb_profile)
    p3_chart = ImageCms.profileToProfile(
        chart,
        ImageCms.createProfile("sRGB"),
        p3_profile,
        outputMode="RGB",
    )
    save_with_profile(p3_chart, OUT / "29_displayp3_1600x1200.jpg", p3_profile)

    hair_portrait(1600, 2000).save(OUT / "30_complex_portrait_1600x2000.png")
    glass(1200, 900).save(OUT / "31_glass_1200x900.png")
    dress(1200, 1600, floral=False).save(OUT / "32_garment_plaid_1200x1600.png")
    dress(1200, 1600, floral=True).save(OUT / "33_garment_floral_1200x1600.png")
    print("generated M2 samples in", OUT)


if __name__ == "__main__":
    main()
