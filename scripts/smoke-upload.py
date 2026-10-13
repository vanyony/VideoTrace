"""Real HTTP smoke test. Uses synthetic bytes; does not claim video/model correctness."""
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
import os
from pathlib import Path
import secrets
import urllib.error
import urllib.parse
import urllib.request

BASE = os.environ.get("VIDEOTRACE_SMOKE_URL", "http://127.0.0.1:9090")
ROOT = Path(__file__).resolve().parents[1]

def request(path, method="GET", token=None, data=None, content_type=None):
    headers = {}
    if token:
        headers["Authorization"] = "Bearer " + token
    if content_type:
        headers["Content-Type"] = content_type
    req = urllib.request.Request(BASE + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=45) as response:
            return response.status, json.load(response)
    except urllib.error.HTTPError as error:
        return error.code, json.load(error)

def ok(path, method="GET", token=None, data=None, content_type=None):
    status, result = request(path, method, token, data, content_type)
    assert 200 <= status < 300 and result["code"] == 0, (path, status, result)
    return result["data"]

def query(path, **params):
    return path + "?" + urllib.parse.urlencode(params)

def account():
    payload = json.dumps({"username": "smoke_" + secrets.token_hex(6),
                          "password": secrets.token_urlsafe(20), "nickname": "Integration check"}).encode()
    ok("/user/register", "POST", data=payload, content_type="application/json")
    return ok("/user/login", "POST", data=payload, content_type="application/json")["token"]

def upload_chunk(upload_id, index, total, payload, token):
    boundary = "videotrace_" + secrets.token_hex(12)
    fields = {"uploadId": upload_id, "chunkIndex": str(index), "totalChunks": str(total)}
    body = bytearray()
    for name, value in fields.items():
        body.extend(("--" + boundary + "\r\nContent-Disposition: form-data; name="
                     + chr(34) + name + chr(34) + "\r\n\r\n" + value + "\r\n").encode())
    body.extend(("--" + boundary + "\r\nContent-Disposition: form-data; name="
                 + chr(34) + "file" + chr(34) + "; filename=" + chr(34) + "part.bin" + chr(34)
                 + "\r\nContent-Type: application/octet-stream\r\n\r\n").encode())
    body.extend(payload)
    body.extend(("\r\n--" + boundary + "--\r\n").encode())
    return request("/media/upload-chunk", "POST", token, bytes(body),
                   "multipart/form-data; boundary=" + boundary)

def main():
    assert request("/media/list")[0] == 401
    owner, other = account(), account()
    chunks = [bytes(range(256)) * (5 * 1024 * 1024 // 256), b"videotrace-last-part"]
    payload = b"".join(chunks)
    upload_id = ok(query("/media/init-upload", filename="integration.mp4", totalChunks=2,
                         totalBytes=len(payload)), "POST", owner)
    with ThreadPoolExecutor(max_workers=2) as pool:
        results = list(pool.map(lambda i: upload_chunk(upload_id, i, 2, chunks[i], owner), range(2)))
    assert all(status == 200 and result["code"] == 0 for status, result in results), results
    assert set(ok(query("/media/upload-status", uploadId=upload_id), token=owner)) == {0, 1}
    assert request(query("/media/upload-status", uploadId=upload_id), token=other)[0] == 403
    assert upload_chunk(upload_id, 1, 2, chunks[1], owner)[0] == 200
    assert upload_chunk(upload_id, 1, 2, b"x" * len(chunks[1]), owner)[0] == 409
    media = ok(query("/media/complete-upload", uploadId=upload_id), "POST", owner)
    repeated = ok(query("/media/complete-upload", uploadId=upload_id), "POST", owner)
    assert media["id"] == repeated["id"]
    assert upload_chunk(upload_id, 0, 2, chunks[0], owner)[0] == 200
    assert request(query("/media/playback", id=media["id"]), token=other)[0] == 403
    source = ok(query("/media/playback", id=media["id"]), token=owner)
    with urllib.request.urlopen(source, timeout=30) as response:
        assert response.read() == payload
    anonymous = source.split("?")[0]
    try:
        urllib.request.urlopen(anonymous, timeout=10)
        raise AssertionError("private bucket is publicly readable")
    except urllib.error.HTTPError as error:
        assert error.code == 403
    records = ok("/media/list", token=owner)
    assert len([record for record in records if record["id"] == media["id"]]) == 1
    report = {"mediaId": media["id"], "uploadId": upload_id, "bytes": len(payload),
              "md5": hashlib.md5(payload).hexdigest(),
              "checks": ["authentication", "parallel chunks", "resume status", "ownership",
                         "duplicate part", "conflicting duplicate", "idempotent completion",
                         "late part", "signed playback byte equality", "private bucket", "single media"]}
    (ROOT / "docs/integration-upload-result.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps(report, indent=2))
    # Keep tokens only in an ignored local file for subsequent outage exercises.
    (ROOT / ".env.integration").write_text("SMOKE_TOKEN=" + owner + "\nSMOKE_OTHER_TOKEN=" + other
                                         + "\nSMOKE_MEDIA_ID=" + str(media["id"]) + "\n", encoding="utf-8")

if __name__ == "__main__":
    main()
