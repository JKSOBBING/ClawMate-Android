"""Public request payloads; kept separate from handlers and services."""

from typing import Literal
from pydantic import BaseModel, Field


class MeetingCreate(BaseModel):
    title: str = Field(default="本地会议", min_length=1, max_length=200)


class ProcessRequest(BaseModel):
    speakers: int = Field(default=-1, ge=-1, le=20)
    compare: bool = True
    offline_model: Literal["qwen3_asr", "sensevoice"] = "qwen3_asr"
    refinement_profile_id: str | None = None
    audio_id: str | None = None


class RefinementProfileRequest(BaseModel):
    name: str = Field(min_length=1, max_length=80)
    kind: Literal["remote_audio", "remote_text"]
    base_url: str = Field(min_length=1, max_length=1000)
    model: str = Field(min_length=1, max_length=200)
    api_key: str | None = Field(default=None, max_length=4096)
    timeout_seconds: int = Field(default=60, ge=5, le=180)


class RefinementSettingsRequest(BaseModel):
    active_id: str
    auto_enabled: bool = True


class Metadata(BaseModel):
    title: str | None = Field(default=None, min_length=1, max_length=200)
    tags: list[str] | None = Field(default=None, max_length=20)
    archived: bool | None = None


class Draft(BaseModel):
    source: Literal["recording", "upload"] = "recording"


class Promote(BaseModel):
    title: str = Field(min_length=1, max_length=200)
    tags: list[str] = Field(default_factory=list, max_length=20)


class Batch(BaseModel):
    ids: list[str] = Field(min_length=1, max_length=100)
    action: Literal["pin", "unpin", "archive", "unarchive", "trash", "restore"]


class Edit(BaseModel):
    text: str = Field(max_length=20000)
    speaker: str = Field(max_length=80)


class Document(BaseModel):
    base_revision: int = Field(ge=0)
    source_hash: str
    segments: list[Edit] = Field(max_length=10000)
    speaker_names: dict[str, str] = Field(default_factory=dict)


class Restore(BaseModel):
    revision: int = Field(ge=1)
    base_revision: int = Field(ge=0)


class Template(BaseModel):
    name: str = Field(min_length=1, max_length=80)
    refinement_profile_id: str | None = None
    auto_refine: bool = True
    summary_instructions: str = Field(default="", max_length=4000)


class Apply(BaseModel):
    template_id: str
