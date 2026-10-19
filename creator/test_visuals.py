import unittest

import numpy as np
from pydantic import ValidationError

from .models import Scene, patch_storyboard
from .renderer import frame, validate_layout
from .test_creator import storyboard


class VisualTests(unittest.TestCase):
    def test_layouts_themes_and_motion(self):
        from .models import Storyboard
        for layout in ("pipeline", "split", "merge", "compare", "bars", "orbit"):
            for theme in ("dark", "light", "warm"):
                for motion in ("slide", "pulse", "draw", "reveal"):
                    data = storyboard()
                    data["scenes"][0]["visual"] = {"layout": layout, "theme": theme, "motion": motion,
                        "items": [{"icon": icon, "label": icon, "value": i * 30} for i, icon in enumerate(("video", "audio", "agent", "phone"))]}
                    board = Storyboard.model_validate(data)
                    validate_layout(board)
                    a, b = frame(board.scenes[0], 0), frame(board.scenes[0], 2)
                    self.assertEqual(a.shape, (1280, 720, 3))
                    self.assertTrue(np.any(a != b))

    def test_legacy_and_patch_validation(self):
        old = storyboard()
        self.assertNotIn("visual", Scene.model_validate(old["scenes"][0]).model_dump())
        visual = {"layout": "merge", "items": [{"icon": "audio", "label": "声音"}, {"icon": "agent", "label": "助手"}]}
        updated = patch_storyboard(old, {"baseRevision": 1, "updates": [{"sceneId": "s_1", "visual": visual}]})
        self.assertEqual(Scene.model_validate(updated["scenes"][0]).visual.layout, "merge")
        visual["items"][0]["icon"] = "execute-python"
        with self.assertRaises(ValidationError):
            Scene.model_validate(updated["scenes"][0] | {"visual": visual})


if __name__ == "__main__":
    unittest.main()
