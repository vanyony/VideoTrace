import copy
import unittest

import numpy as np
from pydantic import ValidationError

from .canvas import state_at
from .canvas_models import CanvasElement
from .models import Scene, validate_candidate, patch_storyboard
from .renderer import frame
from .test_creator import completion, storyboard, operation


def example_canvas():
    return {"elements": [
        {"id": "source", "kind": "rectangle", "x": 180, "y": 550, "width": 180, "height": 100, "text": "原始视频",
         "keyframes": [{"timeMs": 0, "x": 150, "opacity": 0}, {"timeMs": 1500, "x": 180, "opacity": 1}]},
        {"id": "agent", "kind": "icon", "icon": "agent", "x": 520, "y": 750, "width": 120, "height": 120,
         "keyframes": [{"timeMs": 0, "scale": .5}, {"timeMs": 2000, "scale": 1}]},
        {"id": "link", "kind": "arrow", "fromId": "source", "toId": "agent", "z": 0}]}


class CanvasTests(unittest.TestCase):
    def board(self):
        data = storyboard()
        data["scenes"][0]["canvas"] = example_canvas()
        return data

    def test_interpolation_sparse_fields_and_animation(self):
        data = self.board()
        board = validate_candidate(data, operation(), {"e_0_10000"})
        scene = Scene.model_validate(board["scenes"][0])
        element = scene.canvas.elements[0]
        self.assertEqual(state_at(element, -100)["x"], 150)
        self.assertAlmostEqual(state_at(element, 750)["opacity"], .5)
        self.assertEqual(state_at(element, 9000)["x"], 180)
        before, after = frame(scene, 0), frame(scene, 3)
        self.assertEqual(after.shape, (1280, 720, 3))
        self.assertTrue(np.any(before != after))
        for easing in ("linear", "easeInOut", "easeOut"):
            e = CanvasElement.model_validate({"id": "a", "kind": "circle", "keyframes": [
                {"timeMs": 1000, "x": 500, "easing": easing}, {"timeMs": 2000, "opacity": .5}]})
            self.assertEqual(state_at(e, 1500)["x"], 500)
            self.assertTrue(360 <= state_at(e, 500)["x"] <= 500)

    def test_rejects_bad_references_times_bounds_and_text(self):
        mutations = [
            lambda c: c["elements"][2].update(toId="missing"),
            lambda c: c["elements"][2].update(toId="source"),
            lambda c: c["elements"][1].update(id="source"),
            lambda c: c["elements"][0].update(keyframes=[{"timeMs": 1500}, {"timeMs": 1000}]),
            lambda c: c["elements"][0].update(keyframes=[{"timeMs": 11000}]),
            lambda c: c["elements"][0].update(keyframes=[{"timeMs": 0, "x": 50}]),
            lambda c: c["elements"][0].update(width=40),
            lambda c: c["elements"][0].update(fill="file:///private"),
            lambda c: c["elements"][0].update(x=float("nan")),
            lambda c: c["elements"][0].update(keyframes=[{"timeMs": 1000, "x": 50}, {"timeMs": 2000, "x": 180}]),
            lambda c: c["elements"].extend(copy.deepcopy(c["elements"]) * 7),
            lambda c: c["elements"][0].update(keyframes=[{"timeMs": i * 100} for i in range(5)]),
        ]
        for mutate in mutations:
            with self.subTest(mutation=mutate):
                data = self.board(); mutate(data["scenes"][0]["canvas"])
                with self.assertRaises(ValueError):
                    validate_candidate(data, operation(), {"e_0_10000"})
        both = self.board()
        both["scenes"][0]["visual"] = {"layout": "merge", "items": [{"icon": "file", "label": "甲"}, {"icon": "agent", "label": "乙"}]}
        with self.assertRaises(ValidationError):
            Scene.model_validate(both["scenes"][0])

    def test_local_revision_and_mock_agent_canvas_save(self):
        old = self.board()
        patched = patch_storyboard(old, {"baseRevision": 1, "updates": [{"sceneId": "s_1", "canvas": example_canvas()}]})
        op = operation(type="REVISE", parameters={"instruction": "只改第一幕", "storyboard": old,
            "baseRevision": 1, "targetSceneIds": ["s_1"]})
        accepted = validate_candidate(patched, op, set())
        self.assertEqual(accepted["scenes"][1:], old["scenes"][1:])
        # Reuse the real LangGraph harness with deterministic tool responses.
        from .test_creator import CreatorTests
        harness = CreatorTests()
        result, _, _ = harness.run_conversation([
            completion("choose_action", {"action": "CREATE"}), completion("search_video", {"query": "视频"}),
            completion("save_storyboard", old)])
        self.assertEqual(result["storyboard"]["scenes"][0]["canvas"]["elements"][2]["toId"], "agent")

    def test_all_primitives_and_agent_repairs_invalid_canvas(self):
        data = self.board()
        elements = data["scenes"][0]["canvas"]["elements"]
        elements.extend([
            {"id": "circle", "kind": "circle", "x": 360, "y": 900, "width": 60, "height": 60},
            {"id": "label", "kind": "text", "x": 360, "y": 450, "width": 240, "height": 60, "text": "自由组合"},
            {"id": "line", "kind": "line", "fromId": "circle", "toId": "agent"},
        ])
        board = validate_candidate(data, operation(), {"e_0_10000"})
        self.assertEqual(frame(Scene.model_validate(board["scenes"][0]), 2).shape, (1280, 720, 3))
        invalid = copy.deepcopy(data)
        invalid["scenes"][0]["canvas"]["elements"][0]["keyframes"][0]["x"] = 5
        from .test_creator import CreatorTests
        result, offered, _ = CreatorTests().run_conversation([
            completion("choose_action", {"action": "CREATE"}), completion("search_video", {"query": "视频"}),
            completion("save_storyboard", invalid), completion("save_storyboard", data)])
        self.assertIsNotNone(result["storyboard"]["scenes"][0]["canvas"])
        self.assertIn("save_storyboard", offered[-1])


if __name__ == "__main__":
    unittest.main()
