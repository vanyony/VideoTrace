"""Measure the production FFmpeg preprocessing rules and sampled local OCR; no cloud calls."""
from concurrent.futures import ThreadPoolExecutor
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import time

from dotenv import dotenv_values
import imageio_ffmpeg

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "creator/data/upload-capacity/loop-4h.mp4"
DATA = ROOT / "creator/data/preprocessing-4h"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--resume", action="store_true", help="Reuse already measured audio segmentation")
    args = parser.parse_args()
    DATA.mkdir(parents=True, exist_ok=True)
    env = os.environ.copy()
    env.update({k: v for k, v in dotenv_values(ROOT / ".env").items() if v is not None})
    report = {"source": str(SOURCE), "scope": "Full audio segmentation and frame extraction; sampled local OCR; zero cloud API requests"}
    def save():
        (DATA / "result.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    def run(name, command):
        started = time.monotonic()
        with (DATA / (name + ".log")).open("wb") as log:
            process = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT, env=env,
                                       creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
            try:
                status = process.wait(timeout=15 * 60)
            except subprocess.TimeoutExpired:
                process.kill(); process.wait()
                report[name] = {"passed": False, "reason": "production 15 minute timeout"}; save()
                raise
        elapsed = round(time.monotonic() - started, 2)
        assert status == 0, name + " failed; see its local log"
        print(name, "PASS seconds", elapsed, flush=True)
        return elapsed
    audio = DATA / "audio"
    audio.mkdir(exist_ok=True)
    if args.resume:
        cached = json.loads((DATA / "result.json").read_text(encoding="utf-8"))
        elapsed = cached["audio"]["elapsedSeconds"]
        print("Reusing measured audio segmentation", flush=True)
    else:
        elapsed = run("audio", [imageio_ffmpeg.get_ffmpeg_exe(), "-y", "-i", str(SOURCE), "-vn", "-acodec", "libmp3lame",
                                "-f", "segment", "-segment_time", "60", "-reset_timestamps", "1", str(audio / "audio_%03d.mp3")])
    segments = sorted(audio.glob("audio_*.mp3"))
    report["audio"] = {"passed": True, "elapsedSeconds": elapsed, "segments": len(segments),
                       "bytes": sum(p.stat().st_size for p in segments), "cloudAsrRequestsMade": 0}
    assert segments and all(p.stat().st_size > 0 for p in segments)
    def decode(index):
        decoded = subprocess.run([imageio_ffmpeg.get_ffmpeg_exe(), "-v", "error", "-i", str(segments[index]), "-f", "null", "-"],
                                 capture_output=True, timeout=30)
        return {"segment": segments[index].name, "bytes": segments[index].stat().st_size, "decodes": decoded.returncode == 0,
                "error": decoded.stderr.decode(errors="replace")[-350:] if decoded.returncode else ""}
    with ThreadPoolExecutor(max_workers=4) as pool:
        checks = list(pool.map(decode, range(len(segments))))
    report["audio"]["decodeCheckedSegments"] = len(checks)
    report["audio"]["invalidSegments"] = [c for c in checks if not c["decodes"]]
    report["audio"]["passed"] = all(c["decodes"] for c in checks)
    save()
    frames = DATA / "frames"
    frames.mkdir(exist_ok=True)
    elapsed = run("frames", [imageio_ffmpeg.get_ffmpeg_exe(), "-y", "-i", str(SOURCE), "-vf",
                             r"select=eq(n\,0)+gt(scene\,0.35)+gte(t-prev_selected_t\,30),showinfo", "-fps_mode", "vfr",
                             str(frames / "frame_%06d.jpg")])
    images = sorted(frames.glob("frame_*.jpg"))
    times = [float(x) for x in re.findall(r"\bpts_time:([\d.]+)", (DATA / "frames.log").read_text(encoding="utf-8", errors="replace"))]
    report["frames"] = {"passed": True, "elapsedSeconds": elapsed, "rawFrames": len(images),
                        "bytes": sum(p.stat().st_size for p in images), "firstTimestamp": times[0] if times else None,
                        "lastTimestamp": times[-1] if times else None,
                        "note": "Before the production adjacent-frame dHash deduplication"}
    save()
    count = min(12, len(images))
    indices = sorted({round(i * (len(images) - 1) / max(1, count - 1)) for i in range(count)})
    def recognize(index):
        started = time.monotonic()
        result = subprocess.run([env.get("OCR_COMMAND", "tesseract"), str(images[index]), "stdout", "-l", "chi_sim+eng"],
                                capture_output=True, timeout=120, env=env,
                                creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        text = result.stdout.decode("utf-8", errors="replace").strip()
        return {"frame": images[index].name, "timestamp": times[index] if index < len(times) else None,
                "passed": result.returncode == 0, "elapsedSeconds": round(time.monotonic() - started, 2), "text": text}
    started = time.monotonic()
    with ThreadPoolExecutor(max_workers=2) as pool:
        results = list(pool.map(recognize, indices))
    report["ocrSample"] = {"framesTested": len(results), "parallelism": 2,
                           "elapsedSeconds": round(time.monotonic() - started, 2), "results": results,
                           "note": "Only sampled OCR, not a complete OCR run or accuracy benchmark"}
    report["passed"] = report["audio"]["passed"] and all(r["passed"] for r in results)
    save()
    print("LOCAL PREPROCESSING CHECKS", "PASS" if report["passed"] else "FAIL", flush=True)


if __name__ == "__main__":
    main()
