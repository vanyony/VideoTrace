import unittest

import numpy as np

from .models import Scene, validate_candidate
from .renderer import frame
from .motion import transition_frame
from .test_creator import storyboard, operation


class MotionTests(unittest.TestCase):
    def test_styles_animate_and_keep_legacy_fields_compatible(self):
        for style in ("launch", "kinetic"):
            data = storyboard()
            for scene in data["scenes"]:
                scene["durationMs"] = 5000
                scene["motion"] = {"style": style, "words": ["内容", "变成", "作品"], "accent": "violet"}
            board = validate_candidate(data, operation(parameters={"requirement": "15秒大字", "durationSeconds": 15}), {"e_0_10000"})
            scene = Scene.model_validate(board["scenes"][0])
            a, b = frame(scene, 0), frame(scene, 1.6)
            self.assertEqual(b.shape, (1280, 720, 3))
            self.assertTrue(np.any(a != b))
        self.assertNotIn("motion", Scene.model_validate(storyboard()["scenes"][0]).model_dump())

    def test_rejects_long_words_and_multiple_modes(self):
        scene = storyboard()["scenes"][0]
        scene["motion"] = {"style": "kinetic", "words": ["字" * 13]}
        with self.assertRaises(ValueError):
            Scene.model_validate(scene)
        scene["motion"]["words"] = ["内容"]
        scene["canvas"] = {"elements": [{"id": "a", "kind": "circle"}]}
        with self.assertRaises(ValueError):
            Scene.model_validate(scene)

    def test_transitions_keep_duration_and_frame_dimensions(self):
        previous = np.zeros((1280, 720, 3), dtype=np.uint8)
        current = previous + 200
        for effect in ("fade", "push", "wipe"):
            self.assertTrue(np.array_equal(transition_frame(previous, current, 0, effect), previous))
            self.assertTrue(np.array_equal(transition_frame(previous, current, .3, effect), current))
            mid = transition_frame(previous, current, .15, effect)
            self.assertEqual(mid.shape, current.shape)
            self.assertTrue(np.any(mid != previous) and np.any(mid != current))


if __name__ == "__main__":
    unittest.main()
