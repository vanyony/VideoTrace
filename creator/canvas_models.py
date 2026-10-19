"""Declarative canvas contract shared by model tools and the renderer."""
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator


class CanvasData(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True, allow_inf_nan=False)


class Keyframe(CanvasData):
    timeMs: int = Field(ge=0, le=30000)
    x: float | None = Field(default=None, ge=-720, le=1440)
    y: float | None = Field(default=None, ge=-1280, le=2560)
    scale: float | None = Field(default=None, ge=.25, le=4)
    opacity: float | None = Field(default=None, ge=0, le=1)
    rotation: float | None = Field(default=None, ge=-360, le=360)
    easing: Literal["linear", "easeInOut", "easeOut"] = "easeInOut"


class Point(CanvasData):
    x: float = Field(ge=-720, le=1440)
    y: float = Field(ge=-1280, le=2560)


class CameraKeyframe(CanvasData):
    timeMs: int = Field(ge=0, le=30000)
    x: float = Field(default=360, ge=-720, le=1440)
    y: float = Field(default=640, ge=-1280, le=2560)
    zoom: float = Field(default=1, ge=.5, le=3)
    easing: Literal["linear", "easeInOut", "easeOut"] = "easeInOut"


class Curve(CanvasData):
    points: list[Point] = Field(min_length=4, max_length=4)
    startMs: int = Field(default=0, ge=0, le=30000)
    endMs: int = Field(default=2000, ge=1, le=30000)
    loop: bool = False

    @model_validator(mode="after")
    def timing(self):
        if self.endMs <= self.startMs:
            raise ValueError("curve endMs must be after startMs")
        return self


class CanvasElement(CanvasData):
    id: str = Field(pattern=r"^[A-Za-z0-9_-]{1,32}$")
    kind: Literal["circle", "rectangle", "text", "icon", "line", "arrow", "sprite", "path"]
    x: float = Field(default=360, ge=-720, le=1440)
    y: float = Field(default=640, ge=-1280, le=2560)
    width: float = Field(default=120, ge=8, le=1280)
    height: float = Field(default=80, ge=8, le=1280)
    color: str = Field(default="#64dfbf", pattern=r"^#[0-9a-fA-F]{6}$")
    fill: str = Field(default="#172641", pattern=r"^#[0-9a-fA-F]{6}$")
    text: str = Field(default="", max_length=120)
    fontSize: int = Field(default=28, ge=16, le=160)
    icon: Literal["video", "audio", "text", "agent", "cards", "phone", "server", "database", "person", "file"] = "file"
    fromId: str | None = Field(default=None, pattern=r"^[A-Za-z0-9_-]{1,32}$")
    toId: str | None = Field(default=None, pattern=r"^[A-Za-z0-9_-]{1,32}$")
    z: int = Field(default=1, ge=0, le=20)
    space: Literal["world", "screen"] = "world"
    opacity: float = Field(default=1, ge=0, le=1)
    scale: float = Field(default=1, ge=.25, le=4)
    rotation: float = Field(default=0, ge=-360, le=360)
    pulse: bool = False
    sprite: Literal["sun", "earth", "cloud", "droplet", "factory", "truck", "water", "ripple"] = "cloud"
    curve: Curve | None = None
    keyframes: list[Keyframe] = Field(default_factory=list, max_length=12)


class Canvas(CanvasData):
    background: str = Field(default="#0b1428", pattern=r"^#[0-9a-fA-F]{6}$")
    layout: Literal["framed", "full"] = "framed"
    camera: list[CameraKeyframe] = Field(default_factory=list, max_length=8)
    continuePrevious: bool = False
    elements: list[CanvasElement] = Field(min_length=1, max_length=40)

    @model_validator(mode="after")
    def references(self):
        camera_times = [k.timeMs for k in self.camera]
        if camera_times != sorted(set(camera_times)):
            raise ValueError("camera keyframe times must strictly increase")
        if self.layout == "framed" and (self.camera or len(self.elements) > 20 or any(len(e.keyframes) > 4 for e in self.elements)):
            raise ValueError("framed canvas supports 20 elements, four keyframes and no camera; use layout=full")
        ids = {e.id for e in self.elements}
        if len(ids) != len(self.elements):
            raise ValueError("canvas element ids must be unique")
        nodes = {e.id for e in self.elements if e.kind not in ("line", "arrow", "path")}
        for element in self.elements:
            times = [k.timeMs for k in element.keyframes]
            if times != sorted(set(times)):
                raise ValueError(f"canvas {element.id}: keyframe times must strictly increase")
            if element.kind in ("sprite", "path") and element.text:
                raise ValueError("sprites and paths use separate text labels")
            if element.kind == "path" and element.curve is None:
                raise ValueError("path requires a cubic curve with four points")
            if element.curve and element.kind in ("line", "arrow"):
                raise ValueError("connectors cannot have curves")
            if element.curve and any(k.x is not None or k.y is not None for k in element.keyframes):
                raise ValueError("curve controls position; do not also use x/y keyframes")
            if element.kind in ("line", "arrow", "path"):
                if element.pulse or element.rotation or element.scale != 1:
                    raise ValueError("paths/connectors cannot rotate, scale or pulse")
                if any(k.rotation is not None or k.scale is not None for k in element.keyframes):
                    raise ValueError("paths/connectors animate opacity only")
            if element.kind in ("line", "arrow"):
                if element.fromId not in nodes or element.toId not in nodes or element.fromId == element.toId:
                    raise ValueError(f"canvas {element.id}: connectors need two distinct node references")
                lookup = {e.id: e for e in self.elements}
                if any(lookup[name].space != element.space for name in (element.fromId, element.toId)):
                    raise ValueError("connector endpoints must use the same coordinate space")
                if any(k.x is not None or k.y is not None or k.scale is not None for k in element.keyframes):
                    raise ValueError("connectors follow nodes; animate connector opacity only")
            elif element.fromId is not None or element.toId is not None:
                raise ValueError("only connectors may reference nodes")
        return self
