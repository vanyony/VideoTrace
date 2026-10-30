import copy
from hashlib import sha256
import json
from pathlib import Path
import tempfile
import time
from types import SimpleNamespace
import unittest

from .agent import CreatorAgent
from .canvas import resolved_scenes, state_at
from .models import Storyboard
from .preview_reviewer import PreviewReviewer
from .test_creator import operation, settings, storyboard
from .store import Store
from .app import Worker


def canvas_board():
    value = storyboard()
    for scene in value["scenes"]:
        scene["canvas"] = {"layout": "full", "continuePrevious": True,
                            "elements": [{"id": "sun", "kind": "sprite", "sprite": "sun", "x": 300, "y": 500}]}
    value["scenes"][0]["canvas"]["continuePrevious"] = False
    value["scenes"][0]["canvas"]["elements"][0]["keyframes"] = [
        {"timeMs": 0, "x": 300}, {"timeMs": 10000, "x": 450}]
    return value


class FeedbackRecoveryTests(unittest.TestCase):
    def test_review_coordinate_edit_survives_continuation_without_changing_legacy_boards(self):
        from .canvas import materialize_review_edits
        old = canvas_board()
        self.assertEqual(state_at(resolved_scenes(Storyboard.model_validate(old))[1].canvas.elements[0], 750)["x"], 450)
        unchanged = materialize_review_edits(copy.deepcopy(old), old)
        self.assertEqual(unchanged, old)
        draft = copy.deepcopy(old)
        draft["scenes"][1]["canvas"]["elements"][0]["x"] = 200
        repaired = materialize_review_edits(draft, old)
        node = resolved_scenes(Storyboard.model_validate(repaired))[1].canvas.elements[0]
        self.assertEqual(state_at(node, 750)["x"], 200)
        self.assertEqual(repaired["scenes"][0], old["scenes"][0])
        explicit = copy.deepcopy(draft)
        explicit["scenes"][1]["canvas"]["elements"][0]["keyframes"] = [{"timeMs": 0, "x": 180}]
        actual = materialize_review_edits(explicit, old)
        self.assertEqual(state_at(resolved_scenes(Storyboard.model_validate(actual))[1].canvas.elements[0], 750)["x"], 180)

    def test_failed_review_uses_retry_budget_instead_of_successful_review_slot(self):
        attempts = []
        def review(op, board, *args):
            attempts.append(board)
            if len(attempts) == 1:
                raise ValueError("malformed preview JSON")
            return {"previewId": "ok", "draftSha256": sha256(board.model_dump_json().encode()).hexdigest(), "issues": []}
        agent = CreatorAgent(settings("."), SimpleNamespace(trace=lambda *a: None, emit=lambda *a: None),
                             None, None, model=lambda *_: None, reviewer=SimpleNamespace(review=review))
        state = {"operation": operation().model_dump(), "evidence_ids": ["e_0_10000"], "messages": [],
                 "tool_count": 0, "repairs": 0, "deadline": time.time()+30,
                 "calls": [{"id": "r", "function": {"name": "review_storyboard_preview",
                                                     "arguments": json.dumps({"storyboard": storyboard()})}}]}
        state.update(agent.tools_node(state))
        self.assertEqual(state["preview_calls"], 0)
        self.assertEqual(state["preview_attempts"], 1)
        state.update(agent.tools_node(state))
        self.assertEqual(state["preview_calls"], 1)
        self.assertEqual(state["preview_attempts"], 2)

    def test_harmless_top_level_metadata_does_not_discard_valid_preview_issues(self):
        from .test_remotion_renderer import preview_stub
        response = {"choices": [{"finish_reason": "stop", "message": {"content": json.dumps({
            "summary": "未发现明确问题", "issues": [], "confidence": .9, "elementIds": []})}}]}
        with tempfile.TemporaryDirectory() as directory:
            reviewer = PreviewReviewer(settings(directory), model=lambda *_: response,frame_renderer=preview_stub)
            result = reviewer.review(operation(), Storyboard.model_validate(storyboard()), ["s_1"], "", 30)
            self.assertEqual(result["issues"], [])
            self.assertNotIn("confidence", result)

    def test_warning_and_fallback_remain_visible_in_review_metadata(self):
        from .agent import review_metadata
        board = Storyboard.model_validate(storyboard())
        review = {"previewId": "a"*64, "draftSha256": sha256(board.model_dump_json().encode()).hexdigest(),
                  "summary": "标题仍有遮挡", "issues": [{"sceneId": "s_1", "severity": "warning", "confidence": .9,
                    "problem": "标题被遮挡", "suggestion": "下移太阳", "timestampMs": 1000, "category": "layout", "elementIds": []}]}
        metadata = review_metadata(board.model_dump(), review, "review_retry_exhausted")
        self.assertEqual(metadata["status"], "needs_attention")
        self.assertEqual(metadata["warningCount"], 1)
        self.assertEqual(metadata["fallbackReason"], "review_retry_exhausted")
        self.assertTrue(metadata["matchesSavedDraft"])

    def test_review_retry_budget_is_bounded_and_final_save_reserves_a_decision(self):
        attempts = []
        def broken(*args):
            attempts.append(1)
            raise ValueError("invalid service JSON")
        traces = []
        agent = CreatorAgent(settings("."), SimpleNamespace(trace=lambda *a: traces.append(a), emit=lambda *a: None),
                             None, None, model=lambda *_: self.fail("should reserve the last decision for saving"),
                             reviewer=SimpleNamespace(review=broken))
        state = {"operation": operation().model_dump(), "evidence_ids": ["e_0_10000"], "messages": [],
                 "tool_count": 0, "repairs": 0, "deadline": time.time()+30,
                 "calls": [{"id": "r", "function": {"name": "review_storyboard_preview",
                                                     "arguments": json.dumps({"storyboard": storyboard()})}}]}
        for _ in range(5):
            state.update(agent.tools_node(state))
        self.assertEqual(len(attempts), 4)
        self.assertEqual(state["preview_calls"], 0)
        self.assertEqual(state["preview_attempts"], 4)
        board = Storyboard.model_validate(storyboard())
        state.update(decisions=7, preview_draft=storyboard(), preview_result={"previewId": "known",
                     "draftSha256": sha256(board.model_dump_json().encode()).hexdigest(), "issues": []})
        state.update(agent.model_node(state))
        state.update(agent.tools_node(state))
        self.assertEqual(state["candidate"], storyboard())
        self.assertEqual(state["preview_fallback"], "review_retry_exhausted")
        state["tool_count"] = 12
        with self.assertRaisesRegex(RuntimeError, "budget exhausted"):
            agent.model_node(state)

    def test_worker_callback_exposes_unresolved_warnings_before_human_confirmation(self):
        with tempfile.TemporaryDirectory() as directory:
            store = Store(Path(directory)/"ops.sqlite")
            op = operation()
            store.accept(op)
            metadata = {"status": "needs_attention", "warningCount": 1, "summary": "标题仍被遮挡"}
            agent = SimpleNamespace(preview_metadata=lambda _: metadata)
            worker = Worker(settings(directory), store, agent, None, None)
            def run(_):
                worker.stop.set()
                return storyboard()
            agent.run = run
            worker.work()
            event = store.latest(op.operationId)["event"]
            self.assertEqual(event["status"], "AWAITING_CONFIRMATION")
            self.assertEqual(event["previewReview"], metadata)
            self.assertIn("人工核验", event["summary"])


if __name__ == "__main__":
    unittest.main()
