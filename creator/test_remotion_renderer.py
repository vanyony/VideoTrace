import copy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from PIL import Image

from .models import Storyboard
from .remotion_renderer import prepared_board, renderer_identity, preview_frames
from .test_creator import storyboard


def preview_stub(board, scenes, directory, timeout):
    """Unit-only image fixture. Real Chromium/MP4 checks run separately."""
    from .preview_reviewer import sample_times
    frames={}
    for scene in scenes:
        for time in sample_times(scene):
            path=directory/f'{scene.sceneId}-{time}.png'
            Image.new('RGB',(720,1280),'#172641').save(path)
            frames[scene.sceneId,time]=path
    return frames


class RemotionRendererTests(unittest.TestCase):
    def test_legacy_render_cache_cannot_bypass_the_new_renderer(self):
        from .execution import RenderGraph
        from .test_creator import operation, settings
        from types import SimpleNamespace
        with tempfile.TemporaryDirectory() as temp:
            board=storyboard()
            op=operation(type='RENDER',parameters={'storyboard':board,'baseRevision':1,'confirmedRevision':1})
            config=settings(temp)
            directory=config.data_dir/op.taskId/'1'/op.operationId
            directory.mkdir(parents=True)
            (directory/'output.mp4').write_bytes(b'old')
            (directory/'metadata.json').write_text(json.dumps({'sha256':'oldhash'}))
            graph=RenderGraph(config,SimpleNamespace(trace=lambda *a:None),None)
            with patch('creator.execution.minio_storage'),patch('creator.execution.probe',return_value={'sha256':'oldhash'}),patch('creator.execution.run_child',return_value={'revision':1}) as child,patch('creator.execution.upload',return_value={'objectKey':'new'}):
                self.assertEqual(graph.render_node({'operation':op.model_dump()})['candidate'],{'objectKey':'new'})
                child.assert_called_once()

    def test_inherited_state_is_materialized_before_passing_to_react(self):
        from .test_feedback_recovery import canvas_board
        board=Storyboard.model_validate(canvas_board())
        payload=prepared_board(board)
        self.assertEqual(payload['scenes'][1]['canvas']['elements'][0]['x'],450)
        self.assertEqual(board.scenes[1].canvas.elements[0].x,300)

    def test_preview_uses_whole_board_scene_index_and_trusted_output_paths(self):
        board=Storyboard.model_validate(storyboard())
        with tempfile.TemporaryDirectory() as temp,patch('creator.remotion_renderer.invoke') as invoke:
            paths=preview_frames(board,[board.scenes[1]],Path(temp),10)
            job=invoke.call_args.args[0]
            self.assertEqual({s['sceneIndex'] for s in job['samples']},{1})
            self.assertEqual(len(paths),4)
            self.assertEqual(job['storyboard'],prepared_board(board))
            self.assertTrue(all(p.parent==Path(temp) for p in paths.values()))

    def test_renderer_identity_includes_components_and_fonts(self):
        before=renderer_identity()
        original=Path.read_bytes
        def changed(path):
            data=original(path)
            return data+b'change' if path.name=='components.tsx' else data
        with patch.object(Path,'read_bytes',changed):
            self.assertNotEqual(before,renderer_identity())
