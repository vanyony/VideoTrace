from dataclasses import dataclass
import os
from pathlib import Path
from urllib.parse import urlsplit


@dataclass(frozen=True)
class Settings:
    data_dir: Path
    token: str
    java_url: str
    llm_url: str
    llm_key: str
    llm_model: str
    model_timeout: int = 240
    render_timeout: int = 600

    @classmethod
    def from_env(cls):
        return cls(Path(os.getenv("CREATOR_DATA_DIR", "creator/data")).resolve(),
                   os.getenv("CREATOR_INTERNAL_TOKEN", ""),
                   os.getenv("CREATOR_JAVA_URL", "http://127.0.0.1:9090").rstrip("/"),
                   os.getenv("CREATOR_LLM_BASE_URL", os.getenv("SILICONFLOW_BASE_URL", "https://api.siliconflow.cn/v1")).rstrip("/"),
                   os.getenv("CREATOR_LLM_API_KEY", os.getenv("SILICONFLOW_API_KEY", "")),
                   os.getenv("CREATOR_LLM_MODEL", os.getenv("LLM_MODEL", "deepseek-ai/DeepSeek-V3.2")),
                   int(os.getenv("CREATOR_MODEL_TIMEOUT_SECONDS", "240")),
                   int(os.getenv("CREATOR_RENDER_TIMEOUT_SECONDS", "600")))

    def validate(self):
        parsed = urlsplit(self.java_url)
        # Service transport is confined to loopback/private deployments, never model-controlled.
        if parsed.scheme not in ("http", "https") or not parsed.hostname or parsed.username or parsed.password:
            raise ValueError("invalid CREATOR_JAVA_URL")
        if self.token and len(self.token) < 32:
            raise ValueError("CREATOR_INTERNAL_TOKEN must contain at least 32 characters")
        if not 10 <= self.model_timeout <= 1800 or not 10 <= self.render_timeout <= 3600:
            raise ValueError("invalid creator timeout")


class JavaClient:
    def __init__(self, settings):
        import httpx
        self.client = httpx.Client(base_url=settings.java_url, headers={"X-Creator-Token": settings.token},
                                   timeout=40, trust_env=False)

    def post(self, path, body):
        response = self.client.post(path, json=body)
        response.raise_for_status()
        result = response.json()
        if result.get("code") not in (0, 200):
            raise RuntimeError("Java internal API rejected request: " + str(result.get("message", "unknown"))[:200])
        return result.get("data")

    def evidence(self, operation, action, arguments):
        return self.post(f"/internal/creation/tasks/{operation.taskId}/{action}",
                         {"operationId": operation.operationId, "attempt": operation.attempt, **arguments})
