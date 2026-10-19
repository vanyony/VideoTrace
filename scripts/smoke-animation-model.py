"""Real model/tool loop on labelled water-cycle fixture evidence; not uploaded-video E2E."""
from contextlib import closing
import argparse
from dataclasses import replace
import json
from pathlib import Path
import sqlite3
import sys
import time
import uuid

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from dotenv import load_dotenv
from langgraph.checkpoint.sqlite import SqliteSaver
from PIL import Image
from creator.agent import CreatorAgent
from creator.canvas import resolved_scenes
from creator.models import Operation, Storyboard
from creator.renderer import frame, render
from creator.settings import Settings
from creator.store import Store


class WaterCycleEvidence:
    """Synthetic educational transcript, not real ASR or a live Java retrieval test."""
    def evidence(self, operation, action, args):
        clips = [
            (0, 10000, "太阳提供能量，地表的水蒸发成为水蒸气，进入空气。"),
            (10000, 20000, "空气中的水蒸气冷却凝结为小水滴，形成云。"),
            (20000, 30000, "云中的水滴长大后形成降水。水回到地表，汇入河流和海洋，继续参与水循环。"),
        ]
        return [{"evidenceId": f"e_{a}_{b}", "startMs": a, "endMs": b,
                 "snippet": text, "transcript": text, "ocrTexts": []} for a, b, text in clips
                if action != "context" or args.get("evidenceId") == f"e_{a}_{b}"]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--full", action="store_true", help="Exercise full-screen composition and camera motion")
    args = parser.parse_args()
    load_dotenv(ROOT / '.env')
    output = ROOT / 'creator/data/animation-model-smoke' / uuid.uuid4().hex[:8]
    output.mkdir(parents=True)
    instruction = (
        "直接制作15秒二维科普动画，面向普通观众，解释水循环。3幕，每幕5000ms。"
        "先检索证据，按蒸发→凝结成云→降水回流讲解，必须使用canvas组件动画而非motion/visual或文字卡片。"
        "每幕最多10个元素，用sun/earth/cloud/droplet组件与路径光点表达，文字只保留简短标题和最多一句要点。"
        "至少有一个sprite沿curve运动、一个kind=path的循环流动和一个旋转或pulse效果；"
        "后两幕continuePrevious=true，以相同id保留地球、太阳等主体，保持位置连贯；新物体可以出现。"
        "蒸发是水变成水蒸气，凝结后才是云滴，降水是水滴向下运动；避免把上升的大水滴当作水蒸气，蒸发宜用光点路径示意。"
        "可以用水滴沿曲线降落。路径与所有控制点须留足节点半尺寸边距。"
        "地球可放(360,800)尺寸220x220，太阳可放(150,470)尺寸100x100，云可放(480,490)尺寸180x130；"
        "这些仅是可用构图参考，具体画面、路径、时序由你设计。color/fill注意色差，水滴用青色，云用浅蓝色。"
        "不要增加证据没有提到的事实和数字。"
    )
    if args.full:
        instruction = (
            "制作15秒萌系二维科普动画《一滴水的旅程》，3幕各5000ms，按蒸发→成云→降雨回到水面。先检索证据。"
            "全部使用canvas.layout=full，不使用motion/visual。使用浅米白背景、蓝绿色水面、暖黄色太阳、浅色云和蓝色水滴。"
            "不要地球图标，不要流程图或卡片；让水面、云、雨滴分别成为大主体，尝试近景与远景变化。"
            "第一幕水面放画面下方，太阳在左边，水蒸气用上升光点路径；第二幕云形成；第三幕水滴沿curve下降、水面涟漪展开。"
            "sun/earth/cloud/droplet等常规sprite实际绘图在正方形盒子内，water/ripple支持横向拉伸。"
            "每幕6到10元素，所有文字用独立text，大标题2到4字、fontSize80到110、文字盒高度至少字号*1.35+8；不需要画headline/points。"
            "至少一幕camera用两个关键帧实现缓慢平移或推近，zoom建议1到1.15；注意镜头也移动文字，提前预留屏幕位置，关键文字别放边缘。"
            "full允许物体从屏幕外入场，12关键帧足够先入场再动作再离开。后两幕continuePrevious=true，沿用物体ID保持状态，若需要恢复位置请明确0ms关键帧。"
            "可用camera0ms重置构图，避免继承镜头让新标题出画。选一个主体动作，不要所有元素一起乱动。"
            "至少一个path光点流动、一颗sprite水滴沿curve降落、一处ripple用scale和opacity展开。"
            "严格基于证据，无虚构数据；蒸发是气态水，不要把上升的液态水滴当作水蒸气。"
            "所有标题/字幕必须space=screen：大标题建议(360,160)宽620高180，正文说明(360,1160)宽620高100，不要混在主体中间。"
            "water建议宽1100高500、中心(360,1150)，让水面铺到画面底部，不要画成悬空矩形条。太阳和云避开顶部350像素标题区。"
        )
    operation = Operation.model_validate({"taskId": "water-cycle-demo", "operationId": 'op-' + output.name,
        "attempt": 1, "ownerId": 1, "mediaId": 1, "sourceVersion": "fixture", "type": "CREATE",
        "parameters": {"requirement": instruction, "durationSeconds": 15}})
    settings = Settings.from_env()
    settings = replace(settings, llm_options={**settings.llm_options, "max_tokens": 9000})
    store = Store(output / 'ops.sqlite')
    started = time.monotonic()
    print(json.dumps({"output": str(output), "model": settings.llm_model}), flush=True)
    with closing(sqlite3.connect(output / 'checkpoint.sqlite', check_same_thread=False)) as connection:
        result = CreatorAgent(settings, store, WaterCycleEvidence(), SqliteSaver(connection)).run(operation)
    board = Storyboard.model_validate(result)
    (output / 'storyboard.json').write_text(board.model_dump_json(indent=2), encoding='utf-8')
    with store.connect() as connection:
        traces = connection.execute('SELECT name,result FROM tool_traces ORDER BY id').fetchall()
    nodes = [e for s in board.scenes if s.canvas for e in s.canvas.elements]
    assert all(s.canvas for s in board.scenes), 'model must use canvas'
    assert any(e.kind == 'path' for e in nodes), 'model must exercise path rendering'
    assert any(e.kind == 'sprite' and e.curve for e in nodes), 'model must exercise object path movement'
    report = {"scope": "Real configured model + LangGraph tools + synthetic evidence fixture + local render; not real-video/Java/MinIO E2E",
        "model": settings.llm_model, "generationSeconds": round(time.monotonic()-started,2),
        "toolCalls": [r['name'] for r in traces if r['name'] != 'model'],
        "repairErrors": [json.loads(r['result'])['error'] for r in traces if 'error' in json.loads(r['result'])],
        "sprites": sorted({e.sprite for e in nodes if e.kind == 'sprite'}),
        "curveCount": sum(e.curve is not None for e in nodes)}
    report["layouts"] = [s.canvas.layout for s in board.scenes]
    report["cameraKeyframes"] = sum(len(s.canvas.camera) for s in board.scenes)
    if args.full:
        assert all(s.canvas.layout == "full" for s in board.scenes)
        assert report["cameraKeyframes"] >= 2
    sheet = Image.new('RGB',(360*len(board.scenes),640))
    for i,s in enumerate(resolved_scenes(board)):
        sheet.paste(Image.fromarray(frame(s,2.5)).resize((360,640)),(360*i,0))
    sheet.save(output / 'preview.png')
    (output / 'result.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(report,ensure_ascii=False),flush=True)
    report['render'] = render(board, output / 'render')
    (output / 'result.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(report,ensure_ascii=False),flush=True)


if __name__ == '__main__':
    main()
