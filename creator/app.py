from contextlib import asynccontextmanager
import hmac
import json
import logging
import sqlite3
import threading
import httpx

from fastapi import Depends, FastAPI, Header, HTTPException, Request
from langgraph.checkpoint.sqlite import SqliteSaver

from .agent import CreatorAgent
from .execution import RenderGraph
from .models import Operation
from .settings import JavaClient, Settings
from .store import Store

log = logging.getLogger("creator")


class Worker:
    def __init__(self, settings, store, agent, renderer, java):
        self.settings, self.store, self.agent, self.renderer, self.java = settings, store, agent, renderer, java
        self.stop = threading.Event()
        if renderer is not None:
            renderer.stop = self.stop
        self.work_thread = threading.Thread(target=self.work, daemon=True, name="creator-worker")
        self.report_thread = threading.Thread(target=self.report, daemon=True, name="creator-reporter")

    def start(self):
        self.store.recover()
        self.work_thread.start()
        self.report_thread.start()

    def work(self):
        while not self.stop.is_set():
            operation = self.store.claim()
            if not operation:
                self.stop.wait(.5)
                continue
            try:
                stage = {"CREATE": "DRAFTING", "REVISE": "REVISING", "RENDER": "RENDERING", "CONVERSE": "DRAFTING"}[operation.type]
                self.store.emit(operation, stage, "正在执行创作任务")
                if operation.type == "RENDER":
                    artifact = self.renderer.run(operation)
                    self.store.emit(operation, "COMPLETED", "视频已生成", artifact=artifact)
                else:
                    storyboard = self.agent.run(operation)
                    review = self.agent.preview_metadata(operation)
                    fields = {"previewReview": review} if review else {}
                    summary = ("分镜已保存，但仍有视觉问题或检查未完成，请人工核验后确认"
                               if review and review["status"] == "needs_attention"
                               else "分镜已准备好，请确认后生成视频")
                    if operation.type == "CONVERSE":
                        self.store.emit(operation, "DISCUSSED" if storyboard["action"] == "DISCUSS" else "AWAITING_CONFIRMATION",
                                        "创作助手已回复" if storyboard["action"] == "DISCUSS" else summary, **storyboard, **fields)
                    else:
                        self.store.emit(operation, "AWAITING_CONFIRMATION", summary, storyboard=storyboard, **fields)
            except Exception as error:
                if self.stop.is_set():
                    return  # Leave RUNNING durable; checkpoint and render cache resume on restart.
                # Log only safe class/identifiers: provider exception strings can contain request URLs.
                log.error("creator_operation_failed taskId=%s operationId=%s attempt=%s errorType=%s",
                          operation.taskId, operation.operationId, operation.attempt, type(error).__name__)
                safe = str(error)[:1500] if isinstance(error, (ValueError, RuntimeError)) else "外部服务请求失败，请检查创作服务日志和连接配置"
                self.store.emit(operation, "FAILED", "创作任务执行失败", error=safe, retryable=True)

    def flush_events(self):
        blocked = set()
        for event in self.store.pending_events():
            identity = (event["operation_id"], event["attempt"])
            if identity in blocked:
                continue
            try:
                self.java.post(f"/internal/creation/tasks/{event['task_id']}/events", json.loads(event["payload"]))
                self.store.delivered(event)
            except httpx.HTTPStatusError as error:
                if error.response.status_code in (404, 409):
                    self.store.delivered(event, discarded=True)
                else:
                    blocked.add(identity)
            except Exception:
                blocked.add(identity)

    def report(self):
        while not self.stop.is_set():
            self.flush_events()
            self.stop.wait(2)


def create_app(settings=None, *, start_worker=True):
    settings = settings or Settings.from_env()
    settings.validate()
    settings.data_dir.mkdir(parents=True, exist_ok=True)
    store = Store(settings.data_dir / "operations.sqlite")

    @asynccontextmanager
    async def lifespan(app):
        connection = sqlite3.connect(settings.data_dir / "checkpoints.sqlite", check_same_thread=False)
        checkpointer = SqliteSaver(connection)
        java = JavaClient(settings)
        worker = Worker(settings, store, CreatorAgent(settings, store, java, checkpointer), RenderGraph(settings, store, checkpointer), java)
        app.state.worker = worker
        if start_worker and settings.token:
            worker.start()
        try:
            yield
        finally:
            worker.stop.set()
            # Active jobs remain durable and recover at the next start; never close their SQLite underneath them.
            worker.work_thread.join(timeout=3) if worker.work_thread.is_alive() else None
            worker.report_thread.join(timeout=1) if worker.report_thread.is_alive() else None
            if not worker.work_thread.is_alive():
                connection.close()
                java.client.close()

    app = FastAPI(title="VideoTrace creator", lifespan=lifespan, docs_url=None, redoc_url=None, openapi_url=None)
    app.state.store = store

    @app.middleware("http")
    async def bound_request(request: Request, call_next):
        if request.method == "POST":
            from fastapi.responses import JSONResponse
            if len(await request.body()) > 128000:
                return JSONResponse({"detail": "request too large"}, status_code=413)
        return await call_next(request)

    def internal_auth(x_creator_token: str = Header(default="")):
        if not settings.token or not hmac.compare_digest(x_creator_token.encode(), settings.token.encode()):
            raise HTTPException(401, "invalid internal service credentials")

    @app.get("/health")
    def health():
        return {"status": "ok", "configured": bool(settings.token)}

    @app.post("/internal/operations", status_code=202, dependencies=[Depends(internal_auth)])
    def submit(operation: Operation):
        try:
            store.accept(operation)
        except ValueError as error:
            raise HTTPException(409, str(error)) from None
        return store.latest(operation.operationId)

    @app.get("/internal/operations/{operation_id}", dependencies=[Depends(internal_auth)])
    def operation(operation_id: str):
        result = store.latest(operation_id)
        if result is None:
            raise HTTPException(404, "operation not found")
        return result

    return app


app = create_app()
