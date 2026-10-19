"""Local real HTTP upload check; no ASR/LLM calls. Retains test files/media for inspection."""
from concurrent.futures import ThreadPoolExecutor, as_completed
import argparse
import hashlib
import json
import math
from pathlib import Path
import secrets
import struct
import subprocess
import time

import httpx
import imageio_ffmpeg
from moviepy import VideoFileClip

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / "creator/data/upload-capacity"
CHUNK = 5 * 1024 * 1024
LIMIT = CHUNK * 410
BASE = "http://127.0.0.1:9090"


def digest(path):
    h = hashlib.sha256()
    with path.open("rb") as f:
        for block in iter(lambda: f.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--hours", type=int, choices=[3, 4], help="Test only a looped long video; does not call AI APIs")
    args = parser.parse_args()
    DATA.mkdir(parents=True, exist_ok=True)
    source = DATA / "sample.mp4"
    seconds = args.hours * 3600 if args.hours else 3600
    long = DATA / (f"loop-{args.hours}h.mp4" if args.hours else "loop-60min.mp4")
    if not long.exists():
        subprocess.run([imageio_ffmpeg.get_ffmpeg_exe(), "-y", "-stream_loop", "-1", "-i", str(source),
                        "-t", str(seconds), "-c", "copy", "-movflags", "+faststart", str(long)],
                       check=True, capture_output=True, timeout=300)
    samples = [(long, f"{args.hours} hour looped public video")] if args.hours else [
        (source, "original"), (long, "looped real video")]
    boundary = DATA / "boundary-2050MiB.mp4"
    if not args.hours and (not boundary.exists() or boundary.stat().st_size != LIMIT):
        import shutil
        shutil.copyfile(long, boundary)
        padding = LIMIT - boundary.stat().st_size
        assert padding >= 8
        with boundary.open("r+b") as f:
            f.seek(0, 2)
            # Valid top-level MP4 free box; the padding is not extra video content.
            f.write(struct.pack(">I4s", padding, b"free"))
            f.seek(padding - 9, 1)
            f.write(b"\0")
        assert boundary.stat().st_size == LIMIT
    if not args.hours:
        samples.append((boundary, "looped real video with free-box padding"))
    report_file = DATA / (f"result-{args.hours}h.json" if args.hours else "result.json")
    report = {"source": "https://download.blender.org/peach/bigbuckbunny_movies/BigBuckBunny_320x180.mp4.zip",
              "scope": (f"Local HTTP upload/storage only; {args.hours} hour looped public video, without padding. No ASR/OCR quality claim." if args.hours else
                        "Local HTTP upload/storage only; real source, looped long sample, padded MP4 boundary. No ASR/OCR quality claim."),
              "maxBytes": LIMIT, "results": []}
    with httpx.Client(timeout=180, trust_env=False) as client:
        def request(method, path, **kwargs):
            r = client.request(method, BASE + path, **kwargs)
            r.raise_for_status()
            body = r.json()
            assert body["code"] == 0, (path, body)
            return body["data"]
        username, password = "capacity_" + secrets.token_hex(5), secrets.token_urlsafe(24)
        request("POST", "/user/register", json={"username": username, "password": password})
        auth = request("POST", "/user/login", json={"username": username, "password": password})
        (ROOT / ".env.capacity").write_text("CAPACITY_USERNAME=" + username + "\nCAPACITY_PASSWORD=" + password + "\n", encoding="utf-8")
        client.headers["Authorization"] = "Bearer " + auth["token"]
        for size, chunks in [(LIMIT + 1, 411), (LIMIT + 1, 410)]:
            r = client.post(BASE + "/media/init-upload", params={"filename": "too-large.mp4", "totalBytes": size, "totalChunks": chunks})
            assert r.status_code == 400, r.status_code
        report["overLimitRejected"] = True
        for path, kind in samples:
            size = path.stat().st_size
            assert size <= LIMIT, "Sample exceeds the application's file size limit"
            with VideoFileClip(str(path), audio=False) as clip:
                duration, resolution = clip.duration, clip.size
                clip.get_frame(min(5, duration / 2))
                clip.get_frame(duration - 1)
            total = math.ceil(size / CHUNK)
            started = time.monotonic()
            upload = request("POST", "/media/init-upload", params={"filename": path.name, "totalBytes": size, "totalChunks": total})
            def send(index, altered=False):
                with path.open("rb") as stream:
                    stream.seek(index * CHUNK)
                    payload = stream.read(CHUNK)
                if altered:
                    payload = bytes([payload[0] ^ 1]) + payload[1:]
                r = client.post(BASE + "/media/upload-chunk", data={"uploadId": upload, "chunkIndex": str(index), "totalChunks": str(total)},
                                files={"file": ("part.bin", payload, "application/octet-stream")})
                return r
            for index in range(min(2, total)):
                send(index).raise_for_status()
            existing = set(request("GET", "/media/upload-status", params={"uploadId": upload}))
            assert existing == set(range(min(2, total)))
            assert send(0).status_code == 200
            assert send(0, altered=True).status_code == 409
            with ThreadPoolExecutor(max_workers=4) as pool:
                jobs = [pool.submit(send, i) for i in range(total) if i not in existing]
                for n, job in enumerate(as_completed(jobs), 1):
                    r = job.result(); r.raise_for_status(); assert r.json()["code"] == 0
                    if n % 50 == 0: print(path.name, "chunks", n + len(existing), "/", total, flush=True)
            chunks_seconds = time.monotonic() - started
            merged = time.monotonic()
            media = request("POST", "/media/complete-upload", params={"uploadId": upload})
            merge_seconds = time.monotonic() - merged
            repeated = request("POST", "/media/complete-upload", params={"uploadId": upload})
            assert repeated["id"] == media["id"]
            url = request("GET", "/media/playback", params={"id": media["id"]})
            remote_hash, received = hashlib.sha256(), 0
            with httpx.stream("GET", url, timeout=180, trust_env=False) as response:
                response.raise_for_status()
                for block in response.iter_bytes(1024 * 1024):
                    remote_hash.update(block); received += len(block)
            assert received == size and remote_hash.hexdigest() == digest(path)
            row = {"file": str(path), "kind": kind, "bytes": size, "durationSeconds": duration,
                   "resolution": resolution, "chunks": total, "uploadSeconds": round(chunks_seconds, 2),
                   "mergeSeconds": round(merge_seconds, 2), "mediaId": media["id"], "sha256": remote_hash.hexdigest(),
                   "resumeStatus": True, "duplicatePart": True, "conflictingPartRejected": True,
                   "idempotentComplete": True, "downloadHashMatches": True}
            report["results"].append(row)
            report_file.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
            print(json.dumps(row), flush=True)
    print("ALL UPLOAD CHECKS PASSED", flush=True)


if __name__ == "__main__":
    main()
