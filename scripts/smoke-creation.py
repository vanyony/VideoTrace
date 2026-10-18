"""Local integration: real Java/Python/SQL/MinIO, deterministic model stub.

Run with creator/.venv/Scripts/python.exe scripts/smoke-creation.py after Maven
package and docker compose startup. No paid model requests are made. Dedicated
test rows are removed; only this script's child services are stopped.
"""
import json
import os
from pathlib import Path
import secrets
import subprocess
import threading
import time
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = Path(__file__).resolve().parents[1]
JAVA = "http://127.0.0.1:19090"
PYTHON = "http://127.0.0.1:18091"
MODEL_PORT = 19095
REPORT = ROOT / "docs" / "creation-smoke-result.json"
CALLS = []


def settings():
    env = os.environ.copy()
    for raw in (ROOT / ".env").read_text(encoding="utf-8-sig").splitlines():
        line = raw.strip()
        if line and not line.startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            env[key.strip()] = value.strip().strip("\"'")
    env.update(SERVER_PORT="19090", CREATOR_URL=PYTHON, CREATOR_JAVA_URL=JAVA,
               CREATOR_INTERNAL_TOKEN=secrets.token_hex(24), CREATOR_ENABLED="true",
               SILICONFLOW_API_KEY="local-smoke-only", SILICONFLOW_BASE_URL=f"http://127.0.0.1:{MODEL_PORT}/v1",
               LLM_API_KEY="local-smoke-only", LLM_BASE_URL=f"http://127.0.0.1:{MODEL_PORT}/v1",
               CREATOR_LLM_API_KEY="local-smoke-only", CREATOR_LLM_BASE_URL=f"http://127.0.0.1:{MODEL_PORT}/v1",
               CREATOR_DATA_DIR=str(ROOT / "creator" / "data" / ("smoke-" + secrets.token_hex(4))),
               CREATOR_DISPATCH_DELAY_MS="1000", VIDEO_PARSE_VERSION="creator_smoke_v1",
               CREATOR_RENDER_TIMEOUT_SECONDS="600", LLM_TIMEOUT_SECONDS="10",
               ROCKETMQ_ANALYSIS_GROUP="creator-smoke-consumer", ROCKETMQ_PRODUCER_GROUP="creator-smoke-producer")
    # Local integration only: reuse the developer's existing test storage account.
    # Deployment must use a separately scoped creator storage account.
    env["CREATOR_MINIO_ENDPOINT"] = env.get("MINIO_ENDPOINT", "http://127.0.0.1:9000")
    env["CREATOR_MINIO_BUCKET"] = env.get("MINIO_BUCKET", "media")
    env["CREATOR_MINIO_ACCESS_KEY"] = env["MINIO_ACCESS_KEY"]
    env["CREATOR_MINIO_SECRET_KEY"] = env["MINIO_SECRET_KEY"]
    return env


def request(path, body=None, token=None, base=JAVA, internal_token=None):
    headers = {}
    if internal_token:
        headers["X-Creator-Token"] = internal_token
    if token:
        headers["Authorization"] = "Bearer " + token
    if body is not None:
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(base + path, data=None if body is None else json.dumps(body).encode(), headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=20) as response:
            return response.status, json.load(response)
    except urllib.error.HTTPError as error:
        return error.code, json.load(error)


def ok(path, body=None, token=None):
    status, result = request(path, body, token)
    assert status in (200, 202) and result.get("code") == 0, (path, status, result)
    return result.get("data")


def sql(statement):
    command = ["docker", "exec", "-i", "videotrace-mysql-1", "sh", "-c",
               'MYSQL_PWD="$MYSQL_PASSWORD" exec mysql -u "$MYSQL_USER" --default-character-set=utf8mb4 -N -B videotrace']
    result = subprocess.run(command, input=statement, encoding="utf-8", capture_output=True, timeout=20)
    if result.returncode:
        raise RuntimeError("fixture SQL failed: " + result.stderr[-400:])
    return result.stdout.strip()


def literal(value):
    return "(CONVERT(0x" + str(value).encode("utf-8").hex() + " USING utf8mb4) COLLATE utf8mb4_unicode_ci)"


def storyboard():
    return {"schemaVersion": 1, "revision": 1, "title": "发布会的三个变化", "targetAudience": "普通消费者", "musicId": "light_01",
            "scenes": [{"sceneId": f"s_{i}", "template": "points", "durationMs": 10000,
                        "headline": title, "points": [point], "animation": "reveal", "sourceRefs": ["e_0_10000"]}
                       for i, (title, point) in enumerate([("价格更低", "入门款售价降低"), ("续航改善", "日常使用更方便"), ("使用更简单", "减少操作步骤")], 1)]}


class ModelStub(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        if self.path.endswith("/embeddings"):
            result = {"data": [{"index": 0, "embedding": [1.0, 0.0, 0.0]}], "usage": {"prompt_tokens": 1, "total_tokens": 1}}
        elif body.get("tools"):
            messages = body["messages"]
            names = {tool["function"]["name"] for tool in body["tools"]}
            observed = [m for m in messages if m["role"] == "tool" and '"action"' not in m["content"]]
            if "choose_action" in names:
                params = json.loads(next(m["content"] for m in messages if m["role"] == "user"))
                instruction = params["instruction"]
                action = "CREATE" if instruction == "按三个变化直接生成分镜" else "REVISE" if instruction == "第二点改成价格变化，其他不变" else "DISCUSS"
                name, args = "choose_action", {"action": action}
            elif not observed:
                name, args = "search_video", {"query": "价格 续航 使用", "limit": 3}
            elif len(observed) == 1:
                name, args = "read_context", {"evidenceId": "e_0_10000", "windowMs": 10000}
            elif "finish_discussion" in names:
                name, args = "finish_discussion", {"reply": "视频介绍了价格、续航与使用方式的变化，可以选择其中一个角度。当前只讨论，不修改分镜。"}
            elif "patch_scenes" in names:
                params = json.loads(next(m["content"] for m in messages if m["role"] == "user"))
                name, args = "patch_scenes", {"baseRevision": params["baseRevision"], "updates": [{"sceneId": "s_2", "headline": "价格变化值得关注"}]}
            else:
                name, args = "save_storyboard", storyboard()
            CALLS.append(name)
            result = {"id": "local-smoke", "object": "chat.completion", "created": 1, "model": "stub",
                      "choices": [{"index": 0, "finish_reason": "tool_calls", "message": {"role": "assistant", "content": None,
                      "tool_calls": [{"id": "call_" + secrets.token_hex(4), "type": "function", "function": {"name": name, "arguments": json.dumps(args, ensure_ascii=False)}}]}}],
                      "usage": {"prompt_tokens": 10, "completion_tokens": 10, "total_tokens": 20}}
        else:
            result = {"id": "local-smoke", "object": "chat.completion", "created": 1, "model": "stub",
                      "choices": [{"index": 0, "finish_reason": "stop", "message": {"role": "assistant", "content": json.dumps({"semanticQuery": "价格 续航", "keywords": ["价格", "续航"], "visualKeywords": []})}}],
                      "usage": {"prompt_tokens": 10, "completion_tokens": 10, "total_tokens": 20}}
        data = json.dumps(result, ensure_ascii=False).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


def await_service(base, path, seconds=90):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        try:
            if request(path, base=base)[0] < 500:
                return
        except (OSError, ValueError):
            pass
        time.sleep(1)
    raise AssertionError("service did not start: " + base)


def await_task(task_id, token, target, timeout=600):
    deadline = time.monotonic() + timeout
    last = None
    while time.monotonic() < deadline:
        task = ok("/creation/tasks/" + task_id, token=token)
        if task["status"] != last:
            print("task:", task["status"], flush=True)
            last = task["status"]
        if task["status"] == target:
            return task
        if task["status"] == "FAILED":
            raise AssertionError(task.get("error"))
        time.sleep(1)
    raise AssertionError("task timeout")


def main():
    env = settings()
    processes, logs, user_ids, media_ids = [], [], [], []
    server = ThreadingHTTPServer(("127.0.0.1", MODEL_PORT), ModelStub)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    run = secrets.token_hex(5)
    report = {"model": "deterministic local stub; no live LLM quality claim", "checks": []}
    try:
        for name, command in [
            ("java", ["java", "-Xmx768m", "-jar", str(ROOT / "server/target/videotrace-backend-0.0.1-SNAPSHOT.jar")]),
            ("python", [str(ROOT / "creator/.venv/Scripts/python.exe"), "-m", "uvicorn", "creator.app:app", "--host", "127.0.0.1", "--port", "18091"]),
        ]:
            log = (ROOT / f"verification-creation-{name}.log").open("w", encoding="utf-8")
            logs.append(log)
            processes.append(subprocess.Popen(command, cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT,
                                               creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0)))
        await_service(JAVA, "/health")
        await_service(PYTHON, "/health")
        tokens = []
        for index in range(2):
            username = f"cr_{run}_{index}"
            password = secrets.token_hex(12)
            ok("/user/register", {"username": username, "password": password})
            auth = ok("/user/login", {"username": username, "password": password})
            tokens.append(auth["token"])
            user_ids.append(int(auth["userInfo"]["id"]))
        token = tokens[0]
        segments = [{"startMs": 0, "endMs": 10000, "transcript": "发布会介绍：入门款价格降低，续航改善，操作步骤减少。", "ocrTexts": [], "evidenceFrames": []}]
        context = {"source": "creator-smoke.mp4", "userGoal": "", "segments": segments}
        chunk = {"startTime": 0, "endTime": 10000, "segmentSummary": "价格续航使用", "keywords": ["价格", "续航"], "rawSegments": segments, "embedding": [1.0, 0.0, 0.0]}
        for index in range(2):
            media_ids.append(int(sql(f"INSERT INTO media_files(user_id,filename,status,file_path,transcript_text) VALUES({user_ids[0]},'creator-smoke-{run}-{index}.mp4','COMPLETED','creator-smoke.mp4','text only'); SELECT LAST_INSERT_ID();")))
        media_id = media_ids[0]
        for field, data in [("context", context), ("chunks", [chunk])]:
            sql(f"INSERT INTO agent_checkpoints(media_id,checkpoint_key,stage,payload) VALUES({media_id},'parse:creator_smoke_v1:media:{field}','CONTEXT_COMPLETED',{literal(json.dumps(data, ensure_ascii=False))})")
        assert ok(f"/creation/eligibility?mediaId={media_id}", token=token)["eligible"]
        assert not ok(f"/creation/eligibility?mediaId={media_ids[1]}", token=token)["eligible"]
        report["checks"].append("timestamp context readiness; transcript-only denied")
        # Conversation routes use real services/storage and a deterministic tool model.
        conversation = {"mediaId": media_id, "requirement": "先别生成，这个视频有什么值得做的？",
                        "durationSeconds": 30, "requestId": "conversation_" + run}
        chat = ok("/creation/conversations", conversation, token)
        cid = chat["taskId"]
        assert ok("/creation/conversations", conversation, token)["taskId"] == cid
        assert request("/creation/conversations", {**conversation, "requirement": "different"}, token)[0] == 409
        discussed = await_task(cid, token, "DISCUSSED")
        assert discussed["revision"] == 0 and not discussed.get("storyboard")
        message = {"instruction": "按三个变化直接生成分镜", "baseRevision": 0, "requestId": "chat_create_" + run}
        assert request(f"/creation/tasks/{cid}/messages", message, tokens[1])[0] == 403
        ok(f"/creation/tasks/{cid}/messages", message, token)
        draft_chat = await_task(cid, token, "AWAITING_CONFIRMATION")
        assert draft_chat["revision"] == 1
        assert ok(f"/creation/tasks/{cid}/messages", message, token)["revision"] == 1
        explain = {"instruction": "为什么第二段这么写？先不要修改或生成视频", "baseRevision": 1, "requestId": "chat_explain_" + run}
        ok(f"/creation/tasks/{cid}/messages", explain, token)
        explained = await_task(cid, token, "DISCUSSED")
        assert explained["revision"] == 1 and explained["storyboard"] == draft_chat["storyboard"]
        assert int(sql(f"SELECT COUNT(*) FROM creation_artifacts WHERE task_id={literal(cid)}")) == 0
        assert request(f"/creation/tasks/{cid}/messages", {**explain, "requestId": "chat_stale_" + run, "baseRevision": 0}, token)[0] == 409
        ok(f"/creation/tasks/{cid}/messages", {"instruction": "第二点改成价格变化，其他不变", "baseRevision": 1,
            "targetSceneIds": ["s_2"], "requestId": "chat_revise_" + run}, token)
        chat_revised = await_task(cid, token, "AWAITING_CONFIRMATION")
        assert chat_revised["revision"] == 2
        assert chat_revised["storyboard"]["scenes"][0] == draft_chat["storyboard"]["scenes"][0]
        assert chat_revised["storyboard"]["scenes"][2] == draft_chat["storyboard"]["scenes"][2]
        direct = ok("/creation/conversations", {**conversation, "requirement": "按三个变化直接生成分镜", "requestId": "chat_direct_" + run}, token)
        assert await_task(direct["taskId"], token, "AWAITING_CONFIRMATION")["revision"] == 1
        report["checks"].append("conversation discussion -> creation; direct creation; explanation preserves draft; scoped revision; message idempotency/ownership/stale version; no automatic render")
        body = {"mediaId": media_id, "requirement": "发布会的三个变化", "durationSeconds": 30, "requestId": "smoke_" + run}
        task = ok("/creation/tasks", body, token)
        tid = task["taskId"]
        assert ok("/creation/tasks", body, token)["taskId"] == tid
        assert request("/creation/tasks", {**body, "requirement": "different"}, token)[0] == 409
        assert request("/creation/tasks/" + tid, token=tokens[1])[0] == 403
        draft = await_task(tid, token, "AWAITING_CONFIRMATION")
        assert draft["revision"] == 1
        internal = env["CREATOR_INTERNAL_TOKEN"]
        operation_path = "/internal/operations/" + draft["currentOperationId"]
        assert request(operation_path, base=PYTHON)[0] == 401
        latest = request(operation_path, base=PYTHON, internal_token=internal)[1]
        assert request(f"/internal/creation/tasks/{tid}/events", latest["event"], internal_token="wrong")[0] == 401
        assert request(f"/internal/creation/tasks/{tid}/events", latest["event"], internal_token=internal)[0] == 200
        # Restart only this script's Python service; retain its SQLite/checkpoints.
        python_process = processes[-1]
        python_process.terminate()
        python_process.wait(timeout=15)
        processes[-1] = subprocess.Popen([str(ROOT / "creator/.venv/Scripts/python.exe"), "-m", "uvicorn", "creator.app:app",
                                         "--host", "127.0.0.1", "--port", "18091"], cwd=ROOT, env=env,
                                        stdout=logs[-1], stderr=subprocess.STDOUT,
                                        creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        await_service(PYTHON, "/health")
        restored = request(operation_path, base=PYTHON, internal_token=internal)[1]
        assert restored["event"]["storyboard"] == draft["storyboard"]
        report["checks"].append("internal authentication; duplicate callback; Python restart preserves draft")
        assert request(f"/creation/tasks/{tid}/render", {"baseRevision": 0, "requestId": "stale_" + run}, token)[0] == 409
        report["checks"].append("idempotent create; changed duplicate rejected; ownership; stale revision")
        ok(f"/creation/tasks/{tid}/messages", {"instruction": "先解释选题，不要生成视频", "baseRevision": 1,
            "requestId": "before_render_" + run}, token)
        before_render = await_task(tid, token, "DISCUSSED")
        assert before_render["storyboard"] == draft["storyboard"]
        ok(f"/creation/tasks/{tid}/render", {"baseRevision": 1, "requestId": "render_" + run}, token)
        await_task(tid, token, "COMPLETED")
        artifact = ok(f"/creation/tasks/{tid}/artifact?revision=1", token=token)
        with urllib.request.urlopen(artifact["url"], timeout=30) as response:
            video = response.read()
        assert len(video) == artifact["sizeBytes"] and b"ftyp" in video[:32]
        report["checks"].append("actual 720p render; MinIO upload; authorized signed download")
        # Simulate a completed task created before artifactRevision was introduced.
        sql(f"UPDATE creation_tasks SET snapshot=JSON_REMOVE(snapshot,'$.artifactRevision') WHERE task_id={literal(tid)}")
        ok(f"/creation/tasks/{tid}/messages", {"instruction": "解释第二个观点，不要修改", "baseRevision": 1,
            "requestId": "after_render_" + run}, token)
        after_render = await_task(tid, token, "DISCUSSED")
        assert after_render["artifactRevision"] == 1 and after_render["storyboard"] == draft["storyboard"]
        assert ok(f"/creation/tasks/{tid}/artifact?revision=1", token=token)["url"]
        report["checks"].append("render after discussion; legacy completed artifact remains accessible after conversation")
        ok(f"/creation/tasks/{tid}/revisions", {"baseRevision": 1, "requestId": "revise_" + run,
            "instruction": "第二点改成价格变化，其他不变", "targetSceneIds": ["s_2"]}, token)
        revised = await_task(tid, token, "AWAITING_CONFIRMATION")
        assert revised["revision"] == 2
        assert request(f"/internal/creation/tasks/{tid}/events", latest["event"], internal_token=internal)[0] == 409
        assert ok(f"/creation/tasks/{tid}", token=token)["revision"] == 2
        assert revised["storyboard"]["scenes"][0] == draft["storyboard"]["scenes"][0]
        assert revised["storyboard"]["scenes"][2] == draft["storyboard"]["scenes"][2]
        ok(f"/creation/tasks/{tid}/render", {"baseRevision": 2, "requestId": "render2_" + run}, token)
        await_task(tid, token, "COMPLETED")
        metadata = list(Path(env["CREATOR_DATA_DIR"]).rglob("metadata.json"))
        assert any(json.loads(path.read_text())["cacheHits"] >= 2 for path in metadata)
        report["checks"].append("targeted revision preserves other scenes; at least two scene cache hits")
        sql(f"DELETE FROM media_files WHERE id={media_id} AND user_id={user_ids[0]}")
        assert ok(f"/creation/tasks/{tid}", token=token)["sourceDeleted"]
        assert ok(f"/creation/tasks/{tid}/artifact?revision=1", token=token)["url"]
        assert request(f"/creation/tasks/{tid}/revisions", {"baseRevision": 2, "requestId": "deleted_" + run, "instruction": "修改"}, token)[0] not in (200, 202)
        report["checks"].append("deleted source blocks edits; prior completed artifact remains owned-accessible")
        report.update(passed=True, toolCalls=CALLS, artifactBytes=len(video))
        REPORT.parent.mkdir(exist_ok=True)
        REPORT.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        print(json.dumps(report, ensure_ascii=False, indent=2), flush=True)
    finally:
        for process in reversed(processes):
            process.terminate()
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                process.kill()
        server.shutdown()
        for log in logs:
            log.close()
        # Delete only fixture database rows created by this run. Keep local rendered
        # files and MinIO test artifacts for inspection; their keys contain task UUIDs.
        if user_ids:
            ids = ",".join(str(i) for i in user_ids)
            sql(f"DELETE a FROM creation_artifacts a JOIN creation_tasks t ON a.task_id=t.task_id WHERE t.owner_id IN ({ids}); DELETE FROM creation_operations WHERE owner_id IN ({ids}); DELETE FROM creation_tasks WHERE owner_id IN ({ids});")
            if media_ids:
                mids = ",".join(str(i) for i in media_ids)
                sql(f"DELETE FROM agent_checkpoints WHERE media_id IN ({mids}); DELETE FROM media_files WHERE id IN ({mids}) AND user_id IN ({ids});")
            sql(f"DELETE FROM users WHERE id IN ({ids});")


if __name__ == "__main__":
    main()
