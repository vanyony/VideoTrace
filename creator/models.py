from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)


class Scene(StrictModel):
    sceneId: str = Field(pattern=r"^[A-Za-z0-9_-]{1,48}$")
    template: Literal["title", "points", "flow"]
    durationMs: int = Field(ge=2000, le=30000)
    headline: str = Field(min_length=1, max_length=50)
    points: list[str] = Field(default_factory=list, max_length=4)
    animation: Literal["fade", "reveal", "highlight"]
    sourceRefs: list[str] = Field(min_length=1, max_length=8)

    @model_validator(mode="after")
    def text_limits(self):
        if not self.headline.strip() or any(not p.strip() or len(p) > 72 for p in self.points):
            raise ValueError("headline/points must be nonblank; each point is at most 72 characters")
        if any(not __import__("re").fullmatch(r"e_\d+_\d+", ref) for ref in self.sourceRefs):
            raise ValueError("invalid evidence reference")
        return self


class Storyboard(StrictModel):
    schemaVersion: Literal[1] = 1
    revision: int = Field(ge=1)
    title: str = Field(min_length=1, max_length=80)
    targetAudience: str = Field(min_length=1, max_length=80)
    musicId: Literal["light_01", "none"] = "light_01"
    scenes: list[Scene] = Field(min_length=3, max_length=8)

    @model_validator(mode="after")
    def valid_timeline(self):
        if len({s.sceneId for s in self.scenes}) != len(self.scenes):
            raise ValueError("sceneId must be unique")
        if not 30000 <= sum(s.durationMs for s in self.scenes) <= 60000:
            raise ValueError("total duration must be 30000..60000ms")
        return self


class Operation(StrictModel):
    taskId: str = Field(pattern=r"^[A-Za-z0-9_-]{1,80}$")
    operationId: str = Field(pattern=r"^[A-Za-z0-9_-]{1,80}$")
    attempt: int = Field(ge=1, le=100)
    ownerId: int = Field(gt=0)
    mediaId: int = Field(gt=0)
    sourceVersion: str = Field(pattern=r"^[A-Za-z0-9_-]{1,32}$")
    type: Literal["CREATE", "REVISE", "RENDER", "CONVERSE"]
    parameters: dict

    @model_validator(mode="after")
    def safe_parameters(self):
        import json
        if len(json.dumps(self.parameters, ensure_ascii=False)) > 64000:
            raise ValueError("operation parameters too large")
        p = self.parameters
        if self.type in ("CREATE", "CONVERSE"):
            if not isinstance(p.get("requirement"), str) or not 1 <= len(p["requirement"].strip()) <= 2000:
                raise ValueError("requirement must contain 1..2000 characters")
            if p.get("durationSeconds", 45) not in range(30, 61):
                raise ValueError("durationSeconds must be 30..60")
            if self.type == "CONVERSE":
                if not isinstance(p.get("instruction"), str) or not 1 <= len(p["instruction"].strip()) <= 2000:
                    raise ValueError("instruction must contain 1..2000 characters")
                messages = p.get("messages", [])
                if not isinstance(messages, list) or len(messages) > 20:
                    raise ValueError("conversation history must contain at most 20 messages")
                for message in messages:
                    if not isinstance(message, dict) or message.get("role") not in ("user", "assistant") or not isinstance(message.get("content"), str) or len(message["content"]) > 4000:
                        raise ValueError("invalid conversation message")
                if sum(len(m["content"]) for m in messages) > 24000:
                    raise ValueError("conversation history too large")
                if p.get("storyboard") is not None:
                    board = Storyboard.model_validate(p["storyboard"])
                    if p.get("baseRevision") != board.revision:
                        raise ValueError("baseRevision differs from storyboard")
        else:
            board = Storyboard.model_validate(p.get("storyboard"))
            if p.get("baseRevision") != board.revision:
                raise ValueError("baseRevision differs from storyboard")
            if self.type == "RENDER" and p.get("confirmedRevision") != board.revision:
                raise ValueError("explicit confirmation of current storyboard revision required")
            if self.type == "REVISE":
                if not isinstance(p.get("instruction"), str) or not 1 <= len(p["instruction"].strip()) <= 2000:
                    raise ValueError("instruction must contain 1..2000 characters")
                targets = p.get("targetSceneIds") or []
                if not isinstance(targets, list) or not all(isinstance(s, str) for s in targets) or not set(targets) <= {s.sceneId for s in board.scenes}:
                    raise ValueError("unknown targetSceneIds")
        return self


def validate_candidate(data: dict, operation: Operation, evidence_ids: set[str]) -> dict:
    board = Storyboard.model_validate(data)
    expected = operation.parameters.get("baseRevision", 0) + 1
    if board.revision != expected:
        raise ValueError(f"revision must be {expected}")
    old = operation.parameters.get("storyboard")
    expected_duration = sum(s["durationMs"] for s in old["scenes"]) if old else operation.parameters.get("durationSeconds", 45) * 1000
    if sum(s.durationMs for s in board.scenes) != expected_duration:
        raise ValueError(f"total duration must equal selected/approved duration {expected_duration}ms")
    permitted = set(evidence_ids)
    if old:
        permitted.update(ref for s in old["scenes"] for ref in s["sourceRefs"])
    if any(ref not in permitted for s in board.scenes for ref in s.sourceRefs):
        raise ValueError("sourceRefs must refer to evidence retrieved from this operation's bound video")
    targets = set(operation.parameters.get("targetSceneIds") or [])
    if old and targets:
        before = [s for s in old["scenes"] if s["sceneId"] not in targets]
        after = [s.model_dump() for s in board.scenes if s.sceneId not in targets]
        if before != after or old["title"] != board.title or old["musicId"] != board.musicId or old["targetAudience"] != board.targetAudience:
            raise ValueError("non-target scenes, order and global fields must remain unchanged")
    from .renderer import validate_layout
    validate_layout(board)
    return board.model_dump()


def patch_storyboard(old: dict, patch: dict) -> dict:
    """Constrained operations; never accepts arbitrary paths or executes model code."""
    import copy
    board = copy.deepcopy(old)
    if patch.get("baseRevision") != old["revision"]:
        raise ValueError("stale baseRevision")
    allowed = {"baseRevision", "updates", "insertions", "deleteSceneIds", "sceneOrder", "title", "musicId", "targetAudience"}
    if set(patch) - allowed:
        raise ValueError("unsupported patch field")
    scenes = board["scenes"]
    for update in patch.get("updates", []):
        if set(update) - {"sceneId", "template", "durationMs", "headline", "points", "animation", "sourceRefs"}:
            raise ValueError("unsupported scene field")
        matches = [s for s in scenes if s["sceneId"] == update["sceneId"]]
        if not matches:
            raise ValueError("unknown sceneId")
        matches[0].update(update)
    deletes = set(patch.get("deleteSceneIds", []))
    if not deletes <= {s["sceneId"] for s in scenes}:
        raise ValueError("unknown deleted sceneId")
    scenes = [s for s in scenes if s["sceneId"] not in deletes]
    for insertion in patch.get("insertions", []):
        if set(insertion) != {"index", "scene"} or not isinstance(insertion["index"], int) or not 0 <= insertion["index"] <= len(scenes):
            raise ValueError("insertion requires valid index and scene")
        scenes.insert(insertion["index"], Scene.model_validate(insertion["scene"]).model_dump())
    if "sceneOrder" in patch:
        order = patch["sceneOrder"]
        if len(order) != len(scenes) or set(order) != {s["sceneId"] for s in scenes}:
            raise ValueError("sceneOrder must contain every scene exactly once")
        scenes = [next(s for s in scenes if s["sceneId"] == sid) for sid in order]
    board["scenes"] = scenes
    for key in ("title", "musicId", "targetAudience"):
        if key in patch:
            board[key] = patch[key]
    board["revision"] += 1
    return board
