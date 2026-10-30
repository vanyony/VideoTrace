"""Render a synthetic, freely composed 30-second canvas demo without external APIs."""
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from PIL import Image
from creator.models import Storyboard
from creator.renderer import render, validate_layout
from creator.remotion_renderer import preview_frames
from creator.preview_reviewer import sample_times


def node(id, kind, x, y, width=120, height=100, **kwargs):
    return {"id": id, "kind": kind, "x": x, "y": y, "width": width, "height": height, **kwargs}


def label(id, text, x, y, width=210):
    return node(id, "text", x, y, width, 52, text=text, fontSize=26, color="#e8effa")


def arrow(id, a, b, delay=0):
    return {"id": id, "kind": "arrow", "fromId": a, "toId": b, "z": 0,
            "keyframes": [{"timeMs": 0, "opacity": 0}, {"timeMs": delay + 800, "opacity": 1}]}


def appear(delay=0, x=None, y=None):
    return [{"timeMs": 0, "opacity": 0, "scale": .7},
            {"timeMs": delay + 700, "opacity": 1, "scale": 1, "easing": "easeOut"}]


def demo_board():
    groups = [
        ("从一段视频，开始一次创作", "先理解内容，再设计表达", [
            node("screen", "rectangle", 360, 600, 350, 240, fill="#172641", keyframes=appear()),
            node("play", "icon", 360, 580, 130, 130, icon="video", z=2, keyframes=appear(300)),
            label("input", "原始视频", 360, 680),
            node("tag", "rectangle", 360, 850, 220, 64, text="上传 · 解析", fontSize=26, keyframes=appear(700)),
        ]),
        ("声音和画面，走向两条路径", "两类内容保留原视频时间戳", [
            node("video", "icon", 360, 450, 110, 110, icon="video"), label("vl", "原始视频", 360, 535),
            node("audio", "circle", 185, 730, 150, 150, keyframes=appear(500)),
            node("ai", "icon", 185, 730, 90, 90, icon="audio", z=2, keyframes=appear(700)),
            node("text", "rectangle", 535, 730, 150, 150, keyframes=appear(800)),
            node("ti", "icon", 535, 730, 90, 90, icon="text", z=2, keyframes=appear(1000)),
            label("al", "语音转写", 185, 850), label("tl", "画面文字", 535, 850),
            arrow("a1", "video", "audio", 700), arrow("a2", "video", "text", 1000),
        ]),
        ("不同证据，汇集到 Agent", "按创作需求检索与核对", [
            node("audio", "icon", 160, 460, 100, 100, icon="audio"), label("al", "语音证据", 160, 545),
            node("text", "icon", 560, 460, 100, 100, icon="text"), label("tl", "文字证据", 560, 545),
            node("core", "circle", 360, 770, 220, 180, keyframes=[{"timeMs": 0, "scale": .7}, {"timeMs": 1800, "scale": 1}, {"timeMs": 2700, "scale": 1.1}, {"timeMs": 3600, "scale": 1}]),
            node("agent", "icon", 360, 750, 110, 110, icon="agent", z=2), label("agentlabel", "创作 Agent", 360, 895),
            arrow("a1", "audio", "core", 800), arrow("a2", "text", "core", 1200),
            node("packet", "circle", 195, 520, 16, 16, fill="#64dfbf", z=3,
                 keyframes=[{"timeMs": 0, "opacity": 0}, {"timeMs": 1300, "opacity": 1}, {"timeMs": 2600, "x": 330, "y": 705}, {"timeMs": 2900, "opacity": 0}]),
        ]),
        ("把内容组织成可编辑分镜", "先确认结构，再生成成片", [
            node("agent", "icon", 360, 450, 120, 120, icon="agent"),
            node("card1", "rectangle", 165, 720, 170, 170, text="开场", fontSize=30, keyframes=appear(300)),
            node("card2", "rectangle", 360, 720, 170, 170, text="重点", fontSize=30, keyframes=appear(800)),
            node("card3", "rectangle", 555, 720, 170, 170, text="总结", fontSize=30, keyframes=appear(1300)),
            arrow("a1", "agent", "card1", 300), arrow("a2", "agent", "card2", 800), arrow("a3", "agent", "card3", 1300),
            label("approval", "人工确认 · 可局部修改", 360, 900, 430),
        ]),
        ("将分镜，变成一支短视频", "文字、图形、动画与音乐组合", [
            node("card", "rectangle", 180, 560, 180, 130, text="已确认分镜", fontSize=24),
            node("phone", "rectangle", 520, 680, 200, 350, fill="#172641",
                 keyframes=[{"timeMs": 0, "x": 560, "opacity": 0}, {"timeMs": 1800, "x": 520, "opacity": 1, "easing": "easeOut"}]),
            node("screen", "rectangle", 520, 660, 160, 230, color="#425774", fill="#0b1428", z=2, keyframes=appear(700)),
            node("play", "icon", 520, 650, 100, 100, icon="video", z=3, keyframes=appear(1200)),
            label("result", "短视频成片", 520, 910), arrow("produce", "card", "phone", 1000),
            node("status", "rectangle", 180, 810, 200, 64, text="本地渲染", fontSize=25, keyframes=appear(500)),
        ]),
    ]
    return Storyboard.model_validate({"revision": 1, "title": "视频如何变成短视频：自由画布", "targetAudience": "项目体验者", "musicId": "light_01",
        "scenes": [{"sceneId": f"canvas_{i}", "template": "flow", "durationMs": 6000,
                    "headline": headline, "points": [point], "animation": "fade", "sourceRefs": ["e_0_10000"],
                    "canvas": {"elements": elements}} for i, (headline, point, elements) in enumerate(groups)]})


if __name__ == "__main__":
    output = ROOT / "creator/data/canvas-demo"
    output.mkdir(parents=True, exist_ok=True)
    board = demo_board(); validate_layout(board)
    (output / "storyboard.json").write_text(board.model_dump_json(indent=2), encoding="utf-8")
    sheet = Image.new("RGB", (360 * len(board.scenes), 640))
    snapshots=preview_frames(board,board.scenes,output/'frames',180)
    for i, scene in enumerate(board.scenes):
        still = Image.open(snapshots[scene.sceneId,sample_times(scene)[1]])
        still.save(output / f"scene-{i+1}.png")
        sheet.paste(still.resize((360, 640)), (360 * i, 0))
        still.close()
    sheet.save(output / "contact-sheet.png")
    print(json.dumps(render(board, output / "render")), flush=True)
