"""Opt-in real model smoke test with synthetic public demo evidence, not real video parsing."""
import argparse
from contextlib import closing
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
from creator.models import Operation, Storyboard
from creator.renderer import frame, render
from creator.settings import Settings
from creator.store import Store


class DemoEvidence:
    """Fixture describes the public project demo; never reads uploaded videos."""
    def evidence(self, operation, action, args):
        return [{"evidenceId": "e_0_30000", "startMs": 0, "endMs": 30000,
            "snippet": "原始视频上传后，语音转写和画面文字形成内容证据，Agent 根据需求检索并组织分镜，经用户确认后本地渲染为短视频。",
            "transcript": "这是公开视频处理流程的合成演示文本，不是真实上传视频的转写。先解析声音与画面文字，再检索证据、组织分镜，用户确认后输出文字和二维图解动画。", "ocrTexts": []}]


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--render", action="store_true", help="Render the accepted model storyboard locally")
    parser.add_argument("--duration", type=int, choices=[15, 30, 45, 60], default=30)
    parser.add_argument("--style", choices=["canvas", "motion"], default="canvas")
    args = parser.parse_args()
    load_dotenv(ROOT / ".env")
    output = ROOT / "creator/data/canvas-model-smoke" / uuid.uuid4().hex[:8]
    output.mkdir(parents=True)
    instruction = ("直接创作30秒解释视频，面向普通观众。分3幕，每幕10秒，讲原视频解析成语音/文字证据，Agent组织分镜，确认后输出手机短视频。"
        "每幕必须用canvas自由组合图形和独立文字，不使用visual固定布局。每幕最多8元素，标签用独立text。"
        "至少一幕有arrow连接节点，至少一个元素用2个关键帧展示移动和淡入。文字字号24，文字框宽至少180高60；"
        "节点范围保守放x=150..570,y=450..850，宽高不要超过180；不要让文字挡住图形或箭头。")
    instruction = instruction.replace("30秒", f"{args.duration}秒").replace("每幕10秒", f"每幕{args.duration / 3:g}秒")
    if args.style == "motion":
        instruction = (f"直接创作{args.duration}秒科技发布会与动态文字风短视频。使用5幕，时长总和严格为{args.duration * 1000}ms，每幕至少2000ms。"
            "解释原视频解析为语音/文字证据，Agent检索并组织分镜，用户确认后渲染成手机短视频。"
            "每幕必须使用motion，不使用visual/canvas。交替launch发布会拼版和kinetic大字动态排版，accent统一violet。"
            "大标题words每幕2到3个短词，每词优先2到4汉字，形成强视觉主次；每幕headline一句简短承接文案，points用1到3条短卡片文案。"
            "请把15秒内容讲成从问题到解决的连续叙事，每幕引出下一幕：长视频→提取证据→Agent组织→人工确认→短视频成片。"
            "不要加空泛宣传语、虚构效率或性能数字。转场使用push/wipe/fade搭配，保持节奏清楚。")
    operation = Operation.model_validate({"taskId": "canvas-demo", "operationId": "op-" + output.name, "attempt": 1,
        "ownerId": 1, "mediaId": 1, "sourceVersion": "demo", "type": "CREATE",
        "parameters": {"requirement": instruction, "durationSeconds": args.duration}})
    store = Store(output / "ops.sqlite")
    started = time.monotonic()
    with closing(sqlite3.connect(output / "checkpoint.sqlite", check_same_thread=False)) as connection:
        board = CreatorAgent(Settings.from_env(), store, DemoEvidence(), SqliteSaver(connection)).run(operation)
    board = Storyboard.model_validate(board)
    if not all(getattr(scene, args.style) for scene in board.scenes):
        raise ValueError(f"model did not produce {args.style} scenes")
    (output / "storyboard.json").write_text(board.model_dump_json(indent=2), encoding="utf-8")
    with store.connect() as connection:
        traces = connection.execute("SELECT name,result FROM tool_traces ORDER BY id").fetchall()
    report = {"scope": "Real configured model + fixture evidence + local renderer; not real-video end-to-end",
        "model": Settings.from_env().llm_model, "generationSeconds": round(time.monotonic() - started, 2),
        "scenes": len(board.scenes), "toolCalls": [row["name"] for row in traces if row["name"] != "model"],
        "repairErrors": [json.loads(row["result"])["error"] for row in traces if "error" in json.loads(row["result"])]}
    sheet = Image.new("RGB", (360 * len(board.scenes), 640))
    for i, scene in enumerate(board.scenes):
        sheet.paste(Image.fromarray(frame(scene, min(1.6, scene.durationMs / 1000 * .6))).resize((360, 640)), (360 * i, 0))
    sheet.save(output / "contact-sheet.png")
    (output / "result.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    if args.render:
        report["render"] = render(board, output / "render")
    (output / "result.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False), flush=True)
