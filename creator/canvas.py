"""Render a bounded scene graph with keyframes; never evaluate model code."""
import math
from functools import lru_cache

import numpy as np
from PIL import Image, ImageDraw

from .renderer import WIDTH, HEIGHT, font, wrapped
from .visuals import icon


def states(element):
    state = {"x": element.x, "y": element.y, "scale": 1., "opacity": element.opacity}
    result = [(0, state.copy(), "linear")]
    for key in element.keyframes:
        state.update({k: v for k, v in key.model_dump().items() if k in state and v is not None})
        if key.timeMs == 0:
            result[0] = (0, state.copy(), key.easing)
        else:
            result.append((key.timeMs, state.copy(), key.easing))
    return result


def state_at(element, time_ms):
    timeline = states(element)
    for (start, a, _), (end, b, easing) in zip(timeline, timeline[1:]):
        if time_ms <= end:
            p = min(1., max(0., (time_ms - start) / (end - start)))
            if easing == "easeInOut":
                p = p * p * (3 - 2 * p)
            elif easing == "easeOut":
                p = 1 - (1 - p) ** 3
            return {key: a[key] + (b[key] - a[key]) * p for key in a}
    return timeline[-1][1]


def validate_canvas_layout(scene):
    if len(wrapped(scene.headline, font(46), 592)) > 3:
        raise ValueError("canvas headline exceeds three lines")
    if len(scene.points) > 2 or any(len(wrapped(p, font(28), 580)) > 2 for p in scene.points):
        raise ValueError("canvas supports two short footer points, each two lines")
    for element in scene.canvas.elements:
        connector = element.kind in ("line", "arrow")
        if element.kind == "text" and not element.text.strip():
            raise ValueError(f"canvas {element.id}: text must be nonblank")
        if element.kind in ("icon", "line", "arrow") and element.text:
            raise ValueError(f"canvas {element.id}: use a separate text element for labels")
        if element.text:
            lines = wrapped(element.text, font(element.fontSize), element.width - 12)
            if len(lines) * element.fontSize * 1.35 > element.height - 8 or any(font(element.fontSize).getlength(c) > element.width - 12 for c in element.text):
                raise ValueError(f"canvas {element.id}: text overflows element; enlarge its box or shorten text")
        if not connector:
            # Monotone easing keeps the entire interpolated box within endpoint bounds.
            for _, state, _ in states(element):
                w, h = element.width * state["scale"], element.height * state["scale"]
                if state["x"] - w / 2 < 40 or state["x"] + w / 2 > 680 or state["y"] - h / 2 < 360 or state["y"] + h / 2 > 980:
                    raise ValueError(f"canvas {element.id}: animated bounds must stay inside x=40..680, y=360..980")


@lru_cache(maxsize=64)
def cached_node(serialized):
    from .canvas_models import CanvasElement
    element = CanvasElement.model_validate_json(serialized)
    w, h = round(element.width), round(element.height)
    image = Image.new("RGBA", (w, h))
    draw = ImageDraw.Draw(image)
    if element.kind == "rectangle":
        draw.rounded_rectangle((1, 1, w - 2, h - 2), min(22, w / 5, h / 5), fill=element.fill, outline=element.color, width=2)
    elif element.kind == "circle":
        draw.ellipse((1, 1, w - 2, h - 2), fill=element.fill, outline=element.color, width=2)
    elif element.kind == "icon":
        glyph = Image.new("RGBA", (96, 96))
        icon(ImageDraw.Draw(glyph), element.icon, 48, 48, element.color)
        glyph.thumbnail((w, h), Image.Resampling.LANCZOS)
        image.alpha_composite(glyph, ((w - glyph.width) // 2, (h - glyph.height) // 2))
    if element.text:
        lines = wrapped(element.text, font(element.fontSize), element.width - 12)
        step = element.fontSize * 1.35
        for i, line in enumerate(lines):
            draw.text((w / 2, h / 2 + (i - (len(lines) - 1) / 2) * step), line,
                      font=font(element.fontSize), fill=element.color, anchor="mm")
    return image


def canvas_frame(scene, t):
    canvas = scene.canvas
    image = Image.new("RGBA", (WIDTH, HEIGHT), canvas.background)
    draw = ImageDraw.Draw(image)
    draw.text((64, 110), "VIDEO TRACE  /  自由画布", font=font(23), fill="#64dfbf")
    for i, line in enumerate(wrapped(scene.headline, font(46), 592)):
        draw.text((64, 165 + i * 60), line, font=font(46), fill="#f4f8ff")
    elements = {e.id: e for e in canvas.elements}
    current = {e.id: state_at(e, t * 1000) for e in canvas.elements}
    for element in sorted(canvas.elements, key=lambda e: e.z):
        state = current[element.id]
        opacity = state["opacity"]
        if element.kind in ("line", "arrow"):
            a, b = current[element.fromId], current[element.toId]
            opacity *= min(a["opacity"], b["opacity"])
            ax, ay, bx, by = a["x"], a["y"], b["x"], b["y"]
            distance = math.hypot(bx - ax, by - ay)
            if distance < 1 or opacity <= 0:
                continue
            ux, uy = (bx - ax) / distance, (by - ay) / distance
            def radius(node, s):
                return min(node.width / (2 * max(abs(ux), .0001)), node.height / (2 * max(abs(uy), .0001))) * s["scale"] + 8
            start, end = radius(elements[element.fromId], a), radius(elements[element.toId], b)
            if start + end >= distance:
                continue
            ax += ux * start; ay += uy * start
            bx -= ux * end; by -= uy * end
            layer = Image.new("RGBA", image.size)
            pen = ImageDraw.Draw(layer)
            pen.line((ax, ay, bx, by), fill=element.color, width=3)
            if element.kind == "arrow":
                pen.polygon(((bx, by), (bx - ux * 14 - uy * 7, by - uy * 14 + ux * 7),
                             (bx - ux * 14 + uy * 7, by - uy * 14 - ux * 7)), fill=element.color)
            layer.putalpha(layer.getchannel("A").point(lambda v: round(v * opacity)))
            image.alpha_composite(layer)
        elif opacity > 0:
            layer = cached_node(element.model_dump_json(exclude={"keyframes"})).copy()
            layer = layer.resize((max(1, round(element.width * state["scale"])), max(1, round(element.height * state["scale"]))), Image.Resampling.LANCZOS)
            layer.putalpha(layer.getchannel("A").point(lambda v: round(v * opacity)))
            image.alpha_composite(layer, (round(state["x"] - layer.width / 2), round(state["y"] - layer.height / 2)))
    draw = ImageDraw.Draw(image)
    y = 1030
    for point in scene.points:
        for line in wrapped(point, font(28), 580):
            draw.text((64, y), line, font=font(28), fill="#9aacc5")
            y += 37
        y += 10
    draw.line((64, 1230, 656, 1230), fill="#172641", width=4)
    draw.line((64, 1230, 64 + 592 * min(1, max(0, t * 1000 / scene.durationMs)), 1230), fill="#64dfbf", width=4)
    return np.asarray(image.convert("RGB"))
