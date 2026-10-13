"""Load local dotenv without shell evaluation and run the packaged backend."""
import argparse
import os
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument("--without-model", action="store_true",
                    help="Disable remote model access for infrastructure/failure-path smoke checks")
args = parser.parse_args()
env = os.environ.copy()
for raw in (root / ".env").read_text(encoding="utf-8").splitlines():
    line = raw.strip()
    if not line or line.startswith("#"):
        continue
    key, value = line.split("=", 1)
    value = value.strip()
    if len(value) >= 2 and value[0] == value[-1] and value[0] in ("'", chr(34)):
        value = value[1:-1]
    env[key.strip()] = value
if args.without_model:
    env["SILICONFLOW_API_KEY"] = "local-infrastructure-check-only"
    env["SILICONFLOW_BASE_URL"] = "http://127.0.0.1:1/v1"
    env["ASR_URL"] = "http://127.0.0.1:1/v1/audio/transcriptions"
    print("Infrastructure mode: remote model endpoints disabled.", flush=True)
elif not env.get("SILICONFLOW_API_KEY"):
    raise SystemExit("Fill SILICONFLOW_API_KEY in .env, or pass --without-model.")
jar = root / "server/target/videotrace-backend-0.0.1-SNAPSHOT.jar"
if not jar.exists():
    raise SystemExit("Build the backend jar with Maven package first.")
process = subprocess.Popen(["java", "-Xms128m", "-Xmx1024m", "-jar", str(jar)], cwd=root, env=env)
(root / "docs/backend.pid").write_text(str(process.pid), encoding="utf-8")
try:
    raise SystemExit(process.wait())
except KeyboardInterrupt:
    process.terminate()
    raise SystemExit(process.wait(timeout=35))
