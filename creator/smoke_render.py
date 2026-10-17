"""Local render check: python -m creator.smoke_render [output-directory]."""
import json
from pathlib import Path
import sys

from .models import Storyboard
from .renderer import render
from .test_creator import storyboard


if __name__ == "__main__":
    root = Path(sys.argv[1] if len(sys.argv) > 1 else "creator/data/render-smoke").resolve()
    board = storyboard()
    first = render(Storyboard.model_validate(board), root / "1" / "render-1")
    board["revision"] = 2
    board["scenes"][0]["headline"] = "价格变化"
    second = render(Storyboard.model_validate(board), root / "2" / "render-2")
    assert second["cacheHits"] == 2, second
    assert first["sha256"] != second["sha256"]
    print(json.dumps({"first": first, "second": second}, ensure_ascii=False))
