"""Fixed, measured templates. Run in a child process; model output is data only."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
from functools import lru_cache

from PIL import Image, ImageDraw, ImageFont

from .models import Storyboard

WIDTH, HEIGHT, FPS = 720, 1280, 24
TEMPLATE_VERSION = "cards-v1"


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
    for scene in board.scenes:
        scene_layout(scene)


def frame(scene, t: float, layout=None):
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
    import imageio_ffmpeg
    from moviepy import VideoClip
    validate_layout(board)
    directory.mkdir(parents=True, exist_ok=True)
    cache = directory.parent.parent / "scene-cache"
    cache.mkdir(parents=True, exist_ok=True)
    duration = sum(s.durationMs for s in board.scenes)
    output = directory / "output.mp4"
    hits = 0
    paths = []
    font_digest = sha256(Path(font_path()))
    for scene in board.scenes:
        visual = scene.model_dump(exclude={"sceneId", "sourceRefs"})
        key = hashlib.sha256(json.dumps([visual, TEMPLATE_VERSION, font_digest, WIDTH, HEIGHT, FPS], sort_keys=True).encode()).hexdigest()
        path = cache / f"{key}.mp4"
        if path.is_file():
            try:
                probe(path, scene.durationMs)
                hits += 1
            except Exception:
                path.unlink()
        if not path.is_file():
            temporary = cache / f"{key}.tmp.mp4"
            layout = scene_layout(scene)
            clip = VideoClip(lambda t, s=scene, layout=layout: frame(s, t, layout), duration=scene.durationMs / 1000)
            try:
                clip.write_videofile(str(temporary), fps=FPS, codec="libx264", audio=False,
                                     preset="ultrafast", threads=2, logger=None, ffmpeg_params=["-pix_fmt", "yuv420p"])
            finally:
                clip.close()
            probe(temporary, scene.durationMs)
            temporary.replace(path)
        paths.append(path.resolve())
    concat = directory / "concat.txt"
    # Cache names are hashes; all paths originate inside this task's private cache.
    concat.write_text("\n".join("file '" + str(p).replace("\\", "/").replace("'", "'\\''") + "'" for p in paths), encoding="utf-8")
    temporary = directory / "output.tmp.mp4"
    command = [imageio_ffmpeg.get_ffmpeg_exe(), "-y", "-f", "concat", "-safe", "0", "-i", str(concat)]
    if board.musicId == "light_01":
        command += ["-stream_loop", "-1", "-i", str(Path(__file__).parent / "assets" / "light_01.wav"),
                    "-map", "0:v:0", "-map", "1:a:0", "-c:a", "aac", "-b:a", "96k", "-af", "volume=0.18"]
    command += ["-c:v", "copy", "-t", str(duration / 1000), "-movflags", "+faststart", str(temporary)]
    result = subprocess.run(command, capture_output=True, timeout=120, check=False)
    if result.returncode:
        raise RuntimeError("FFmpeg assembly failed: " + result.stderr.decode(errors="replace")[-1000:])
    metadata = probe(temporary, duration)
    temporary.replace(output)
    metadata.update({"path": str(output.resolve()), "revision": board.revision, "cacheHits": hits, "sceneCount": len(paths)})
    (directory / "metadata.json").write_text(json.dumps(metadata), encoding="utf-8")
    return metadata


if __name__ == "__main__":
    job = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
    print(json.dumps(render(Storyboard.model_validate(job["storyboard"]), Path(job["directory"]))))
