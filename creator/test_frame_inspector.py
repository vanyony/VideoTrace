import json
import time
import unittest
from types import SimpleNamespace

from .agent import CreatorAgent
from .frame_inspector import FrameInspector
from .test_creator import operation, settings


class FrameInspectorTests(unittest.TestCase):
    def test_separates_observations_and_does_not_return_image_bytes(self):
        class Java:
            def evidence(self, op, action, args):
                assert action == "frame"
                return {"imageDataUrl": "data:image/jpeg;base64,YWJj", "timestampMs": 5000, "sha256": "a" * 64}
        def vision(messages, timeout):
            self.assertTrue(messages[1]["content"][0]["image_url"]["url"].startswith("data:image/jpeg"))
            return {"choices": [{"finish_reason": "stop", "message": {"content": json.dumps({
                "visibleText": "价格对比", "observations": "两组柱状图", "inferences": "无法确定单位"})}}]}
        result = FrameInspector(settings("."), Java(), vision).inspect(operation(), {
            "evidenceId": "e_0_10000", "timestampMs": 5000, "question": "图表说明什么？"}, 20)
        self.assertEqual(result["visibleText"], "价格对比")
        self.assertNotIn("imageDataUrl", result)
        self.assertNotIn("base64", json.dumps(result))
        with self.assertRaises(ValueError):
            FrameInspector(settings("."), Java(), lambda *_: {"choices": [{"finish_reason": "length"}]}).inspect(
                operation(), {"evidenceId": "e_0_10000", "timestampMs": 5000, "question": "?"}, 20)

    def test_requires_retrieved_evidence_and_limits_inspections(self):
        traces = []
        store = SimpleNamespace(trace=lambda *args: traces.append(args[-1]), emit=lambda *args: None)
        calls = []
        inspector = SimpleNamespace(inspect=lambda op, args, timeout: calls.append(args) or {"observations": "图表"})
        agent = CreatorAgent(settings("."), store, None, None, model=lambda *_: None, inspector=inspector)
        op = operation(type="CONVERSE", parameters={"requirement": "解释", "instruction": "解释图表", "durationSeconds": 30})
        def call(ref):
            return {"id": "inspect", "function": {"name": "inspect_video_frame", "arguments": json.dumps({
                "evidenceId": ref, "timestampMs": 5000, "question": "解释图表"})}}
        state = {"operation": op.model_dump(), "evidence_ids": ["e_0_10000"], "messages": [], "tool_count": 0,
                 "repairs": 0, "deadline": time.time() + 30, "action": "DISCUSS",
                 "calls": [call("unknown"), call("e_0_10000"), call("e_0_10000"), call("e_0_10000")]}
        result = agent.tools_node(state)
        self.assertEqual(len(calls), 2)
        self.assertEqual(result["frame_calls"], 2)
        self.assertIn("retrieved evidence", traces[0]["error"])
        self.assertIn("budget exhausted", traces[-1]["error"])


if __name__ == "__main__":
    unittest.main()
