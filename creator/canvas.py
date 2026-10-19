"""Render a bounded scene graph with keyframes; never evaluate model code."""
import math
from functools import lru_cache

import numpy as np
from PIL import Image, ImageDraw

from .renderer import WIDTH, HEIGHT, font, wrapped
from .visuals import icon


def camera_at(canvas, time_ms):
    a = {"x": WIDTH/2, "y": HEIGHT/2, "zoom": 1.}
    start = 0
    for key in canvas.camera:
        b = {"x": key.x, "y": key.y, "zoom": key.zoom}
        if key.timeMs == 0:
            a = b
            continue
        if time_ms <= key.timeMs:
            p = min(1., max(0., (time_ms-start)/(key.timeMs-start)))
            if key.easing == "easeInOut":
                p = p*p*(3-2*p)
            elif key.easing == "easeOut":
                p = 1-(1-p)**3
            return {name: a[name]+(b[name]-a[name])*p for name in a}
        start, a = key.timeMs, b
    return a


def states(element):
    state = {"x": element.x, "y": element.y, "scale": element.scale, "opacity": element.opacity, "rotation": element.rotation}
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
            result = {key: a[key] + (b[key] - a[key]) * p for key in a}
            break
    else:
        result = timeline[-1][1].copy()
    if element.curve and element.kind != "path":
        result["x"], result["y"] = curve_position(element.curve, time_ms)
    if element.pulse:
        result["scale"] *= 1 + .06 * math.sin(max(0, time_ms) / 1000 * math.tau)
    return result


def curve_position(curve, time_ms):
    p = max(0., (time_ms - curve.startMs) / (curve.endMs - curve.startMs))
    p = p % 1 if curve.loop and time_ms >= curve.startMs else min(1., p)
    return bezier(curve, p)


def bezier(curve, p):
    a, b, c, d = [(point.x, point.y) for point in curve.points]
    return tuple((1-p)**3*a[i] + 3*(1-p)**2*p*b[i] + 3*(1-p)*p*p*c[i] + p**3*d[i] for i in (0, 1))


def continued_scene(scene, previous):
    """Resolve inherited state before layout validation, cache hashing and rendering."""
    if not (previous and previous.canvas and scene.canvas and scene.canvas.continuePrevious):
        return scene
    old = {e.id: e for e in previous.canvas.elements if e.kind not in ("line", "arrow", "path")}
    elements = []
    for node in scene.canvas.elements:
        prior = old.get(node.id)
        if prior and node.space == prior.space == "world" and node.kind == prior.kind and (node.kind != "sprite" or node.sprite == prior.sprite):
            state = state_at(prior, previous.durationMs)
            # Pulse is an effect, not a cumulative scale transformation.
            if prior.pulse:
                state["scale"] /= 1 + .06 * math.sin(previous.durationMs / 1000 * math.tau)
            node = node.model_copy(update=state)
        elements.append(node)
    camera = scene.canvas.camera
    if scene.canvas.layout == "full" and previous.canvas.layout == "full" and (not camera or camera[0].timeMs != 0):
        from .canvas_models import CameraKeyframe
        camera = [CameraKeyframe(timeMs=0, **camera_at(previous.canvas, previous.durationMs)), *camera]
    return scene.model_copy(update={"canvas": scene.canvas.model_copy(update={"elements": elements, "camera": camera})})


def resolved_scenes(board):
    result = []
    for scene in board.scenes:
        result.append(continued_scene(scene, result[-1] if result else None))
    return result


def validate_canvas_layout(scene):
    errors = []
    full = scene.canvas.layout == "full"
    zoom = max([1., *[key.zoom for key in scene.canvas.camera]])
    if any(k.timeMs > scene.durationMs for k in scene.canvas.camera):
        errors.append("camera timing exceeds scene duration")
    if not full and len(wrapped(scene.headline, font(46), 592)) > 3:
        errors.append("canvas headline exceeds three lines")
    if not full and (len(scene.points) > 2 or any(len(wrapped(p, font(28), 580)) > 2 for p in scene.points)):
        errors.append("canvas supports two short footer points, each two lines")
    for element in scene.canvas.elements:
        if not full and (element.width > 640 or element.height > 600 or element.fontSize > 64 or len(element.text) > 60 or element.scale > 2
                         or any(k.scale is not None and k.scale > 2 for k in element.keyframes)):
            errors.append(f"canvas {element.id}: larger dimensions/fonts/scales require layout=full")
        connector = element.kind in ("line", "arrow", "path")
        if element.kind == "text" and not element.text.strip():
            errors.append(f"canvas {element.id}: text must be nonblank")
        if element.kind in ("icon", "line", "arrow", "sprite", "path") and element.text:
            errors.append(f"canvas {element.id}: use a separate text element for labels")
        if element.text:
            lines = wrapped(element.text, font(element.fontSize), element.width - 12)
            if len(lines) * element.fontSize * 1.35 > element.height - 8 or any(font(element.fontSize).getlength(c) > element.width - 12 for c in element.text):
                errors.append(f"canvas {element.id}: text overflows {element.width:g}x{element.height:g} box; need height >= {len(lines) * element.fontSize * 1.35 + 8:.0f}px; enlarge box or shorten text")
        if element.curve and element.curve.endMs > scene.durationMs:
            errors.append(f"canvas {element.id}: curve timing exceeds scene duration")
        if not full and element.curve and any(not (40 <= p.x <= 680 and 360 <= p.y <= 980) for p in element.curve.points):
            errors.append(f"canvas {element.id}: curve points must stay in framed safe area")
        if not connector:
            # Monotone easing keeps the entire interpolated box within endpoint bounds.
            timeline = states(element)
            rotated = any(s["rotation"] != 0 for _, s, _ in timeline)
            locations = [(s["x"], s["y"]) for _, s, _ in timeline]
            if element.curve:
                # A Bezier stays inside its control-point convex hull.
                locations = [(p.x, p.y) for p in element.curve.points]
            max_scale = max(s["scale"] for _, s, _ in timeline) * (1.06 if element.pulse else 1)
            raster_area = element.width*element.height*(max_scale*(zoom if element.space == "world" else 1))**2*(2 if rotated else 1)
            if raster_area > 8_000_000:
                errors.append(f"canvas {element.id}: rendered layer exceeds 8 megapixels; reduce size, scale or camera zoom")
            for x, y in locations:
                state = {"x": x, "y": y, "scale": max_scale}
                w, h = element.width * state["scale"], element.height * state["scale"]
                if rotated:
                    w = h = math.hypot(w, h)
                if not full and (state["x"] - w / 2 < 40 or state["x"] + w / 2 > 680 or state["y"] - h / 2 < 360 or state["y"] + h / 2 > 980):
                    errors.append(f"canvas {element.id}: animated bounds must stay inside x=40..680, y=360..980")
                    break
    if errors:
        raise ValueError("; ".join(errors))


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
    elif element.kind == "sprite":
        from .sprites import sprite_image
        glyph = sprite_image(element.sprite, element.color, element.fill).copy()
        if element.sprite in ("water", "ripple"):
            glyph = glyph.resize((w, h), Image.Resampling.LANCZOS)
        else:
            glyph.thumbnail((w, h), Image.Resampling.LANCZOS)
        image.alpha_composite(glyph, ((w-glyph.width)//2, (h-glyph.height)//2))
    if element.text:
        lines = wrapped(element.text, font(element.fontSize), element.width - 12)
        step = element.fontSize * 1.35
        for i, line in enumerate(lines):
            draw.text((w / 2, h / 2 + (i - (len(lines) - 1) / 2) * step), line,
                      font=font(element.fontSize), fill=element.color, anchor="mm")
    return image


@lru_cache(maxsize=8)
def canvas_background(color):
    y, x = np.mgrid[0:HEIGHT, 0:WIDTH]
    base = np.array(tuple(bytes.fromhex(color[1:])), dtype=float)
    light = np.exp(-((x-400)**2 / 180000 + (y-720)**2 / 220000))
    return Image.fromarray(np.uint8(np.clip(base + light[:, :, None]*[5, 12, 18], 0, 255))).convert("RGBA")


def canvas_frame(scene, t):
    canvas = scene.canvas
    image = canvas_background(canvas.background).copy()
    draw = ImageDraw.Draw(image)
    if canvas.layout == "framed":
        draw.text((64, 110), "VIDEO TRACE", font=font(23), fill="#64dfbf")
        for i, line in enumerate(wrapped(scene.headline, font(46), 592)):
            draw.text((64, 165 + i * 60), line, font=font(46), fill="#f4f8ff")
    camera = camera_at(canvas, t*1000)
    zoom = camera["zoom"]
    def screen(x, y):
        return (x-camera["x"])*zoom+WIDTH/2, (y-camera["y"])*zoom+HEIGHT/2
    elements = {e.id: e for e in canvas.elements}
    current = {e.id: state_at(e, t * 1000) for e in canvas.elements}
    for element in canvas.elements:
        state = current[element.id]
        if element.space == "world":
            state["x"], state["y"] = screen(state["x"], state["y"])
            state["scale"] *= zoom
    for element in sorted(canvas.elements, key=lambda e: (e.space == "screen", e.z)):
        state = current[element.id]
        opacity = state["opacity"]
        if element.kind == "path":
            curve = element.curve
            if t * 1000 < curve.startMs:
                continue
            p = min(1., (t*1000-curve.startMs) / min(600, curve.endMs-curve.startMs))
            layer = Image.new("RGBA", image.size)
            pen = ImageDraw.Draw(layer)
            project = screen if element.space == "world" else lambda x, y: (x, y)
            path = [project(*bezier(curve, v)) for v in np.linspace(0, p, 64)]
            rgb = tuple(bytes.fromhex(element.color[1:]))
            pen.line(path, fill=(*rgb, 90), width=3)
            progress = max(0, (t*1000-curve.startMs)/(curve.endMs-curve.startMs))
            for offset in (0, .33, .66) if curve.loop else (0,):
                q = (progress+offset) % 1 if curve.loop else min(1, progress)
                if q > p:
                    continue
                for trail in range(6, -1, -1):
                    if q-trail*.012 < 0:
                        continue
                    x, y = project(*bezier(curve, q-trail*.012))
                    r = 5 if trail == 0 else 3
                    pen.ellipse((x-r,y-r,x+r,y+r),fill=(*rgb,round(255*(1-trail/7))))
            layer.putalpha(layer.getchannel("A").point(lambda v: round(v*opacity)))
            image.alpha_composite(layer)
        elif element.kind in ("line", "arrow"):
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
            if state["rotation"]:
                layer = layer.rotate(-state["rotation"], resample=Image.Resampling.BICUBIC, expand=True)
            layer.putalpha(layer.getchannel("A").point(lambda v: round(v * opacity)))
            image.alpha_composite(layer, (round(state["x"] - layer.width / 2), round(state["y"] - layer.height / 2)))
    draw = ImageDraw.Draw(image)
    y = 1030
    for point in scene.points if canvas.layout == "framed" else []:
        for line in wrapped(point, font(28), 580):
            draw.text((64, y), line, font=font(28), fill="#9aacc5")
            y += 37
        y += 10
    return np.asarray(image.convert("RGB"))
