"""Real model/tool loop on labelled educational fixture evidence; not uploaded-video E2E."""
from contextlib import closing
import argparse
from hashlib import sha256
from dataclasses import replace
import json
import re
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
from creator.renderer import render
from creator.remotion_renderer import preview_frames
from creator.preview_reviewer import sample_times
from creator.settings import Settings
from creator.store import Store


class DemoEvidence:
    """Synthetic educational transcript, not real ASR or a live Java retrieval test."""
    def __init__(self, topic):
        self.topic = topic

    def evidence(self, operation, action, args):
        clips = [
            (0, 10000, "太阳提供能量，地表的水蒸发成为水蒸气，进入空气。"),
            (10000, 20000, "空气中的水蒸气冷却凝结为小水滴，形成云。"),
            (20000, 30000, "云中的水滴长大后形成降水。水回到地表，汇入河流和海洋，继续参与水循环。"),
        ]
        if self.topic == "parcel":
            clips = [
                (0, 10000, "包裹进入分拣中心，工作人员核对目的地，将包裹分到对应的运输线路并装车。"),
                (10000, 20000, "运输车辆装载分拣后的包裹，从分拣中心出发，将包裹运往目的地。"),
                (20000, 30000, "包裹到达目的地后被交付给收件人。这是从分拣、运输到送达的过程。"),
            ]
        return [{"evidenceId": f"e_{a}_{b}", "startMs": a, "endMs": b,
                 "snippet": text, "transcript": text, "ocrTexts": []} for a, b, text in clips
                if action != "context" or args.get("evidenceId") == f"e_{a}_{b}"]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--full", action="store_true", help="Exercise full-screen composition and camera motion")
    parser.add_argument("--review", action="store_true", help="Exercise real multimodal preview feedback before saving")
    parser.add_argument("--topic", choices=["watercycle", "parcel"], default="watercycle")
    parser.add_argument("--resume", help="Resume an existing eight-character run ID without regenerating a completed draft")
    args = parser.parse_args()
    load_dotenv(ROOT / '.env')
    if args.resume and not re.fullmatch(r"[0-9a-f]{8}", args.resume):
        parser.error("resume must be an eight-character lowercase hexadecimal run ID")
    output = ROOT / 'creator/data/animation-model-smoke' / (args.resume or uuid.uuid4().hex[:8])
    if args.resume and not output.is_dir():
        parser.error("resume directory does not exist")
    output.mkdir(parents=True, exist_ok=bool(args.resume))
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
    if args.topic == "parcel":
        if not args.full:
            parser.error("parcel experiment requires --full")
        instruction = (
            "制作15秒萌系二维动画《快递包裹的一天》，3幕各5000ms，讲分拣→运输→送达。先检索并按给定证据创作，不编造速度、距离或效率数据。"
            "全部用canvas.layout=full，不使用motion/visual，不做流程图或卡片列表。浅米白背景、暖橙色包裹、深蓝或青绿色车辆，文字深色高对比。"
            "第一幕用factory组件示意分拣中心，包裹可用有文字的rectangle或file图标，展示包裹进入并装车；第二幕truck作为大主体运输，路面与背景移动产生行驶感；第三幕包裹移动到收件人(person图标)处。"
            "常规sprite绘图位于正方形盒子内，宽扁盒可能留白，按实际可见主体调整；包裹、车辆和收件人至少要看得清楚。"
            "每幕6到10元素，至少一处curve驱动物体移动、一处path光点流动和两个camera关键帧。镜头只做有意义的缓慢变化，避免所有东西同时移动。"
            "所有标题和字幕用space=screen：标题2到4字，fontSize80到110，建议中心(360,160)宽620高180；正文在(360,1160)宽620高100。"
            "主体动作尽量留在屏幕y=380..1020，考虑camera变换后也不要经过标题和字幕区。"
            "后两幕continuePrevious=true，复用包裹/车辆ID可承接状态，但需要新位置时用0ms关键帧显式设置；新标题不继承world坐标。"
            "先让包裹入场，再装车或送达，最后停留一小段；运输车辆不要过早完全离开屏幕。"
        )
    if args.review:
        instruction += "保存前必须调用review_storyboard_preview查看实际渲染画面，按具体问题决定是否修改；最多检查两次。修改必须重新检查。最后优先用save_storyboard({reviewedPreviewId:最后一次成功预览ID})原样保存，不再改动；不可把检查后的新改动直接保存。"
    operation = Operation.model_validate({"taskId": args.topic+"-demo", "operationId": 'op-' + output.name,
        "attempt": 1, "ownerId": 1, "mediaId": 1, "sourceVersion": "fixture", "type": "CREATE",
        "parameters": {"requirement": instruction, "durationSeconds": 15}})
    settings = Settings.from_env()
    settings = replace(settings, llm_options={**settings.llm_options, "max_tokens": 9000})
    store = Store(output / 'ops.sqlite')
    started = time.monotonic()
    print(json.dumps({"output": str(output), "model": settings.llm_model}), flush=True)
    with closing(sqlite3.connect(output / 'checkpoint.sqlite', check_same_thread=False)) as connection:
        agent = CreatorAgent(settings, store, DemoEvidence(args.topic), SqliteSaver(connection))
        if args.resume:
            state = agent.graph.get_state({"configurable": {"thread_id": f"{operation.operationId}:{operation.attempt}"}})
            if state.values.get("operation") != operation.model_dump():
                parser.error("resume parameters differ from the original operation")
        result = agent.run(operation)
    board = Storyboard.model_validate(result)
    (output / 'storyboard.json').write_text(board.model_dump_json(indent=2), encoding='utf-8')
    with store.connect() as connection:
        traces = connection.execute('SELECT name,result FROM tool_traces ORDER BY id').fetchall()
    nodes = [e for s in board.scenes if s.canvas for e in s.canvas.elements]
    assert all(s.canvas for s in board.scenes), 'model must use canvas'
    assert any(e.kind == 'path' for e in nodes), 'model must exercise path rendering'
    assert any(e.kind not in ('path', 'line', 'arrow') and e.curve for e in nodes), 'model must exercise object path movement'
    report = {"scope": "Real configured model + LangGraph tools + synthetic evidence fixture + local render; not real-video/Java/MinIO E2E",
        "topic": args.topic,
        "model": settings.llm_model, "generationSeconds": None if args.resume else round(time.monotonic()-started,2),
        "resumedFromCheckpoint": bool(args.resume),
        "toolCalls": [r['name'] for r in traces if r['name'] != 'model'],
        "repairErrors": [json.loads(r['result'])['error'] for r in traces if 'error' in json.loads(r['result'])],
        "sprites": sorted({e.sprite for e in nodes if e.kind == 'sprite'}),
        "curveCount": sum(e.curve is not None for e in nodes)}
    report["layouts"] = [s.canvas.layout for s in board.scenes]
    report["cameraKeyframes"] = sum(len(s.canvas.camera) for s in board.scenes)
    report["previewReviews"] = [json.loads(r['result']) for r in traces if r['name']=='review_storyboard_preview']
    successful_reviews = [r for r in report["previewReviews"] if 'previewId' in r]
    report["finalDraftMatchesLastReview"] = bool(successful_reviews and successful_reviews[-1]['draftSha256'] == sha256(board.model_dump_json().encode()).hexdigest())
    if args.review:
        assert any('previewId' in r for r in report["previewReviews"]), 'no successful multimodal preview review'
        assert report["finalDraftMatchesLastReview"], 'saved draft differs from last successful preview'
    if args.full:
        assert all(s.canvas.layout == "full" for s in board.scenes)
        assert report["cameraKeyframes"] >= 2
    sheet = Image.new('RGB',(360*len(board.scenes),640))
    snapshots=preview_frames(board,resolved_scenes(board),output/'frames',180)
    for i,s in enumerate(resolved_scenes(board)):
        with Image.open(snapshots[s.sceneId,sample_times(s)[1]]) as still:
            sheet.paste(still.resize((360,640)),(360*i,0))
    sheet.save(output / 'preview.png')
    (output / 'result.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(report,ensure_ascii=False),flush=True)
    report['render'] = render(board, output / 'render')
    (output / 'result.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(report,ensure_ascii=False),flush=True)


if __name__ == '__main__':
    main()
