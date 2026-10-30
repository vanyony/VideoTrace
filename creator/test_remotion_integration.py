"""Opt in with CREATOR_REMOTION_INTEGRATION=1; renders real Chromium frames and MP4."""
import json
import os
from pathlib import Path
import tempfile
import threading
import unittest

from .models import Storyboard


@unittest.skipUnless(os.getenv('CREATOR_REMOTION_INTEGRATION')=='1','real Remotion integration is opt-in')
class RemotionIntegrationTests(unittest.TestCase):
    def test_worker_cancellation_never_publishes_partial_output(self):
        from .execution import run_child
        board=Storyboard.model_validate_json((Path(__file__).parent/'testdata/remotion-board.json').read_text(encoding='utf8'))
        board=board.model_copy(update={'title':'取消任务验证'})
        with tempfile.TemporaryDirectory() as temp:
            directory=Path(os.getenv('CREATOR_REMOTION_TEST_DIR',temp))/'task'/'1'/'cancelled'
            directory.mkdir(parents=True,exist_ok=True)
            job=directory/'job.json'
            job.write_text(json.dumps({'storyboard':board.model_dump(),'directory':str(directory)}),encoding='utf8')
            stop=threading.Event();timer=threading.Timer(10,stop.set);timer.start()
            try:
                with self.assertRaisesRegex(RuntimeError,'interrupted'):
                    run_child(job,180,stop)
                self.assertFalse((directory/'metadata.json').exists())
                self.assertFalse((directory/'output.mp4').exists())
            finally:timer.cancel()

    def test_real_worker_output_cache_audio_and_preview_agree(self):
        import numpy as np
        from moviepy import VideoFileClip
        from PIL import Image
        from .execution import run_child
        from .remotion_renderer import preview_frames,renderer_identity
        from .canvas import resolved_scenes
        board=Storyboard.model_validate_json((Path(__file__).parent/'testdata/remotion-board.json').read_text(encoding='utf8'))
        with tempfile.TemporaryDirectory() as temp:
            root=Path(os.getenv('CREATOR_REMOTION_TEST_DIR',temp))/'task'/'1'
            def job(name):
                directory=root/name;directory.mkdir(parents=True,exist_ok=True)
                path=directory/'job.json'
                path.write_text(json.dumps({'storyboard':board.model_dump(),'directory':str(directory)}),encoding='utf8')
                return run_child(path,180)
            first=job('first');second=job('second')
            self.assertEqual(first['renderer'],renderer_identity())
            self.assertEqual(first['sha256'],second['sha256'])
            self.assertEqual(second['cacheHits'],1)
            chosen=[resolved_scenes(board)[i] for i in (0,5,7)]
            previews=preview_frames(board,chosen,root/'preview',120)
            errors=[]
            with VideoFileClip(first['path']) as clip:
                self.assertEqual(clip.size,[720,1280])
                self.assertAlmostEqual(clip.duration,16,places=2)
                self.assertEqual(clip.fps,24)
                self.assertIsNotNone(clip.audio)
                self.assertGreater(float(np.abs(clip.audio.to_soundarray(fps=8000)).mean()),.0001)
                for index in (0,5,7):
                    scene=board.scenes[index]
                    with Image.open(previews[scene.sceneId,1000]) as still:
                        encoded=clip.get_frame(index*2+1).astype(float)
                        error=float(np.abs(encoded-np.asarray(still.convert('RGB'),dtype=float)).mean())
                        errors.append(error)
                        self.assertLess(error,4)
            (root/'verification.json').write_text(json.dumps({'artifact':first,'cachedArtifact':second,'previewMeanPixelErrors':errors,'previewFrames':len(previews)},ensure_ascii=False,indent=2),encoding='utf8')
