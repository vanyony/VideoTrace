import copy
import unittest

import numpy as np
from pydantic import ValidationError

from .canvas import state_at, continued_scene, resolved_scenes, camera_at
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

    def test_layout_reports_multiple_elements_and_scenes_for_one_repair(self):
        data = self.board()
        data["scenes"][0]["canvas"]["elements"][0].update(width=40, height=12)
        data["scenes"][1]["canvas"] = {"elements": [
            {"id": "other_label", "kind": "text", "text": "另一段超长说明", "width": 30, "height": 12}]}
        with self.assertRaises(ValueError) as result:
            validate_candidate(data, operation(), {"e_0_10000"})
        message = str(result.exception)
        self.assertIn("source", message)
        self.assertIn("other_label", message)
        self.assertIn("need height", message)

    def test_components_curves_rotation_and_continuation(self):
        data = self.board()
        curve = {"points": [{"x": 180, "y": 800}, {"x": 200, "y": 640},
                             {"x": 400, "y": 500}, {"x": 500, "y": 550}], "endMs": 2000}
        data["scenes"][0]["canvas"] = {"elements": [
            {"id": "water", "kind": "sprite", "sprite": "droplet", "width": 50, "height": 60, "curve": curve},
            {"id": "sun", "kind": "sprite", "sprite": "sun", "x": 360, "y": 550,
             "width": 90, "height": 90, "pulse": True, "keyframes": [{"timeMs": 2000, "rotation": 90}]},
            {"id": "flow", "kind": "path", "curve": {**curve, "loop": True}},
        ]}
        data["scenes"][1]["canvas"] = {"continuePrevious": True, "elements": [
            {"id": "water", "kind": "sprite", "sprite": "droplet", "width": 50, "height": 60}]}
        board = validate_candidate(data, operation(), {"e_0_10000"})
        from .models import Storyboard
        scenes = resolved_scenes(Storyboard.model_validate(board))
        self.assertEqual(state_at(scenes[0].canvas.elements[0], 2000)["x"], 500)
        self.assertEqual(scenes[1].canvas.elements[0].x, 500)
        self.assertEqual(scenes[1].canvas.elements[0].y, 550)
        self.assertAlmostEqual(state_at(scenes[0].canvas.elements[1], 1000)["rotation"], 45)
        self.assertFalse(np.array_equal(frame(scenes[0], .3), frame(scenes[0], 1.3)))
        for sprite in ("sun", "earth", "cloud", "droplet", "factory", "truck"):
            from .sprites import sprite_image
            self.assertIsNotNone(sprite_image(sprite, "#ffe080", "#3e98bd").getbbox())

    def test_animation_rejects_unsafe_paths_and_inherited_bounds(self):
        for bad in (
            {"kind": "path"},
            {"kind": "sprite", "sprite": "python-code"},
            {"kind": "sprite", "curve": {"points": [{"x": 50, "y": 400}]*4, "endMs": 2000}},
            {"kind": "path", "curve": {"points": [{"x": 200, "y": 600}]*4, "endMs": 11000}},
            {"kind": "path", "curve": {"points": [{"x": 200, "y": 600}]*4, "endMs": 2000}, "pulse": True},
        ):
            data = self.board()
            data["scenes"][0]["canvas"] = {"elements": [{"id": "bad", **bad}]}
            with self.assertRaises(ValueError):
                validate_candidate(data, operation(), {"e_0_10000"})
        data = self.board()
        data["scenes"][0]["canvas"] = {"elements": [{"id": "same", "kind": "circle", "x": 130, "y": 500, "width": 80}]}
        data["scenes"][1]["canvas"] = {"continuePrevious": True, "elements": [
            {"id": "same", "kind": "circle", "width": 300}]}
        with self.assertRaisesRegex(ValueError, "animated bounds"):
            validate_candidate(data, operation(), {"e_0_10000"})

    def test_scoped_revision_preserves_legacy_canvas_json(self):
        old = self.board()
        old["scenes"][1]["canvas"] = example_canvas()
        # This old document intentionally lacks all newly introduced defaults.
        revised = patch_storyboard(old, {"baseRevision": 1, "updates": [
            {"sceneId": "s_1", "headline": "只修改这一幕"}]})
        op = operation(type="REVISE", parameters={"instruction": "只改第一幕", "storyboard": old,
            "baseRevision": 1, "targetSceneIds": ["s_1"]})
        accepted = validate_candidate(revised, op, set())
        self.assertEqual(accepted["scenes"][1], old["scenes"][1])

    def test_full_canvas_camera_and_offscreen_entrance(self):
        data = self.board()
        data["scenes"][0]["canvas"] = {"layout": "full", "background": "#eef6ed", "camera": [
            {"timeMs": 0, "x": 360, "y": 640, "zoom": 1},
            {"timeMs": 2000, "x": 480, "y": 600, "zoom": 1.4}], "elements": [
            {"id": "sun", "kind": "sprite", "sprite": "sun", "x": -160, "y": 500, "width": 220, "height": 220,
             "keyframes": [{"timeMs": 1000, "x": 300}]},
            {"id": "title", "kind": "text", "text": "出发", "space": "screen", "x": 360, "y": 170, "width": 500, "height": 180, "fontSize": 110},
            {"id": "sea", "kind": "sprite", "sprite": "water", "x": 360, "y": 1150, "width": 900, "height": 450}]}
        board = validate_candidate(data, operation(), {"e_0_10000"})
        scene = Scene.model_validate(board["scenes"][0])
        self.assertEqual(camera_at(scene.canvas, 2000)["zoom"], 1.4)
        self.assertAlmostEqual(camera_at(scene.canvas, 1000)["x"], 420)
        self.assertTrue(np.any(frame(scene, 0) != frame(scene, 2)))
        self.assertTrue(np.array_equal(frame(scene, 0)[90:250], frame(scene, 2)[90:250]))
        next_scene = scene.model_copy(update={"canvas": scene.canvas.model_copy(update={"camera": [], "continuePrevious": True})})
        resolved = continued_scene(next_scene, scene)
        self.assertEqual(camera_at(resolved.canvas, 0)["x"], 480)
        self.assertEqual(camera_at(resolved.canvas, 0)["zoom"], 1.4)

    def test_full_canvas_rejects_resource_and_timeline_overflow(self):
        for extra in (
            {"camera": [{"timeMs": 11000, "zoom": 1}]},
            {"camera": [{"timeMs": 2000}, {"timeMs": 1000}]},
            {"elements": [{"id": "huge", "kind": "circle", "width": 1280, "height": 1280, "scale": 4}]},
        ):
            data = self.board()
            data["scenes"][0]["canvas"] = {"layout": "full", "elements": [{"id": "a", "kind": "circle"}], **extra}
            with self.assertRaises(ValueError):
                validate_candidate(data, operation(), {"e_0_10000"})


if __name__ == "__main__":
    unittest.main()
