"""Exercise durable acceptance while the local broker is unavailable."""
import json
from pathlib import Path
import runpy
import secrets
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]
client = runpy.run_path(str(ROOT / "scripts/smoke-upload.py"))
request, ok, query = client["request"], client["ok"], client["query"]
local = dict(line.split("=", 1) for line in (ROOT / ".env.integration").read_text().splitlines())
token = local["SMOKE_TOKEN"]
media_id = int(local["SMOKE_MEDIA_ID"])

def sql(statement):
    result = subprocess.run(["docker", "exec", "-i", "videotrace-mysql-1", "sh", "-c",
                             'MYSQL_PWD="$MYSQL_PASSWORD" exec mysql -u "$MYSQL_USER" -N -B videotrace'],
                            input=statement, text=True, capture_output=True, timeout=15, check=True)
    return result.stdout.strip()

def main():
    goal = "Infrastructure recovery check " + secrets.token_hex(5)
    path = query("/analysis/ai", id=media_id, goal=goal, mode="GENERAL")
    task = None
    try:
        subprocess.run(["docker", "stop", "-t", "5", "videotrace-rmqbroker-1"], check=True, timeout=25)
        status, body = request(path, "POST", token)
        assert status == 202 and body["code"] == 0, (status, body)
        task = ok(query("/analysis/tasks/latest", id=media_id, goal=goal, mode="GENERAL"), token=token)
        assert task["state"] == "QUEUED", task
        assert request(path, "POST", token)[0] == 409
        row = sql("SELECT status,attempts FROM analysis_deliveries WHERE task_id='" + task["taskId"] + "';")
        assert row.split("\t")[0] == "PENDING", row
        assert sql("SELECT COUNT(*) FROM analysis_executions WHERE task_id='" + task["taskId"] + "';") == "1"
        print("Broker offline: HTTP 202, durable PENDING delivery, duplicate HTTP 409.", flush=True)
    finally:
        subprocess.run(["docker", "start", "videotrace-rmqbroker-1"], check=True, timeout=25)
    deadline = time.monotonic() + 150
    while time.monotonic() < deadline:
        current = ok("/analysis/tasks/" + task["taskId"], token=token)
        if current["state"] in ("FAILED", "SUCCEEDED"):
            break
        time.sleep(2)
    else:
        raise AssertionError("No terminal state after broker restoration")
    assert current["state"] == "FAILED", current
    assert 1 <= current["attempts"] <= 3, current
    assert sql("SELECT status FROM analysis_deliveries WHERE task_id='" + task["taskId"] + "';") == "OBSERVED"
    other = local["SMOKE_OTHER_TOKEN"]
    assert request("/analysis/tasks/" + task["taskId"], token=other)[0] == 403
    report = {"taskId": task["taskId"], "mediaId": media_id, "state": current["state"],
              "executionAttempts": current["attempts"],
              "lastError": current["lastError"],
              "checks": ["broker offline durable acceptance", "duplicate rejection",
                         "automatic delivery after broker restart", "consumer observation",
                         "bounded failure handling", "task ownership"],
              "scope": "Synthetic upload and unavailable model/tools; FAILED is expected, no AI success claimed."}
    (ROOT / "docs/integration-mq-result.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps(report, indent=2))

if __name__ == "__main__":
    main()
