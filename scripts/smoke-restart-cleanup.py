"""Verify persisted upload receipts and expired-object cleanup after backend restart."""
import json
from pathlib import Path
import runpy
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]
client = runpy.run_path(str(ROOT / "scripts/smoke-upload.py"))
request, ok, query = client["request"], client["ok"], client["query"]
local = dict(line.split("=", 1) for line in (ROOT / ".env.integration").read_text().splitlines())
token = local["SMOKE_TOKEN"]
original = json.loads((ROOT / "docs/integration-upload-result.json").read_text())

def sql(statement):
    result = subprocess.run(["docker", "exec", "-i", "videotrace-mysql-1", "sh", "-c",
                             'MYSQL_PWD="$MYSQL_PASSWORD" exec mysql -u "$MYSQL_USER" -N -B videotrace'],
                            input=statement, text=True, capture_output=True, timeout=15, check=True)
    return result.stdout.strip()

def object_exists(upload_id):
    path = "/data/media/chunk-uploads/" + upload_id + "/part-0/xl.meta"
    return subprocess.run(["docker", "exec", "videotrace-minio-1", "test", "-e", path],
                          timeout=10).returncode == 0

def main():
    expired_id = ok(query("/media/init-upload", filename="expired.mp4", totalChunks=1, totalBytes=3),
                    "POST", token)
    assert client["upload_chunk"](expired_id, 0, 1, b"abc", token)[0] == 200
    assert object_exists(expired_id), "Cannot locate uploaded object before expiry"
    sql("UPDATE upload_sessions SET expires_at=DATE_SUB(CURRENT_TIMESTAMP,INTERVAL 1 SECOND)"
        + " WHERE upload_id='" + expired_id + "';")
    assert request(query("/media/upload-status", uploadId=expired_id), token=token)[0] == 400
    pid = int((ROOT / "docs/backend.pid").read_text())
    command = ("$p=Get-CimInstance Win32_Process -Filter " + chr(34) + "ProcessId=" + str(pid) + chr(34)
               + "; if($p.CommandLine -notlike '*videotrace-backend-0.0.1-SNAPSHOT.jar*')"
               + "{throw 'Unexpected process'}; Stop-Process -Id " + str(pid))
    subprocess.run(["powershell", "-NoProfile", "-Command", command], check=True, timeout=15)
    stdout = (ROOT / "server/integration-restart.log").open("w")
    stderr = (ROOT / "server/integration-restart-error.log").open("w")
    subprocess.Popen(["python", str(ROOT / "scripts/run-backend.py"), "--without-model"],
                     stdout=stdout, stderr=stderr, cwd=ROOT,
                     creationflags=subprocess.CREATE_NO_WINDOW)
    stdout.close()
    stderr.close()
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        try:
            if request("/media/list", token=token)[0] == 200:
                break
        except OSError:
            pass
        time.sleep(2)
    else:
        raise AssertionError("Backend did not restart")
    assert set(ok(query("/media/upload-status", uploadId=original["uploadId"]), token=token)) == {0, 1}
    recovered = ok(query("/media/complete-upload", uploadId=original["uploadId"]), "POST", token)
    assert recovered["id"] == original["mediaId"]
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        row = sql("SELECT state,cleanup_pending FROM upload_sessions WHERE upload_id='" + expired_id + "';")
        if row == "EXPIRED\t0":
            break
        time.sleep(2)
    else:
        raise AssertionError("Expired upload was not cleaned")
    assert not object_exists(expired_id)
    assert sql("SELECT COUNT(*) FROM upload_parts WHERE upload_id='" + expired_id + "';") == "0"
    assert sql("SELECT COUNT(*) FROM media_files WHERE source_upload_id='" + original["uploadId"] + "';") == "1"
    assert sql("SELECT cleanup_pending FROM upload_sessions WHERE upload_id='" + original["uploadId"] + "';") == "0"
    assert sql("SELECT COUNT(*) FROM upload_parts WHERE upload_id='" + original["uploadId"] + "';") == "0"
    source = ok(query("/media/playback", id=original["mediaId"]), token=token)
    import urllib.request
    with urllib.request.urlopen(source, timeout=30) as response:
        assert len(response.read()) == original["bytes"]
    report = {"mediaId": original["mediaId"], "expiredUploadId": expired_id,
              "checks": ["backend process interruption and restart", "Redis login session survives",
                         "persistent completed receipt", "one media row after retry",
                         "expired upload refused", "scheduled physical object cleanup",
                         "part receipts cleared", "completed final object preserved"]}
    (ROOT / "docs/integration-restart-result.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps(report, indent=2))

if __name__ == "__main__":
    main()
