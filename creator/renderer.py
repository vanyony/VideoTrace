"""Fixed, measured templates. Run in a child process; model output is data only."""
import hashlib
import json
import os
from pathlib import Path
import sys
from functools import lru_cache

from PIL import Image, ImageDraw, ImageFont

from .models import Storyboard

WIDTH, HEIGHT, FPS = 720, 1280, 24
TEMPLATE_VERSION = "remotion-v1"


def font_path() -> str:
    configured = os.getenv("CREATOR_FONT_PATH")
    candidates = [configured] if configured else [
        "C:/Windows/Fonts/msyh.ttc", "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
        "/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc",
    ]
    for candidate in candidates:
        if candidate and Path(candidate).is_file():
            return candidate
    raise ValueError("Configure CREATOR_FONT_PATH to an installed Chinese font")


@lru_cache(maxsize=8)
def font(size):
    return ImageFont.truetype(font_path(), size)


def wrapped(text: str, font, max_width: int) -> list[str]:
    lines, line = [], ""
    for char in text:
        if char == "\n":
            lines.append(line)
            line = ""
        elif font.getlength(line + char) > max_width:
            lines.append(line)
            line = char
        else:
            line += char
    if line:
        lines.append(line)
    return lines


def scene_layout(scene):
    if scene.motion:
        from .motion import validate_motion_layout
        validate_motion_layout(scene)
        return None
    if scene.canvas:
        from .canvas import validate_canvas_layout
        validate_canvas_layout(scene)
        return None
    if scene.visual:
        from .visuals import validate_visual_layout
        validate_visual_layout(scene)
        return None
    heading_font = font(48)
    body_font = font(34)
    heading = wrapped(scene.headline, heading_font, 584)
    points = [wrapped(point, body_font, 528) for point in scene.points]
    if len(heading) > 3 or any(len(lines) > 3 for lines in points):
        raise ValueError(f"scene {scene.sceneId}: text exceeds template line limits")
    y = 230 + len(heading) * 68 + 54
    blocks = []
    for lines in points:
        blocks.append((y, lines))
        y += len(lines) * 50 + 44
    if y > 1130:
        raise ValueError(f"scene {scene.sceneId}: text exceeds visible card area; shorten points")
    return heading_font, body_font, heading, blocks


def validate_layout(board: Storyboard):
    from .canvas import resolved_scenes
    errors = []
    for scene in resolved_scenes(board):
        try:
            scene_layout(scene)
        except ValueError as error:
            errors.append(f"scene {scene.sceneId}: {error}")
    if errors:
        raise ValueError("\n".join(errors))


def frame(scene, t: float, layout=None):
    if scene.motion:
        from .motion import motion_frame
        return motion_frame(scene, t)
    if scene.canvas:
        from .canvas import canvas_frame
        return canvas_frame(scene, t)
    if scene.visual:
        from .visuals import diagram_frame
        return diagram_frame(scene, t)
    import numpy as np
    heading_font, body_font, heading, blocks = layout or scene_layout(scene)
    image = Image.new("RGB", (WIDTH, HEIGHT), "#0b1428")
    draw = ImageDraw.Draw(image)
    draw.rounded_rectangle((32, 100, 688, 1180), radius=28, fill="#172641")
    draw.rounded_rectangle((64, 156, 128, 166), radius=4, fill="#64dfbf")
    badge = {"title": "视频重点", "points": "核心观点", "flow": "理解路径"}[scene.template]
    draw.text((64, 182), badge, font=font(23), fill="#8ba5c7")
    for i, line in enumerate(heading):
        draw.text((64, 240 + i * 68), line, font=heading_font, fill="#f4f8ff")
    for i, (y, lines) in enumerate(blocks):
        if scene.animation == "reveal" and t < 0.5 + i * 0.75:
            continue
        active = scene.animation == "highlight" and min(int(t / max(scene.durationMs / 1000 / max(len(blocks), 1), .1)), len(blocks) - 1) == i
        color = "#64dfbf" if active or scene.template != "flow" else "#7184a1"
        draw.ellipse((64, y + 13, 78, y + 27), fill=color)
        for j, line in enumerate(lines):
            draw.text((96, y + j * 50), line, font=body_font, fill="#e8effa" if active or scene.template != "flow" else "#9aacc5")
        if scene.template == "flow" and i < len(blocks) - 1:
            draw.line((71, y + 38, 71, blocks[i + 1][0] + 5), fill="#425774", width=3)
    if scene.animation == "fade" and t < .6:
        image = Image.blend(Image.new("RGB", image.size, "#0b1428"), image, max(0, t / .6))
    return np.asarray(image)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def probe(path: Path, expected_duration_ms: int) -> dict:
    from moviepy import VideoFileClip
    with VideoFileClip(str(path), audio=False) as clip:
        if clip.size != [WIDTH, HEIGHT] or abs(clip.fps - FPS) > .1 or abs(clip.duration * 1000 - expected_duration_ms) > 150:
            raise ValueError("encoded output metadata does not match approved storyboard")
        clip.get_frame(min(.5, clip.duration / 2))
    return {"sizeBytes": path.stat().st_size, "durationMs": expected_duration_ms, "sha256": sha256(path)}


def render(board: Storyboard, directory: Path) -> dict:
    from .remotion_renderer import render as render_remotion
    return render_remotion(board, directory)


if __name__ == "__main__":
    job = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
    print(json.dumps(render(Storyboard.model_validate(job["storyboard"]), Path(job["directory"]))))
