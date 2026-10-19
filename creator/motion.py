"""Designed launch cards and kinetic typography; model supplies content, not code."""
from functools import lru_cache
from pathlib import Path
import math
import hashlib

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


@lru_cache(maxsize=12)
def backdrop(accent, variant=0):
    y, x = np.mgrid[0:HEIGHT, 0:WIDTH]
    a, b = ACCENTS[accent]
    glow = np.exp(-((x - (600 if variant % 2 == 0 else 100)) ** 2 / 210000 + (y - (200 if variant < 2 else 850)) ** 2 / 400000))
    glow2 = np.exp(-((x - 30) ** 2 / 130000 + (y - 950) ** 2 / 170000))
    pixels = np.zeros((HEIGHT, WIDTH, 3), dtype=np.float32) + (8, 9, 18)
    pixels += glow[:, :, None] * np.array(rgb(a)) * .19
    pixels += glow2[:, :, None] * np.array(rgb(b)) * .075
    image = Image.fromarray(np.uint8(np.clip(pixels, 0, 255))).convert("RGBA")
    if variant == 2:
        pen = ImageDraw.Draw(image)
        for xx in range(0, WIDTH, 80):
            pen.line((xx, 0, xx, HEIGHT), fill=(25, 27, 41, 255))
        for yy in range(0, HEIGHT, 80):
            pen.line((0, yy, WIDTH, yy), fill=(25, 27, 41, 255))
    return image


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


def motion_frame(scene, t, previous=None):
    design = scene.motion
    accent, secondary = ACCENTS[design.accent]
    variant = hashlib.sha256(scene.headline.encode()).digest()[0] % 4
    image = backdrop(design.accent, variant).copy()
    # Gentle camera drift keeps the background moving after the entrance animation.
    drift = round(5 * math.sin(t * .7))
    image = image.crop((12 + drift, 12, WIDTH - 12 + drift, HEIGHT - 12)).resize((WIDTH, HEIGHT), Image.Resampling.BILINEAR)
    duration = scene.durationMs / 1000
    draw = ImageDraw.Draw(image)
    draw.text((48, 58), design.eyebrow.upper(), font=fitted(design.eyebrow.upper(), 20, 550), fill="#a39ac3")

    # Slow ambient shapes keep the frame alive without distracting from the message.
    x = 580 + math.sin(t * .7) * 18
    draw.line((x - 35, 110, x + 20, 165), fill="#625995", width=2)
    if design.style == "launch":
        top = 190 if variant % 2 == 0 else 240
        for i, word in enumerate(design.words):
            word_frame(image, scene, previous, word, i, t, 48, top + i * 128, 108,
                       "#f7f5ff" if i == 0 else accent)
        n = len(design.words)
        caption_y = top + n * 128 + 18
        pen = ImageDraw.Draw(image)
        for i, line in enumerate(wrapped(scene.headline, display_font(28), 600)):
            pen.text((50, caption_y + i * 39), line, font=display_font(28), fill="#c3bed6")
        captions = scene.points or [scene.headline]
        card_y = max(650, caption_y + 112)
        if variant % 2 and len(captions) > 1:
            # Split cards balance the large title; a third item becomes a wide closing card.
            for i, caption in enumerate(captions[:2]):
                card(image, (48 + i * 320, card_y, 352 + i * 320, card_y + 220),
                     design.words[min(i, n - 1)], caption, accent if i == 0 else secondary,
                     (t - .55 - i * .28) / .6)
            if len(captions) == 3:
                card(image, (48, card_y + 236, 672, card_y + 392), design.words[-1],
                     captions[2], secondary, (t - 1.15) / .6)
        else:
            card(image, (48, card_y, 672, card_y + 200), design.words[0], captions[0], accent, (t - .55) / .6, True)
            for i, caption in enumerate(captions[1:3]):
                card(image, (48 + i * 320, card_y + 216, 352 + i * 320, card_y + 392),
                     design.words[min(i + 1, n - 1)], caption, secondary, (t - .9 - i * .32) / .6)
    else:
        words = design.words
        y0 = 280 if len(words) < 3 else 230
        # Repeated outlined lettering supplies hierarchy and rhythmic context.
        text_reveal(image, words[0], 48, y0 - 125, 112, "#514b6e", t / .6, 624, True)
        for i, word in enumerate(words):
            y = y0 + i * 165
            word_frame(image, scene, previous, word, i, t, 48 + (i % 2) * 18, y, 136,
                       accent if i % 2 else "#f5f3ff")
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
    return np.asarray(image.convert("RGB"))


def word_position(scene, index):
    variant = hashlib.sha256(scene.headline.encode()).digest()[0] % 4
    if scene.motion.style == "launch":
        return 48, (190 if variant % 2 == 0 else 240) + index * 128, 108
    return 48 + (index % 2) * 18, (280 if len(scene.motion.words) < 3 else 230) + index * 165, 136


def word_frame(image, scene, previous, word, index, t, x, y, size, color):
    shared = previous and previous.motion and word in previous.motion.words
    if shared and t < .65:
        px, py, ps = word_position(previous, previous.motion.words.index(word))
        p = ease(t / .65)
        text_reveal(image, word, round(px + (x - px) * p), py + (y - py) * p,
                    round(ps + (size - ps) * p), color, 1, 610)
    else:
        # A subtle late lift supplies a second beat without perpetual bouncing.
        beat = ease((t - scene.durationMs / 2000) / .5)
        text_reveal(image, word, x, y - 6 * beat, size, color,
                    1 if shared else (t - index * .25) / .55, 610)


def scene_transition(previous_scene, scene, previous_frame, t, layout=None):
    from .renderer import frame
    shared = (previous_scene and previous_scene.motion and scene.motion
              and set(previous_scene.motion.words) & set(scene.motion.words))
    if not shared:
        return transition_frame(previous_frame, frame(scene, t, layout), t,
                                scene.motion.transition if scene.motion else "fade")
    # Avoid duplicating the moving word in a frozen predecessor screenshot.
    return motion_frame(scene, t, previous_scene)
