import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import time
from urllib.parse import urlsplit

from langgraph.graph import END, START, StateGraph
from minio import Minio
from minio.error import S3Error

from .agent import AgentState
from .models import Operation
from .renderer import probe


def minio_storage():
    access, secret = os.getenv("CREATOR_MINIO_ACCESS_KEY"), os.getenv("CREATOR_MINIO_SECRET_KEY")
    if not access or not secret:
        raise RuntimeError("Configure separate restricted CREATOR_MINIO_ACCESS_KEY and CREATOR_MINIO_SECRET_KEY")
    endpoint = urlsplit(os.getenv("CREATOR_MINIO_ENDPOINT", os.getenv("MINIO_ENDPOINT", "http://127.0.0.1:9000")))
    if endpoint.scheme not in ("http", "https") or not endpoint.netloc or endpoint.path not in ("", "/"):
        raise ValueError("invalid creator MinIO endpoint")
    return Minio(endpoint.netloc, access_key=access, secret_key=secret, secure=endpoint.scheme == "https"), os.getenv("CREATOR_MINIO_BUCKET", os.getenv("MINIO_BUCKET", "media"))


def upload(operation, path, metadata):
    client, bucket = minio_storage()
    key = f"creations/{operation.taskId}/{metadata['revision']}/{operation.operationId}/output.mp4"
    try:
        current = client.stat_object(bucket, key)
    except S3Error as error:
        if error.code not in ("NoSuchKey", "NoSuchObject"):
            raise
        current = None
    if current is None or current.size != metadata["sizeBytes"] or current.metadata.get("x-amz-meta-sha256") != metadata["sha256"]:
        client.fput_object(bucket, key, str(path), content_type="video/mp4", metadata={"sha256": metadata["sha256"]})
    stat = client.stat_object(bucket, key)
    if stat.size != metadata["sizeBytes"] or stat.metadata.get("x-amz-meta-sha256") != metadata["sha256"]:
        raise RuntimeError("uploaded object verification failed")
    return {"objectKey": key, **{k: metadata[k] for k in ("sizeBytes", "durationMs", "sha256", "revision")}}


def run_child(job, timeout, stop=None):
    command = [sys.executable, "-m", "creator.renderer", str(job)]
    kwargs = {"cwd": str(Path(__file__).resolve().parent.parent), "stdout": subprocess.PIPE, "stderr": subprocess.PIPE}
    if os.name == "nt":
        kwargs["creationflags"] = subprocess.CREATE_NEW_PROCESS_GROUP | subprocess.CREATE_NO_WINDOW
    else:
        kwargs["start_new_session"] = True
    child = subprocess.Popen(command, **kwargs)
    deadline = time.monotonic() + timeout
    cancelled = False
    while True:
        if time.monotonic() >= deadline or (stop is not None and stop.is_set()):
            cancelled = True
            break
        try:
            out, error = child.communicate(timeout=min(.5, deadline - time.monotonic()))
            break
        except subprocess.TimeoutExpired:
            continue
    if cancelled:
        if os.name == "nt":
            subprocess.run(["taskkill", "/PID", str(child.pid), "/T", "/F"], capture_output=True, timeout=10)
        else:
            os.killpg(child.pid, signal.SIGKILL)
        child.kill()
        child.communicate()
        raise RuntimeError("render interrupted or exceeded timeout")
    if child.returncode:
        raise RuntimeError("render failed: " + error.decode(errors="replace")[-1200:])
    return json.loads(out.decode().strip().splitlines()[-1])


class RenderGraph:
    def __init__(self, settings, store, checkpointer):
        self.settings, self.store = settings, store
        self.stop = None
        builder = StateGraph(AgentState)
        builder.add_node("approved_render", self.render_node)
        builder.add_edge(START, "approved_render")
        builder.add_edge("approved_render", END)
        self.graph = builder.compile(checkpointer=checkpointer)

    def render_node(self, state):
        operation = Operation.model_validate(state["operation"])
        if operation.type != "RENDER":
            raise ValueError("render requires an explicit RENDER operation")
        minio_storage()  # Fail configuration promptly, before expensive encoding.
        board = operation.parameters["storyboard"]
        directory = self.settings.data_dir / operation.taskId / str(board["revision"]) / operation.operationId
        directory.mkdir(parents=True, exist_ok=True)
        job = directory / "job.json"
        job.write_text(json.dumps({"storyboard": board, "directory": str(directory)}, ensure_ascii=False), encoding="utf-8")
        started = time.monotonic()
        metadata_file = directory / "metadata.json"
        metadata = None
        if metadata_file.is_file() and (directory / "output.mp4").is_file():
            cached = json.loads(metadata_file.read_text(encoding="utf-8"))
            verified = probe(directory / "output.mp4", sum(s["durationMs"] for s in board["scenes"]))
            if cached["sha256"] == verified["sha256"]:
                metadata = {**cached, **verified}
        if metadata is None:
            metadata = run_child(job, self.settings.render_timeout, self.stop)
        artifact = upload(operation, directory / "output.mp4", metadata)
        self.store.trace(operation, "render", int((time.monotonic() - started) * 1000),
                         {"artifact": artifact, "cacheHits": metadata.get("cacheHits", 0), "sceneCount": len(board["scenes"])})
        return {"candidate": artifact}

    def run(self, operation):
        config = {"configurable": {"thread_id": f"render:{operation.operationId}:{operation.attempt}"}}
        snapshot = self.graph.get_state(config)
        if snapshot.values.get("candidate"):
            return snapshot.values["candidate"]
        return self.graph.invoke(None if snapshot.next else {"operation": operation.model_dump()}, config)["candidate"]
