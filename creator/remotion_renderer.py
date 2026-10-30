"""Trusted React components render validated data through a local Node child."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import time

ROOT = Path(__file__).resolve().parent / "remotion"


def renderer_identity():
    from .renderer import font_path, sha256
    sources = [ROOT/name for name in ("components.tsx", "video.tsx", "timeline.mjs", "render.mjs", "package-lock.json")]
    sources.extend(ROOT.parent/name for name in ("remotion_renderer.py","renderer.py","canvas.py","models.py","canvas_models.py"))
    digest = hashlib.sha256(b"remotion-v1")
    for path in sources:
        digest.update(path.read_bytes())
    digest.update(sha256(Path(font_path())).encode())
    digest.update(sha256(ROOT.parent/"assets/light_01.wav").encode())
    return "remotion-"+digest.hexdigest()


def prepared_board(board):
    from .canvas import resolved_scenes
    from .renderer import scene_layout
    scenes=[]
    for scene in resolved_scenes(board):
        data=scene.model_dump()
        if not (scene.canvas or scene.visual or scene.motion):
            _,_,heading,blocks=scene_layout(scene)
            data['_textLayout']={'heading':heading,'blocks':[{'y':y,'lines':lines} for y,lines in blocks]}
        scenes.append(data)
    return {**board.model_dump(), "scenes": scenes}


def invoke(job, directory, timeout, isolated=True, cache_directory=None):
    from .renderer import font_path
    node = shutil.which("node")
    if not node or not (ROOT/"node_modules/@remotion/renderer/package.json").is_file():
        raise RuntimeError("Remotion requires Node.js and npm ci in creator/remotion")
    directory.mkdir(parents=True, exist_ok=True)
    job.update(font=font_path(), bundleDirectory=str(((cache_directory or directory)/"bundles"/renderer_identity()).resolve()))
    jobfile=directory/f"remotion-job-{time.time_ns()}.json"
    jobfile.write_text(json.dumps(job,ensure_ascii=False),encoding="utf-8")
    kwargs={"cwd":str(ROOT),"stdout":subprocess.PIPE,"stderr":subprocess.PIPE}
    if os.name=="nt":
        kwargs["creationflags"]=subprocess.CREATE_NO_WINDOW
    elif isolated:
        kwargs["start_new_session"]=True
    child=subprocess.Popen([node,str(ROOT/"render.mjs"),str(jobfile.resolve())],**kwargs)
    try:
        out,error=child.communicate(timeout=timeout)
        if child.returncode:
            raise RuntimeError("Remotion rendering failed: "+error.decode(errors="replace")[-1800:])
        return json.loads(out.decode().strip().splitlines()[-1])
    except subprocess.TimeoutExpired as exc:
        if os.name=="nt":
            subprocess.run(["taskkill","/PID",str(child.pid),"/T","/F"],capture_output=True,timeout=10)
        elif isolated:
            os.killpg(child.pid,signal.SIGKILL)
        else:
            # Final rendering inherits the outer worker's process group; its deadline owns cancellation.
            child.terminate()
        child.kill()
        child.communicate()
        raise RuntimeError("Remotion rendering deadline exhausted") from exc
    finally:
        jobfile.unlink(missing_ok=True)


def preview_frames(board, scenes, directory, timeout):
    from .preview_reviewer import sample_times
    samples=[]
    for scene in scenes:
        index=next(i for i,s in enumerate(board.scenes) if s.sceneId==scene.sceneId)
        for timestamp in sample_times(scene):
            # Names derive from validated IDs/times, never from model-supplied file paths.
            samples.append({"sceneIndex":index,"timestampMs":timestamp,
                            "path":str((directory/f"{scene.sceneId}-{timestamp}.png").resolve())})
    invoke({"mode":"stills","storyboard":prepared_board(board),"samples":samples},directory,timeout,cache_directory=directory.parent.parent)
    return {(board.scenes[s['sceneIndex']].sceneId,s['timestampMs']):Path(s['path']) for s in samples}


def render(board, directory):
    from .renderer import validate_layout,probe
    validate_layout(board)
    directory=Path(directory)
    directory.mkdir(parents=True,exist_ok=True)
    identity=renderer_identity()
    digest=hashlib.sha256((identity+board.model_dump_json()).encode()).hexdigest()
    cache=directory.parent.parent/"remotion-cache"
    cache.mkdir(parents=True,exist_ok=True)
    cached=cache/f"{digest}.mp4"
    duration=sum(s.durationMs for s in board.scenes)
    hit=0
    if cached.is_file():
        try:probe(cached,duration);hit=1
        except Exception:cached.unlink()
    if not hit:
        temporary=directory/"remotion.tmp.mp4"
        # The outer render child supervises the complete Python -> Node -> Chromium process tree.
        invoke({"mode":"video","storyboard":prepared_board(board),"output":str(temporary.resolve())},
               directory.parent.parent,None,isolated=False)
        probe(temporary,duration)
        pending=cache/f"{digest}.{time.time_ns()}.tmp.mp4"
        shutil.copyfile(temporary,pending)
        pending.replace(cached)
        temporary.unlink()
    output=directory/"output.tmp.mp4"
    shutil.copyfile(cached,output)
    metadata=probe(output,duration)
    output.replace(directory/"output.mp4")
    metadata.update(path=str((directory/"output.mp4").resolve()),revision=board.revision,
                    cacheHits=hit,sceneCount=len(board.scenes),renderer=identity,storyboardSha256=sha256_json(board))
    (directory/"metadata.json").write_text(json.dumps(metadata),encoding="utf-8")
    return metadata


def sha256_json(board):
    return hashlib.sha256(board.model_dump_json().encode()).hexdigest()
