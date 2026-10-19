from contextlib import closing
import copy
import json
from hashlib import sha256
from pathlib import Path
import sqlite3
import tempfile
import time
from types import SimpleNamespace
import unittest

from langgraph.checkpoint.sqlite import SqliteSaver

from .agent import CreatorAgent, REVIEW
from .models import Storyboard, patch_storyboard
from .preview_reviewer import PreviewReviewer, sample_times, overlap_hints
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
            self.assertEqual(result["sampledFrames"][0]["timestampsMs"], [1500, 5000, 8500, 9500])
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
            issue.update(sceneId="s_1", elementIds=["missing-node"])
            with self.assertRaisesRegex(ValueError, "unknown element"):
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
                return {"summary": "建议缩短标题" if len(reviewed)==1 else "未发现明确问题", "issues": [],
                        "previewId": f"preview-{len(reviewed)}", "draftSha256": sha256(board.model_dump_json().encode()).hexdigest()}
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

    def tool_agent(self):
        traces = []
        def review(op, board, *args):
            return {"previewId": "checked", "draftSha256": sha256(board.model_dump_json().encode()).hexdigest(), "issues": []}
        agent = CreatorAgent(settings("."), SimpleNamespace(trace=lambda *a: traces.append(a[-1]), emit=lambda *a: None),
            None, None, model=lambda *_: None, reviewer=SimpleNamespace(review=review))
        state = {"operation": operation().model_dump(), "evidence_ids": ["e_0_10000"], "messages": [],
                 "tool_count": 0, "repairs": 0, "deadline": time.time()+30}
        return agent, state, traces

    @staticmethod
    def call(name, args):
        return {"id": "tool", "function": {"name": name, "arguments": json.dumps(args)}}

    def test_changed_draft_cannot_finish_graph_and_reference_saves_frozen_copy(self):
        agent, state, traces = self.tool_agent()
        state["calls"] = [self.call("review_storyboard_preview", {"storyboard": storyboard()})]
        state.update(agent.tools_node(state))
        changed = copy.deepcopy(storyboard())
        changed["scenes"][0]["headline"] = "检查后的新改动"
        state["calls"] = [self.call("save_storyboard", {"storyboard": changed})]
        result = agent.tools_node(state)
        self.assertIsNone(result["candidate"])
        self.assertEqual(result["repairs"], 0)
        self.assertIn("changed after preview", traces[-1]["error"])
        state.update(result)
        state["calls"] = [self.call("save_storyboard", {"reviewedPreviewId": "unknown"})]
        self.assertIsNone(agent.tools_node(state)["candidate"])
        state["preview_calls"] = 2  # Review budget used; the checked snapshot is still saveable.
        state["calls"] = [self.call("save_storyboard", {"reviewedPreviewId": "checked"})]
        self.assertEqual(agent.tools_node(state)["candidate"], storyboard())
        self.assertTrue(traces[-1]["previewReview"]["matchesSavedDraft"])

    def test_full_draft_requires_successful_preview_and_failed_review_cannot_bypass(self):
        agent, state, traces = self.tool_agent()
        full = storyboard()
        full["scenes"][0]["canvas"] = {"layout": "full", "elements": [{"id": "a", "kind": "circle"}]}
        state["calls"] = [self.call("save_storyboard", {"storyboard": full})]
        self.assertIsNone(agent.tools_node(state)["candidate"])
        self.assertIn("successful preview", traces[-1]["error"])
        state["preview_calls"] = 1
        state["calls"] = [self.call("save_storyboard", {"storyboard": storyboard()})]
        self.assertIsNone(agent.tools_node(state)["candidate"])

    def test_revision_cannot_bypass_preview_scope_or_checked_version(self):
        agent, state, traces = self.tool_agent()
        old = storyboard()
        parameters = {"instruction": "只修改第一幕", "storyboard": old, "baseRevision": 1, "targetSceneIds": ["s_1"]}
        state["operation"] = operation(type="REVISE", parameters=parameters).model_dump()
        patch = {"baseRevision": 1, "updates": [{"sceneId": "s_1", "headline": "新标题"}]}
        checked = patch_storyboard(old, patch)
        state["calls"] = [self.call("review_storyboard_preview", {"storyboard": checked})]
        state.update(agent.tools_node(state))
        patch["updates"][0]["headline"] = "未经检查的新标题"
        state["calls"] = [self.call("patch_scenes", patch)]
        self.assertIsNone(agent.tools_node(state)["candidate"])
        state["calls"] = [self.call("patch_scenes", {"baseRevision": 1, "reviewedPreviewId": "checked"})]
        self.assertEqual(agent.tools_node(state)["candidate"]["scenes"][0]["headline"], "新标题")

    def test_sampling_action_endpoint_and_concrete_overlap_hints(self):
        data = storyboard()
        data["scenes"][0]["canvas"] = {"layout": "full", "elements": [
            {"id": "caption", "kind": "text", "space": "screen", "text": "说明", "x": 360, "y": 1100, "width": 500, "height": 100},
            {"id": "drop", "kind": "sprite", "sprite": "droplet", "width": 90, "height": 90,
             "curve": {"points": [{"x": 360, "y": 600}, {"x": 360, "y": 800}, {"x": 360, "y": 1000}, {"x": 360, "y": 1100}], "endMs": 6000}}]}
        scene = Storyboard.model_validate(data).scenes[0]
        self.assertIn(6000, sample_times(scene))
        hint = overlap_hints(scene, sample_times(scene))[0]
        self.assertEqual((hint["textId"], hint["objectId"]), ("caption", "drop"))


if __name__ == "__main__":
    unittest.main()
