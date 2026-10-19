"""Bounded 2D diagrams: data only, no model-generated drawing code."""
import math

import numpy as np
from PIL import Image, ImageDraw

from .renderer import WIDTH, HEIGHT, font, wrapped

PALETTES = {
    "dark": ("#0b1428", "#172641", "#f4f8ff", "#64dfbf", "#8ba5c7"),
    "light": ("#f3f6fb", "#ffffff", "#172641", "#137bba", "#516784"),
    "warm": ("#fff4e6", "#ffe7cb", "#422e27", "#c45832", "#856452"),
}


def validate_visual_layout(scene):
    if len(wrapped(scene.headline, font(48), 584)) > 3:
        raise ValueError("diagram headline exceeds three lines")
    if len(scene.points) > 2 or any(len(wrapped(p, font(30), 568)) > 2 for p in scene.points):
        raise ValueError("diagram supports at most two short points, each two lines")
    if any(not item.label.strip() or len(wrapped(item.label, font(25), 184)) > 2 for item in scene.visual.items):
        raise ValueError("diagram label exceeds two lines")


def positions(layout, n):
    if layout in ("pipeline", "bars"):
        return [(280, 445 + i * 150) for i in range(n)] if layout == "pipeline" else [(130 + i * 460 / max(n - 1, 1), 690) for i in range(n)]
    if layout == "compare":
        return [(200 + (i % 2) * 320, 505 + (i // 2) * 240) for i in range(n)]
    if layout == "split":
        return [(360, 440)] + [(130 + i * 460 / max(n - 2, 1), 720) for i in range(n - 1)]
    if layout == "merge":
        return [(130 + i * 460 / max(n - 2, 1), 440) for i in range(n - 1)] + [(360, 720)]
    return [(360, 590)] + [(360 + math.cos(-math.pi / 2 + i * 2 * math.pi / (n - 1)) * 220,
                                         620 + math.sin(-math.pi / 2 + i * 2 * math.pi / (n - 1)) * 155) for i in range(n - 1)]


def icon(draw, kind, x, y, color):
    box = (x - 32, y - 24, x + 32, y + 24)
    if kind == "agent":
        draw.rounded_rectangle(box, 10, outline=color, width=3)
        for dx in (-12, 12):
            draw.ellipse((x + dx - 4, y - 5, x + dx + 4, y + 3), fill=color)
        draw.line((x - 13, y + 13, x + 13, y + 13), fill=color, width=3)
        draw.line((x, y - 24, x, y - 35), fill=color, width=3)
    elif kind == "person":
        draw.ellipse((x - 13, y - 32, x + 13, y - 6), outline=color, width=3)
        draw.arc((x - 28, y - 2, x + 28, y + 42), 180, 360, fill=color, width=3)
    elif kind == "audio":
        for i, h in enumerate((12, 28, 42, 22, 34)):
            xx = x - 28 + i * 14
            draw.line((xx, y - h / 2, xx, y + h / 2), fill=color, width=5)
    elif kind == "database":
        draw.rectangle((x - 29, y - 20, x + 29, y + 20), outline=color, width=3)
        for dy in (-20, 0, 20):
            draw.ellipse((x - 29, y + dy - 8, x + 29, y + dy + 8), outline=color, width=3)
    else:
        if kind == "cards":
            draw.rounded_rectangle((x - 39, y - 31, x + 24, y + 17), 5, outline=color, width=2)
        if kind == "phone":
            box = (x - 21, y - 34, x + 21, y + 34)
        draw.rounded_rectangle(box, 5, outline=color, width=3)
        if kind in ("video", "phone"):
            draw.polygon(((x - 7, y - 11), (x - 7, y + 11), (x + 12, y)), fill=color)
        else:
            for dy in (-10, 0, 10):
                draw.line((x - 19, y + dy, x + 19, y + dy), fill=color, width=3)
            if kind == "server":
                draw.ellipse((x + 22, y - 4, x + 27, y + 1), fill=color)


def diagram_frame(scene, t):
    visual = scene.visual
    bg, panel, fg, accent, muted = PALETTES[visual.theme]
    image = Image.new("RGB", (WIDTH, HEIGHT), bg)
    draw = ImageDraw.Draw(image)
    draw.rounded_rectangle((32, 100, 688, 1180), 28, fill=panel)
    draw.text((64, 150), "VIDEO TRACE  /  图解", font=font(23), fill=accent)
    for i, line in enumerate(wrapped(scene.headline, font(48), 584)):
        draw.text((64, 205 + i * 65), line, font=font(48), fill=fg)
    coords = positions(visual.layout, len(visual.items))
    progress = min(1.0, max(0.0, t / 1.4))
    edges = []
    if visual.layout == "pipeline":
        edges = [(i, i + 1) for i in range(len(coords) - 1)]
    elif visual.layout in ("split", "orbit"):
        edges = [(0, i) for i in range(1, len(coords))]
    elif visual.layout == "merge":
        edges = [(i, len(coords) - 1) for i in range(len(coords) - 1)]
    for a, b in edges:
        ax, ay = coords[a]; bx, by = coords[b]
        length = math.hypot(bx - ax, by - ay)
        ux, uy = (bx - ax) / length, (by - ay) / length
        start = (ax + ux * 60, ay + uy * 60)
        end = (bx - ux * 60, by - uy * 60)
        p = progress if visual.motion == "draw" else 1
        ex, ey = start[0] + (end[0] - start[0]) * p, start[1] + (end[1] - start[1]) * p
        draw.line((*start, ex, ey), fill=muted, width=3)
        if p >= 1:
            draw.polygon(((ex, ey), (ex - ux * 12 - uy * 6, ey - uy * 12 + ux * 6),
                          (ex - ux * 12 + uy * 6, ey - uy * 12 - ux * 6)), fill=muted)
        q = (t * .4) % 1
        px, py = start[0] + (end[0] - start[0]) * q * p, start[1] + (end[1] - start[1]) * q * p
        draw.ellipse((px - 5, py - 5, px + 5, py + 5), fill=accent)
    for i, (item, (x, y)) in enumerate(zip(visual.items, coords)):
        appear = min(1, max(0, (t - i * .25) / .5))
        if visual.motion == "reveal" and appear <= 0:
            continue
        if visual.motion == "slide":
            x -= (1 - appear) * 65
        if visual.layout == "bars":
            h = item.value * 2.2 * progress
            draw.rounded_rectangle((x - 36, y - h, x + 36, y + 2), 6, fill=accent)
            draw.text((x, y - h - 32), str(item.value) + "%", font=font(23), fill=fg, anchor="mt")
            label_y = y + 20
        else:
            radius = 53 + (3 * math.sin(t * 3 - i) if visual.motion == "pulse" else 0)
            draw.rounded_rectangle((x - radius, y - 49, x + radius, y + 49), 18, fill=bg, outline=accent, width=2)
            icon(draw, item.icon, x, y, accent)
            label_y = y + 54
        for j, line in enumerate(wrapped(item.label, font(25), 184)):
            if visual.layout == "pipeline":
                draw.text((x + 76, y - 16 + j * 34), line, font=font(25), fill=fg)
            else:
                draw.text((x, label_y + j * 34), line, font=font(25), fill=fg, anchor="mt")
    y = 985
    for point in scene.points:
        for line in wrapped(point, font(30), 568):
            draw.text((76, y), line, font=font(30), fill=muted)
            y += 40
        y += 12
    draw.rounded_rectangle((64, 1140, 656, 1146), 3, fill=bg)
    draw.rounded_rectangle((64, 1140, 64 + max(3, 592 * min(t / (scene.durationMs / 1000), 1)), 1146), 3, fill=accent)
    if scene.animation == "fade" and t < .5:
        image = Image.blend(Image.new("RGB", image.size, bg), image, max(0, t / .5))
    return np.asarray(image)
