"""Local renderer demonstration; synthetic storyboard, no model/API calls."""
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from creator.models import Storyboard
from creator.renderer import render, frame, validate_layout
from PIL import Image


def demo_board():
    examples = [
        ("从一个原始视频开始", "pipeline", [("video", "原始视频"), ("file", "上传解析")], "slide", "先理解内容，再规划表达"),
        ("同时理解声音与文字", "split", [("video", "原始视频"), ("audio", "语音转写"), ("text", "画面文字")], "draw", "两类信息保留来源时间戳"),
        ("让 Agent 汇集内容证据", "merge", [("audio", "语音转写"), ("text", "画面文字"), ("agent", "创作 Agent")], "pulse", "根据需求检索与核实内容"),
        ("将重点组织为分镜", "split", [("agent", "创作 Agent"), ("cards", "开场分镜"), ("cards", "核心要点"), ("cards", "结尾总结")], "reveal", "内容与节奏先交给用户确认"),
        ("确认后生成短视频", "pipeline", [("cards", "确认分镜"), ("server", "本地渲染"), ("phone", "短视频成片")], "draw", "文字、图形、动画与音乐组合"),
    ]
    scenes = []
    for i, (headline, layout, items, motion, point) in enumerate(examples):
        scenes.append({"sceneId": f"demo_{i}", "template": "flow", "durationMs": 6000,
                       "headline": headline, "points": [point], "animation": "fade", "sourceRefs": ["e_0_10000"],
                       "visual": {"layout": layout, "theme": "dark", "motion": motion,
                                  "items": [{"icon": icon, "label": label} for icon, label in items]}})
    return Storyboard.model_validate({"revision": 1, "title": "视频如何变成短视频", "targetAudience": "项目体验者",
                                     "musicId": "light_01", "scenes": scenes})


if __name__ == "__main__":
    output = ROOT / "creator/data/diagram-demo"
    output.mkdir(parents=True, exist_ok=True)
    board = demo_board()
    validate_layout(board)
    (output / "storyboard.json").write_text(board.model_dump_json(indent=2), encoding="utf-8")
    sheet = Image.new("RGB", (360 * len(board.scenes), 640))
    for i, scene in enumerate(board.scenes):
        still = Image.fromarray(frame(scene, 3))
        still.save(output / f"scene-{i + 1}.png")
        sheet.paste(still.resize((360, 640)), (360 * i, 0))
    sheet.save(output / "contact-sheet.png")
    print(json.dumps(render(board, output / "render")), flush=True)
