"""Designed launch cards and kinetic typography; model supplies content, not code."""
from functools import lru_cache
from pathlib import Path
import math

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

from .renderer import WIDTH, HEIGHT, font_path, wrapped

ACCENTS = {"violet": ("#9c8bff", "#57e5ef"), "cyan": ("#54e5ef", "#bba4ff"), "lime": ("#b6fb68", "#62e5ce")}


@lru_cache(maxsize=32)
def display_font(size):
    configured = Path(font_path())
    bold = configured.with_name("msyhbd.ttc") if configured.name.lower() == "msyh.ttc" else configured
    return ImageFont.truetype(str(bold if bold.is_file() else configured), size)


def fitted(text, size, width):
    while size > 24 and display_font(size).getlength(text) > width:
        size -= 2
    return display_font(size)


def rgb(value):
    return tuple(int(value[i:i + 2], 16) for i in (1, 3, 5))


@lru_cache(maxsize=3)
def backdrop(accent):
    y, x = np.mgrid[0:HEIGHT, 0:WIDTH]
    a, b = ACCENTS[accent]
    glow = np.exp(-((x - 600) ** 2 / 210000 + (y - 200) ** 2 / 400000))
    glow2 = np.exp(-((x - 30) ** 2 / 130000 + (y - 950) ** 2 / 170000))
    pixels = np.zeros((HEIGHT, WIDTH, 3), dtype=np.float32) + (8, 9, 18)
    pixels += glow[:, :, None] * np.array(rgb(a)) * .19
    pixels += glow2[:, :, None] * np.array(rgb(b)) * .075
    return Image.fromarray(np.uint8(np.clip(pixels, 0, 255))).convert("RGBA")


def ease(value):
    return 1 - (1 - min(1, max(0, value))) ** 3


def transition_frame(previous, current, t, effect):
    if previous is None or t >= .3:
        return current
    p = ease(t / .3)
    if effect == "fade":
        return np.asarray(Image.blend(Image.fromarray(previous), Image.fromarray(current), p))
    shift = round(WIDTH * p)
    if effect == "push":
        return np.concatenate((previous[:, shift:], current[:, :shift]), axis=1)
    result = previous.copy()
    result[:, :shift] = current[:, :shift]
    return result


def validate_motion_layout(scene):
    if len(scene.points) > 3 or any(len(p) > 28 for p in scene.points):
        raise ValueError("motion supports at most three card captions, each at most 28 characters")
    if len(wrapped(scene.headline, display_font(30), 600)) > 2:
        raise ValueError("motion headline exceeds two caption lines")


def text_reveal(image, text, x, y, size, color, progress, width=600, outline=False):
    selected = fitted(text, size, width)
    box = selected.getbbox(text)
    h = box[3] - box[1] + 30
    layer = Image.new("RGBA", (WIDTH, h + 20))
    draw = ImageDraw.Draw(layer)
    p = ease(progress)
    draw.text((x, 10 - box[1] + round((1 - p) * h)), text, font=selected,
              fill=(0, 0, 0, 0) if outline else color, stroke_width=1 if outline else 0, stroke_fill=color)
    image.alpha_composite(layer, (0, round(y)))


def card(image, box, title, subtitle, accent, progress, feature=False):
    x0, y0, x1, y1 = box
    p = ease(progress)
    layer = Image.new("RGBA", image.size)
    draw = ImageDraw.Draw(layer)
    y0 += round((1 - p) * 35); y1 += round((1 - p) * 35)
    draw.rounded_rectangle((x0, y0, x1, y1), 24, fill="#18172e" if feature else "#10121e", outline="#393552", width=1)
    draw.rounded_rectangle((x0 + 22, y0 + 24, x0 + 58, y0 + 28), 2, fill=accent)
    # The orbit is decorative, not an invented data visualization.
    if feature:
        draw.ellipse((x1 - 205, y0 + 24, x1 + 35, y1 + 55), outline="#343059", width=22)
        draw.ellipse((x1 - 163, y0 + 55, x1 - 12, y1 + 18), outline=accent, width=2)
    draw.text((x0 + 22, y0 + 52), title, font=fitted(title, 38 if feature else 30, x1 - x0 - 44), fill="#f7f5ff")
    caption_size = 22
    while caption_size > 16 and len(wrapped(subtitle, display_font(caption_size), x1 - x0 - 44)) > 2:
        caption_size -= 1
    for i, line in enumerate(wrapped(subtitle, display_font(caption_size), x1 - x0 - 44)):
        draw.text((x0 + 22, y1 - 75 + i * 31), line, font=display_font(caption_size), fill="#a5a4bd")
    layer.putalpha(layer.getchannel("A").point(lambda v: round(v * p)))
    image.alpha_composite(layer)


def motion_frame(scene, t):
    design = scene.motion
    accent, secondary = ACCENTS[design.accent]
    image = backdrop(design.accent).copy()
    duration = scene.durationMs / 1000
    draw = ImageDraw.Draw(image)
    draw.text((48, 58), design.eyebrow.upper(), font=fitted(design.eyebrow.upper(), 20, 550), fill="#a39ac3")
    draw.text((622, 54), "↗", font=display_font(28), fill="#eeeaff")
    # Slow ambient shapes keep the frame alive without distracting from the message.
    x = 580 + math.sin(t * .7) * 18
    draw.line((x - 35, 110, x + 20, 165), fill="#625995", width=2)
    if design.style == "launch":
        top = 190
        for i, word in enumerate(design.words):
            text_reveal(image, word, 48, top + i * 128, 108, "#f7f5ff" if i == 0 else accent,
                        (t - i * .19) / .55, 624)
        n = len(design.words)
        caption_y = top + n * 128 + 18
        pen = ImageDraw.Draw(image)
        for i, line in enumerate(wrapped(scene.headline, display_font(28), 600)):
            pen.text((50, caption_y + i * 39), line, font=display_font(28), fill="#c3bed6")
        captions = scene.points or [scene.headline]
        card_y = max(650, caption_y + 112)
        card(image, (48, card_y, 672, card_y + 200), "01 / " + design.words[0], captions[0], accent, (t - .55) / .6, True)
        for i, caption in enumerate(captions[1:3]):
            card(image, (48 + i * 320, card_y + 216, 352 + i * 320, card_y + 392),
                 f"0{i+2}", caption, secondary, (t - .9 - i * .15) / .6)
    else:
        words = design.words
        y0 = 280 if len(words) < 3 else 230
        # Repeated outlined lettering supplies hierarchy and rhythmic context.
        text_reveal(image, words[0], 48, y0 - 125, 112, "#514b6e", t / .6, 624, True)
        for i, word in enumerate(words):
            y = y0 + i * 165
            text_reveal(image, word, 48 + (i % 2) * 18, y, 136, accent if i % 2 else "#f5f3ff",
                        (t - i * .28) / .55, 610)
        pen = ImageDraw.Draw(image)
        highlight = min(len(words) - 1, int(max(0, t - .8) / max(.55, (duration - 1) / len(words))))
        yy = y0 + highlight * 165 + 140
        p = ease((t - .6) / .6)
        pen.rounded_rectangle((48, yy, 48 + max(4, 570 * p), yy + 7), 3, fill=secondary)
        # Masked stripe crosses the lettering, echoing the split-type reference.
        if 1.0 < t < 1.55:
            stripe_y = y0 + (t - 1.0) / .55 * (len(words) * 165)
            strip = Image.new("RGBA", image.size)
            ImageDraw.Draw(strip).rectangle((48, stripe_y, 672, stripe_y + 12), fill=accent)
            image = Image.alpha_composite(image, strip)
        for i, line in enumerate(wrapped(scene.headline, display_font(30), 600)):
            ImageDraw.Draw(image).text((50, 965 + i * 43), line, font=display_font(30), fill="#c6c1d8")
        if scene.points:
            line = scene.points[0]
            ImageDraw.Draw(image).text((50, 1090), line, font=fitted(line, 23, 600), fill="#8f8aa5")
    draw = ImageDraw.Draw(image)
    draw.text((48, 1200), "VIDEOTRACE  /  CREATE IN MOTION", font=display_font(15), fill="#77718e")
    draw.line((48, 1174, 672, 1174), fill="#28243b", width=2)
    draw.line((48, 1174, 48 + 624 * min(1, max(0, t / duration)), 1174), fill=accent, width=2)
    return np.asarray(image.convert("RGB"))
