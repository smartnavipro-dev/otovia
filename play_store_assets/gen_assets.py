"""
Otovia Play Store Asset Generator
Generates:
  - icon_512.png      (512x512 - Play Store icon)
  - feature_1024.png  (1024x500 - Play Store feature graphic)
  - ic_launcher.png   (1024x1024 - high-res for adaptive icon generation)
"""

import math
import os
from PIL import Image, ImageDraw, ImageFont

# ── Color palette ──────────────────────────────────────────────────
NAVY       = (10,  27,  55)    # #0A1B37 - dark navy background
NAVY_LIGHT = (18,  45,  85)    # #122D55 - slightly lighter navy
TEAL       = (20, 210, 180)    # #14D2B4 - main teal accent
TEAL_DIM   = (14, 160, 135)    # #0EA087 - dimmer teal
WHITE      = (255, 255, 255)
WHITE_DIM  = (200, 215, 235)   # slightly blue-tinted white
GOLD       = (255, 210, 100)   # accent for "auto" easter egg feeling

# ── Font paths ─────────────────────────────────────────────────────
FONT_DIR   = "C:/Windows/Fonts"
FONT_BOLD  = os.path.join(FONT_DIR, "arialbd.ttf")      # Arial Bold
FONT_REG   = os.path.join(FONT_DIR, "arial.ttf")        # Arial Regular
FONT_NOTO  = os.path.join(FONT_DIR, "NotoSansJP-VF.ttf") # Japanese

OUTPUT_DIR = os.path.dirname(os.path.abspath(__file__))


def round_rect(draw, xy, radius, fill):
    """Draw a rounded rectangle."""
    x0, y0, x1, y1 = xy
    draw.rectangle([x0 + radius, y0, x1 - radius, y1], fill=fill)
    draw.rectangle([x0, y0 + radius, x1, y1 - radius], fill=fill)
    draw.ellipse([x0, y0, x0 + 2*radius, y0 + 2*radius], fill=fill)
    draw.ellipse([x1 - 2*radius, y0, x1, y0 + 2*radius], fill=fill)
    draw.ellipse([x0, y1 - 2*radius, x0 + 2*radius, y1], fill=fill)
    draw.ellipse([x1 - 2*radius, y1 - 2*radius, x1, y1], fill=fill)


def draw_sound_wave(draw, cx, cy, amplitude, num_bars, bar_w, color, alpha_base=200):
    """Draw equalizer-style bars centered at (cx, cy)."""
    total_w = num_bars * bar_w + (num_bars - 1) * (bar_w // 2)
    x_start = cx - total_w // 2
    heights = [0.35, 0.55, 0.85, 1.0, 0.80, 0.55, 0.40, 0.65, 0.90, 0.75,
               0.50, 0.35, 0.60, 0.80, 0.45]
    for i in range(num_bars):
        h = int(amplitude * heights[i % len(heights)])
        x = x_start + i * (bar_w + bar_w // 2)
        y0 = cy - h
        y1 = cy + h
        r = bar_w // 2
        # Draw rounded bar
        if h > r:
            draw.ellipse([x, y0, x + bar_w, y0 + bar_w], fill=color)
            draw.ellipse([x, y1 - bar_w, x + bar_w, y1], fill=color)
            draw.rectangle([x, y0 + r, x + bar_w, y1 - r], fill=color)
        else:
            draw.ellipse([x, cy - r, x + bar_w, cy + r], fill=color)


def add_glow(img, glow_color, radius=30, strength=0.4):
    """Add a subtle glow effect by blending a blurred overlay."""
    from PIL import ImageFilter
    glow = Image.new("RGBA", img.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(glow)
    # Draw teal circle in center
    w, h = img.size
    cx, cy = w // 2, h // 2
    r = min(w, h) // 3
    d.ellipse([cx - r, cy - r, cx + r, cy + r],
              fill=(*glow_color[:3], int(255 * strength)))
    glow = glow.filter(ImageFilter.GaussianBlur(radius))
    img = img.convert("RGBA")
    img = Image.alpha_composite(img, glow)
    return img.convert("RGB")


# ══════════════════════════════════════════════════════════════════
#  ICON 512×512
# ══════════════════════════════════════════════════════════════════
def make_icon(size=512):
    img = Image.new("RGB", (size, size), NAVY)
    draw = ImageDraw.Draw(img)

    s = size
    cx, cy = s // 2, s // 2

    # ── Background gradient feel: lighter center circle ───────────
    # Draw concentric circles from outside in (darker to lighter)
    for i in range(20):
        t = i / 20
        r_val = int(s * 0.48 * (1 - t * 0.3))
        blend = tuple(int(NAVY[j] + (NAVY_LIGHT[j] - NAVY[j]) * t) for j in range(3))
        draw.ellipse([cx - r_val, cy - r_val, cx + r_val, cy + r_val], fill=blend)

    # ── Sound wave bars (behind text) ─────────────────────────────
    wave_y = int(s * 0.62)
    wave_color = (*TEAL_DIM, 180)
    # Use RGBA layer for transparency
    overlay = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    ov_draw = ImageDraw.Draw(overlay)
    num_bars = 11
    bar_w = int(s * 0.028)
    amplitude = int(s * 0.065)
    heights = [0.55, 0.75, 1.0, 0.80, 0.60, 0.90, 0.65, 0.85, 0.70, 0.50, 0.40]
    total_w = num_bars * bar_w + (num_bars - 1) * (bar_w // 2)
    x_start = cx - total_w // 2
    bar_color = (*TEAL_DIM, 160)
    for i in range(num_bars):
        h = int(amplitude * heights[i])
        x = x_start + i * (bar_w + bar_w // 2)
        r = bar_w // 2
        if h > r:
            ov_draw.ellipse([x, wave_y - h, x + bar_w, wave_y - h + bar_w], fill=bar_color)
            ov_draw.ellipse([x, wave_y + h - bar_w, x + bar_w, wave_y + h], fill=bar_color)
            ov_draw.rectangle([x, wave_y - h + r, x + bar_w, wave_y + h - r], fill=bar_color)
        else:
            ov_draw.ellipse([x, wave_y - r, x + bar_w, wave_y + r], fill=bar_color)

    img = img.convert("RGBA")
    img = Image.alpha_composite(img, overlay)
    img = img.convert("RGB")
    draw = ImageDraw.Draw(img)

    # ── Main title "Otovia" ────────────────────────────────────────
    font_size = int(s * 0.22)
    try:
        font_title = ImageFont.truetype(FONT_BOLD, font_size)
    except:
        font_title = ImageFont.load_default()

    title = "Otovia"
    bbox = draw.textbbox((0, 0), title, font=font_title)
    tw = bbox[2] - bbox[0]
    th = bbox[3] - bbox[1]
    tx = cx - tw // 2
    ty = int(cy * 0.78) - th // 2

    # Shadow
    draw.text((tx + 3, ty + 3), title, font=font_title, fill=(0, 0, 0))
    # Main text: "O" in slightly brighter teal, rest in teal
    draw.text((tx, ty), title, font=font_title, fill=TEAL)

    # ── Tagline "音の旅" ──────────────────────────────────────────
    tag_size = int(s * 0.07)
    try:
        font_tag = ImageFont.truetype(FONT_NOTO, tag_size)
    except:
        try:
            font_tag = ImageFont.truetype(FONT_REG, tag_size)
        except:
            font_tag = ImageFont.load_default()

    tagline = "音の旅"
    bbox2 = draw.textbbox((0, 0), tagline, font=font_tag)
    tw2 = bbox2[2] - bbox2[0]
    ty2 = ty + th + int(s * 0.02)
    draw.text((cx - tw2 // 2, ty2), tagline, font=font_tag, fill=WHITE_DIM)

    # ── Teal accent line under tagline ────────────────────────────
    line_y = ty2 + (bbox2[3] - bbox2[1]) + int(s * 0.03)
    line_w = int(s * 0.25)
    draw.rectangle([cx - line_w // 2, line_y, cx + line_w // 2, line_y + int(s * 0.008)],
                   fill=TEAL)

    # Save
    out_path = os.path.join(OUTPUT_DIR, "icon_512.png")
    img.save(out_path, "PNG")
    print(f"Saved: {out_path}")
    return img


# ══════════════════════════════════════════════════════════════════
#  FEATURE GRAPHIC 1024×500
# ══════════════════════════════════════════════════════════════════
def make_feature(w=1024, h=500):
    img = Image.new("RGB", (w, h), NAVY)
    draw = ImageDraw.Draw(img)

    cx, cy = w // 2, h // 2

    # ── Background: gradient-like circles on the right ────────────
    for i in range(15):
        t = i / 15
        r = int(h * 0.9 * (1 - t * 0.25))
        blend = tuple(int(NAVY[j] + (NAVY_LIGHT[j] - NAVY[j]) * t * 0.7) for j in range(3))
        draw.ellipse([w * 0.65 - r, cy - r, w * 0.65 + r, cy + r], fill=blend)

    # ── Sound wave (right side) ────────────────────────────────────
    overlay = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    ov_draw = ImageDraw.Draw(overlay)
    num_bars = 18
    bar_w = 14
    amplitude_base = int(h * 0.28)
    total_w = num_bars * bar_w + (num_bars - 1) * 7
    wave_cx = int(w * 0.78)
    wave_cy = cy
    x_start = wave_cx - total_w // 2
    heights = [0.3, 0.5, 0.7, 0.95, 0.8, 0.6, 1.0, 0.75, 0.55, 0.85,
               0.65, 0.45, 0.9, 0.7, 0.5, 0.35, 0.6, 0.4]
    for i in range(num_bars):
        amp = int(amplitude_base * heights[i])
        x = x_start + i * (bar_w + 7)
        alpha = int(160 + 60 * heights[i])
        bc = (*TEAL, alpha)
        r = bar_w // 2
        if amp > r:
            ov_draw.ellipse([x, wave_cy - amp, x + bar_w, wave_cy - amp + bar_w], fill=bc)
            ov_draw.ellipse([x, wave_cy + amp - bar_w, x + bar_w, wave_cy + amp], fill=bc)
            ov_draw.rectangle([x, wave_cy - amp + r, x + bar_w, wave_cy + amp - r], fill=bc)
        else:
            ov_draw.ellipse([x, wave_cy - r, x + bar_w, wave_cy + r], fill=bc)

    img = img.convert("RGBA")
    img = Image.alpha_composite(img, overlay)
    img = img.convert("RGB")
    draw = ImageDraw.Draw(img)

    # ── Left side: text content ────────────────────────────────────
    left_cx = int(w * 0.3)
    text_top = int(h * 0.2)

    # App name
    fn_title = int(h * 0.22)
    try:
        font_title = ImageFont.truetype(FONT_BOLD, fn_title)
    except:
        font_title = ImageFont.load_default()

    draw.text((left_cx - 10, text_top - 3), "Otovia", font=font_title, fill=(0, 0, 0))
    draw.text((left_cx - 10, text_top), "Otovia", font=font_title, fill=TEAL)

    # Tagline Japanese
    fn_tag = int(h * 0.075)
    try:
        font_tag = ImageFont.truetype(FONT_NOTO, fn_tag)
    except:
        try:
            font_tag = ImageFont.truetype(FONT_REG, fn_tag)
        except:
            font_tag = ImageFont.load_default()

    tag_y = text_top + fn_title + int(h * 0.04)
    draw.text((left_cx - 10, tag_y), "電子書籍を耳で読む", font=font_tag, fill=WHITE_DIM)

    # Feature bullets
    fn_bullet = int(h * 0.055)
    try:
        font_bullet = ImageFont.truetype(FONT_NOTO, fn_bullet)
    except:
        try:
            font_bullet = ImageFont.truetype(FONT_REG, fn_bullet)
        except:
            font_bullet = ImageFont.load_default()

    bullets = [
        "OCR + AI自動補正",
        "自動ページ送り",
        "バックグラウンド先読み",
        "自動学習・パターン昇格",
    ]
    bullet_y = tag_y + fn_tag + int(h * 0.07)
    line_gap = int(fn_bullet * 1.7)
    for b in bullets:
        # Teal dot
        dot_r = int(fn_bullet * 0.22)
        dot_y = bullet_y + fn_bullet // 2
        draw.ellipse([left_cx - 10, dot_y - dot_r, left_cx - 10 + dot_r * 2, dot_y + dot_r],
                     fill=TEAL)
        draw.text((left_cx + dot_r * 2, bullet_y), b, font=font_bullet, fill=WHITE_DIM)
        bullet_y += line_gap

    # Teal divider line
    line_x = int(w * 0.52)
    draw.rectangle([line_x, int(h * 0.15), line_x + 2, int(h * 0.85)], fill=TEAL_DIM)

    # Save
    out_path = os.path.join(OUTPUT_DIR, "feature_1024.png")
    img.save(out_path, "PNG", quality=95)
    print(f"Saved: {out_path}")
    return img


# ══════════════════════════════════════════════════════════════════
#  ANDROID ADAPTIVE ICON (foreground layer 1024×1024)
# ══════════════════════════════════════════════════════════════════
def make_adaptive_foreground(size=1024):
    """
    Transparent foreground for adaptive icon.
    Safe zone: center 66% (leave 17% margin each side).
    """
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)

    cx, cy = size // 2, size // 2
    safe = int(size * 0.66)  # safe zone diameter

    # "Otovia" text
    fn = int(safe * 0.33)
    try:
        font = ImageFont.truetype(FONT_BOLD, fn)
    except:
        font = ImageFont.load_default()

    title = "Otovia"
    bbox = draw.textbbox((0, 0), title, font=font)
    tw = bbox[2] - bbox[0]
    th = bbox[3] - bbox[1]
    tx = cx - tw // 2
    ty = int(cy * 0.82) - th // 2
    draw.text((tx, ty), title, font=font, fill=(*TEAL, 255))

    # Tagline
    fn2 = int(safe * 0.10)
    try:
        font2 = ImageFont.truetype(FONT_NOTO, fn2)
    except:
        try:
            font2 = ImageFont.truetype(FONT_REG, fn2)
        except:
            font2 = ImageFont.load_default()
    tag = "音の旅"
    bbox2 = draw.textbbox((0, 0), tag, font=font2)
    tw2 = bbox2[2] - bbox2[0]
    ty2 = ty + th + int(size * 0.02)
    draw.text((cx - tw2 // 2, ty2), tag, font=font2, fill=(*WHITE_DIM, 200))

    out_path = os.path.join(OUTPUT_DIR, "ic_launcher_foreground_1024.png")
    img.save(out_path, "PNG")
    print(f"Saved: {out_path}")
    return img


if __name__ == "__main__":
    print("Generating Otovia Play Store assets...")
    make_icon()
    make_feature()
    make_adaptive_foreground()
    print("Done!")
