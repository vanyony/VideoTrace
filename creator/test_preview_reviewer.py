from contextlib import closing
import copy
import json
from pathlib import Path
import sqlite3
import tempfile
import time
from types import SimpleNamespace
import unittest

from langgraph.checkpoint.sqlite import SqliteSaver

from .agent import CreatorAgent, REVIEW
from .models import Storyboard
from .preview_reviewer import PreviewReviewer
from .store import Store
from .test_creator import Java, completion, operation, settings, storyboard


def feedback(issues=None):
    return {"choices": [{"finish_reason": "stop", "message": {"content": json.dumps({
        "summary": "主体可见，建议检查标题位置", "issues": issues or []})}}]}


class PreviewReviewerTests(unittest.TestCase):
    def test_rendered_strips_feedback_and_completed_result_cache(self):
        calls = []
        def vision(messages, timeout):
            images = [p for p in messages[1]["content"] if p["type"] == "image_url"]
            self.assertEqual(len(images), 1)
            self.assertTrue(images[0]["image_url"]["url"].startswith("data:image/jpeg;base64,"))
            calls.append(messages)
            return feedback([{"sceneId": "s_1", "timestampMs": 5000, "severity": "suggestion",
                "category": "layout", "problem": "标题与正文距离偏近", "suggestion": "增加上下间距", "confidence": .8}])
        with tempfile.TemporaryDirectory() as directory:
            reviewer = PreviewReviewer(settings(directory), vision)
            board = Storyboard.model_validate(storyboard())
            result = reviewer.review(operation(), board, ["s_1"], "排版", 30)
            cached = reviewer.review(operation(), board, ["s_1"], "排版", 30)
            self.assertEqual(len(calls), 1)
            self.assertTrue(cached["cached"])
            self.assertEqual(result["sampledFrames"][0]["timestampsMs"], [2000, 5000, 8000])
            self.assertTrue(result["advisoryOnly"])
            self.assertNotIn("base64", json.dumps(result))
            self.assertTrue((Path(directory)/"preview-reviews"/result["previewId"]/"s_1.jpg").is_file())
            changed = board.model_copy(update={"title": "不同分镜"})
            self.assertNotEqual(result["previewId"], reviewer.review(operation(), changed, ["s_1"], "排版", 30)["previewId"])
            self.assertEqual(len(calls), 2)

    def test_rejects_unsampled_feedback_and_invalid_scene_selection(self):
        issue = {"sceneId": "unknown", "timestampMs": 5000, "severity": "warning", "category": "contrast",
                 "problem": "无法阅读", "suggestion": "提高对比度", "confidence": .9}
        with tempfile.TemporaryDirectory() as directory:
            reviewer = PreviewReviewer(settings(directory), lambda *_: feedback([issue]))
            board = Storyboard.model_validate(storyboard())
            for ids in (["missing"], ["s_1", "s_1"]):
                with self.assertRaises(ValueError):
                    reviewer.review(operation(), board, ids, "", 30)
            with self.assertRaisesRegex(ValueError, "unsampled"):
                reviewer.review(operation(), board, ["s_1"], "", 30)
            self.assertFalse(list(Path(directory).rglob("result.json")))

    def test_agent_feedback_repair_then_save_and_checkpoint_reuse(self):
        with tempfile.TemporaryDirectory() as directory, closing(sqlite3.connect(":memory:", check_same_thread=False)) as connection:
            store = Store(Path(directory)/"ops.sqlite")
            revised = copy.deepcopy(storyboard())
            revised["scenes"][0]["headline"] = "修正后的标题"
            responses = iter([completion("search_video", {"query": "重点"}),
                completion("review_storyboard_preview", {"storyboard": storyboard(), "sceneIds": ["s_1"]}),
                completion("review_storyboard_preview", {"storyboard": revised, "sceneIds": ["s_1"]}),
                completion("save_storyboard", {"storyboard": revised})])
            reviewed = []
            def review(op, board, scene_ids, focus, timeout):
                reviewed.append(board.title)
                return {"summary": "建议缩短标题" if len(reviewed)==1 else "未发现明确问题", "issues": []}
            def model(messages, tools, timeout):
                self.assertIn("review_storyboard_preview", [t["function"]["name"] for t in tools])
                return next(responses)
            agent = CreatorAgent(settings(directory), store, Java(), SqliteSaver(connection), model=model,
                                 reviewer=SimpleNamespace(review=review))
            self.assertEqual(agent.run(operation()), revised)
            self.assertEqual(agent.run(operation()), revised)
            self.assertEqual(len(reviewed), 2)
            with store.connect() as db:
                traces = [dict(row) for row in db.execute("SELECT name,result FROM tool_traces")]
            self.assertEqual(sum(t["name"]=="review_storyboard_preview" for t in traces), 2)
            self.assertNotIn("base64", json.dumps(traces))
        # Nested storyboard schema definitions remain resolvable at the tool root.
        self.assertIn("Scene", REVIEW["function"]["parameters"]["$defs"])

    def test_agent_rejects_unretrieved_evidence_discussion_and_excess_reviews(self):
        calls, traces = [], []
        agent = CreatorAgent(settings("."), SimpleNamespace(trace=lambda *a: traces.append(a[-1]), emit=lambda *a: None),
            None, None, model=lambda *_: None, reviewer=SimpleNamespace(review=lambda *a: calls.append(a) or {"summary": "ok"}))
        op = operation()
        call = {"id": "review", "function": {"name": "review_storyboard_preview", "arguments": json.dumps({"storyboard": storyboard()})}}
        state = {"operation": op.model_dump(), "evidence_ids": [], "messages": [], "tool_count": 0, "repairs": 0,
                 "deadline": time.time()+30, "calls": [call]}
        agent.tools_node(state)
        self.assertEqual(len(calls), 0)
        self.assertIn("retrieved", traces[-1]["error"])
        state["evidence_ids"] = ["e_0_10000"]
        state["calls"] = [call, call, call]
        result = agent.tools_node(state)
        self.assertEqual(len(calls), 2)
        self.assertEqual(result["preview_calls"], 2)
        self.assertIn("budget exhausted", traces[-1]["error"])
        discussion = operation(type="CONVERSE", parameters={"requirement": "讨论", "instruction": "先讨论", "durationSeconds": 30})
        state.update(operation=discussion.model_dump(), action="DISCUSS", calls=[call])
        agent.tools_node(state)
        self.assertEqual(len(calls), 2)
        self.assertIn("not available", traces[-1]["error"])

    def test_preview_failure_does_not_prevent_human_review_draft(self):
        with tempfile.TemporaryDirectory() as directory, closing(sqlite3.connect(":memory:", check_same_thread=False)) as connection:
            store = Store(Path(directory)/"ops.sqlite")
            responses = iter([completion("search_video", {"query": "重点"}),
                completion("review_storyboard_preview", {"storyboard": storyboard()}),
                completion("save_storyboard", {"storyboard": storyboard()})])
            def unavailable(*args):
                raise ValueError("preview vision unavailable")
            agent = CreatorAgent(settings(directory), store, Java(), SqliteSaver(connection), model=lambda *_: next(responses),
                                 reviewer=SimpleNamespace(review=unavailable))
            self.assertEqual(agent.run(operation()), storyboard())
            with store.connect() as db:
                trace = json.loads(db.execute("SELECT result FROM tool_traces WHERE name='review_storyboard_preview'").fetchone()[0])
            self.assertIn("unavailable", trace["error"])


if __name__ == "__main__":
    unittest.main()
