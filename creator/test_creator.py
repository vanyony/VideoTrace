import copy
import json
from pathlib import Path
import sqlite3
import tempfile
import unittest
from contextlib import closing
from unittest.mock import patch
import httpx
import threading

from fastapi.testclient import TestClient
from langgraph.checkpoint.sqlite import SqliteSaver

from .agent import CreatorAgent
from .app import Worker, create_app
from .execution import run_child
from .models import Operation, Storyboard, patch_storyboard, validate_candidate
from .settings import Settings
from .store import Store


def storyboard():
    return {"schemaVersion": 1, "revision": 1, "title": "三个重要变化", "targetAudience": "普通观众", "musicId": "light_01",
            "scenes": [{"sceneId": f"s_{i}", "template": "points", "durationMs": 10000, "headline": f"观点{i}",
                        "points": ["理解变化", "核对证据"], "animation": "reveal", "sourceRefs": ["e_0_10000"]} for i in range(1, 4)]}


def operation(**updates):
    value = {"taskId": "task-a", "operationId": "op-a", "attempt": 1, "ownerId": 1, "mediaId": 2,
             "sourceVersion": "v1", "type": "CREATE", "parameters": {"requirement": "总结重要变化", "durationSeconds": 30}}
    value.update(updates)
    return Operation.model_validate(value)


def settings(directory):
    return Settings(Path(directory), "x" * 32, "http://127.0.0.1:9090", "http://127.0.0.1:9999/v1", "test", "mock")


def completion(name, args):
    return {"choices": [{"message": {"role": "assistant", "content": None, "tool_calls": [
        {"id": "call_1", "type": "function", "function": {"name": name, "arguments": json.dumps(args, ensure_ascii=False)}}]}}],
            "usage": {"prompt_tokens": 40, "completion_tokens": 10}}


class Java:
    def __init__(self):
        self.calls = []

    def evidence(self, op, action, args):
        self.calls.append((op.mediaId, action, args))
        return [{"evidenceId": "e_0_10000", "startMs": 0, "endMs": 10000, "snippet": "事实内容", "transcript": "事实内容", "ocrTexts": []}]


class CreatorTests(unittest.TestCase):
    def test_validation_rejects_unknown_evidence_and_layout_overflow(self):
        with self.assertRaisesRegex(ValueError, "sourceRefs"):
            validate_candidate(storyboard(), operation(), set())
        board = storyboard()
        board["scenes"][0]["points"] = ["字" * 72] * 4
        with self.assertRaisesRegex(ValueError, "line limits|visible card"):
            validate_candidate(board, operation(), {"e_0_10000"})
        validate_candidate(storyboard(), operation(), {"e_0_10000"})

    def test_patch_preserves_stable_ids_and_scope(self):
        old = storyboard()
        op = operation(type="REVISE", parameters={"storyboard": old, "baseRevision": 1, "instruction": "只改第一点", "targetSceneIds": ["s_1"]})
        changed = patch_storyboard(old, {"baseRevision": 1, "updates": [{"sceneId": "s_1", "headline": "价格变化"}]})
        accepted = validate_candidate(changed, op, set())
        self.assertEqual(old["scenes"][1:], accepted["scenes"][1:])
        self.assertEqual(2, accepted["revision"])
        changed["scenes"][1]["headline"] = "不应修改"
        with self.assertRaisesRegex(ValueError, "non-target"):
            validate_candidate(changed, op, set())
        with self.assertRaisesRegex(ValueError, "stale"):
            patch_storyboard(old, {"baseRevision": 0})
        with self.assertRaisesRegex(ValueError, "unsupported"):
            patch_storyboard(old, {"baseRevision": 1, "__code__": "unsafe"})

    def test_render_requires_explicit_matching_confirmation(self):
        with self.assertRaisesRegex(ValueError, "confirmation"):
            operation(type="RENDER", parameters={"storyboard": storyboard(), "baseRevision": 1})
        op = operation(type="RENDER", parameters={"storyboard": storyboard(), "baseRevision": 1, "confirmedRevision": 1})
        self.assertEqual("RENDER", op.type)

    def test_selected_duration_cannot_be_silently_ignored(self):
        with self.assertRaisesRegex(ValueError, "selected/approved duration 45000"):
            validate_candidate(storyboard(), operation(parameters={"requirement": "总结", "durationSeconds": 45}), {"e_0_10000"})
        old = storyboard()
        op = operation(type="REVISE", parameters={"storyboard": old, "baseRevision": 1, "instruction": "改标题"})
        changed = copy.deepcopy(old)
        changed["revision"] = 2
        changed["scenes"][0]["durationMs"] += 5000
        with self.assertRaisesRegex(ValueError, "approved duration 30000"):
            validate_candidate(changed, op, set())

    def test_persistent_idempotency_recovery_and_monotonic_events(self):
        with tempfile.TemporaryDirectory() as directory:
            store = Store(Path(directory) / "ops.sqlite")
            op = operation()
            self.assertTrue(store.accept(op))
            self.assertFalse(store.accept(op))
            with self.assertRaises(ValueError):
                store.accept(operation(parameters={"requirement": "不同需求"}))
            self.assertEqual(op, store.claim())
            reopened = Store(store.path)
            reopened.recover()
            self.assertEqual(op, reopened.claim())
            reopened.emit(op, "DRAFTING", "检索中")
            reopened.emit(op, "AWAITING_CONFIRMATION", "待确认", storyboard=storyboard())
            self.assertEqual(2, reopened.latest(op.operationId)["event"]["sequence"])
            self.assertEqual(2, len(reopened.pending_events()))
            self.assertEqual("DONE", reopened.latest(op.operationId)["status"])
            with self.assertRaisesRegex(ValueError, "bound operation"):
                reopened.accept(operation(attempt=2, mediaId=99))
            self.assertTrue(reopened.accept(operation(attempt=2)))

    def test_internal_auth_empty_token_and_payload_conflict(self):
        with tempfile.TemporaryDirectory() as directory:
            cfg = settings(directory)
            app = create_app(cfg, start_worker=False)
            with TestClient(app) as client:
                self.assertEqual(401, client.post("/internal/operations", json=operation().model_dump()).status_code)
                headers = {"X-Creator-Token": cfg.token}
                self.assertEqual(202, client.post("/internal/operations", json=operation().model_dump(), headers=headers).status_code)
                self.assertEqual(202, client.post("/internal/operations", json=operation().model_dump(), headers=headers).status_code)
                altered = operation().model_dump()
                altered["mediaId"] = 44
                self.assertEqual(409, client.post("/internal/operations", json=altered, headers=headers).status_code)
                self.assertEqual(200, client.get("/internal/operations/op-a", headers=headers).status_code)
            empty = Settings(Path(directory) / "empty", "", cfg.java_url, cfg.llm_url, "", "mock")
            with TestClient(create_app(empty, start_worker=False)) as client:
                self.assertEqual(401, client.post("/internal/operations", json=operation().model_dump(), headers={"X-Creator-Token": ""}).status_code)

    def test_real_graph_dynamic_tools_repair_and_checkpoint_reuse(self):
        with tempfile.TemporaryDirectory() as directory, closing(sqlite3.connect(":memory:", check_same_thread=False)) as connection:
            store, java = Store(Path(directory) / "ops.sqlite"), Java()
            invalid = storyboard()
            invalid["scenes"][0]["template"] = "arbitrary-python"
            responses = iter([completion("search_video", {"query": "价格变化", "limit": 3}),
                              completion("read_context", {"evidenceId": "e_0_10000", "windowMs": 5000}),
                              completion("save_storyboard", invalid), completion("save_storyboard", storyboard())])
            calls = []
            def model(messages, tools, timeout):
                calls.append(messages)
                return next(responses)
            agent = CreatorAgent(settings(directory), store, java, SqliteSaver(connection), model)
            self.assertEqual(storyboard(), agent.run(operation()))
            self.assertEqual(4, len(calls))
            self.assertEqual(["evidence-search", "context"], [c[1] for c in java.calls])
            self.assertIn("arbitrary-python", calls[-1][-1]["content"])
            # Re-running the same operation returns the checkpoint; no additional provider/tool calls.
            self.assertEqual(storyboard(), agent.run(operation()))
            self.assertEqual(4, len(calls))

    def test_graph_recovery_continues_after_provider_failure(self):
        with tempfile.TemporaryDirectory() as directory, closing(sqlite3.connect(Path(directory) / "graph.sqlite", check_same_thread=False)) as connection:
            store, java = Store(Path(directory) / "ops.sqlite"), Java()
            responses = iter([completion("search_video", {"query": "重点"}), RuntimeError("simulated interruption")])
            def interrupted(*args):
                value = next(responses)
                if isinstance(value, Exception):
                    raise value
                return value
            agent = CreatorAgent(settings(directory), store, java, SqliteSaver(connection), interrupted)
            with self.assertRaises(RuntimeError):
                agent.run(operation())
            recovered = CreatorAgent(settings(directory), store, java, SqliteSaver(connection), lambda *args: completion("save_storyboard", storyboard()))
            self.assertEqual(storyboard(), recovered.run(operation()))
            self.assertEqual(1, len(java.calls))

    def test_agent_budget_prevents_unbounded_retrieval(self):
        with tempfile.TemporaryDirectory() as directory, closing(sqlite3.connect(":memory:", check_same_thread=False)) as connection:
            store = Store(Path(directory) / "ops.sqlite")
            agent = CreatorAgent(settings(directory), store, Java(), SqliteSaver(connection), lambda *args: completion("search_video", {"query": "重点"}))
            with self.assertRaisesRegex(RuntimeError, "budget"):
                agent.run(operation())
            with store.connect() as db:
                self.assertEqual(8, db.execute("SELECT COUNT(*) FROM tool_traces WHERE name='model'").fetchone()[0])

    def test_failed_callback_remains_durable_and_ordered(self):
        with tempfile.TemporaryDirectory() as directory:
            store = Store(Path(directory) / "ops.sqlite")
            op = operation()
            store.accept(op)
            store.emit(op, "DRAFTING", "检索")
            store.emit(op, "AWAITING_CONFIRMATION", "完成", storyboard=storyboard())
            class Callback:
                fail = True
                calls = []
                def post(self, path, body):
                    self.calls.append(body["sequence"])
                    if self.fail:
                        raise OSError("offline")
            callback = Callback()
            worker = Worker(settings(directory), store, None, None, callback)
            worker.flush_events()
            self.assertEqual([1], callback.calls)
            self.assertEqual(2, len(store.pending_events()))
            callback.fail = False
            worker.flush_events()
            self.assertEqual([1, 1, 2], callback.calls)
            self.assertEqual([], store.pending_events())

    def test_obsolete_callbacks_cannot_starve_new_tasks(self):
        with tempfile.TemporaryDirectory() as directory:
            store = Store(Path(directory) / "ops.sqlite")
            old = operation(operationId="aaa-obsolete")
            new = operation(operationId="zzz-current")
            store.accept(old)
            store.accept(new)
            for i in range(110):
                store.emit(old, "DRAFTING", "过期事件")
            store.emit(new, "AWAITING_CONFIRMATION", "新分镜", storyboard=storyboard())
            class Callback:
                received = []
                def post(self, path, body):
                    if body["operationId"] == old.operationId:
                        response = httpx.Response(409, request=httpx.Request("POST", "http://localhost/events"))
                        raise httpx.HTTPStatusError("stale", request=response.request, response=response)
                    self.received.append(body)
            callback = Callback()
            worker = Worker(settings(directory), store, None, None, callback)
            worker.flush_events()
            worker.flush_events()
            self.assertEqual(1, len(callback.received))
            self.assertEqual(new.operationId, callback.received[0]["operationId"])
            self.assertEqual([], store.pending_events())

    def test_renderer_cancels_process_tree_and_does_not_publish_partial_output(self):
        stop = threading.Event()
        stop.set()
        class Child:
            pid = 1234
            returncode = 0
            killed = False
            def communicate(self, **kwargs):
                return b"", b""
            def kill(self):
                self.killed = True
        child = Child()
        with patch("creator.execution.subprocess.Popen", return_value=child), patch("creator.execution.subprocess.run") as terminate:
            with self.assertRaisesRegex(RuntimeError, "interrupted"):
                run_child(Path("job.json"), 10, stop)
            self.assertTrue(child.killed)
            if __import__("os").name == "nt":
                self.assertIn("/T", terminate.call_args.args[0])


if __name__ == "__main__":
    unittest.main()
