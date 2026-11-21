"""Documents HTTP routes using explicit application services."""

import json

from fastapi import APIRouter, HTTPException, Query
from fastapi.responses import Response

from ..persistence import documents as document_store
from .common import WorkspaceAccess, guard
from .schemas import Document, Restore
from pydantic import BaseModel, Field
from typing import Literal


class HandoffRequest(BaseModel):
    base_revision: int = Field(ge=0)
    source_hash: str = Field(pattern=r"^[0-9a-f]{64}$")
    selection: Literal["transcript", "summary", "both"] = "both"


def router(services):
    router = APIRouter(prefix="/api/v1", tags=["documents"])
    access = WorkspaceAccess(services)
    exists = access.exists
    mutate = access.mutate

    @router.post("/meetings/{mid}/handoff")
    def handoff(mid: str, body: HandoffRequest):
        exists(mid)
        from .. import handoff as handoff_store
        return guard(lambda: handoff_store.export(mid, body.base_revision, body.source_hash, body.selection))

    @router.get("/meetings/{mid}/handoff")
    def download_handoff(mid: str, base_revision: int = Query(ge=0),
                         source_hash: str = Query(pattern=r"^[0-9a-f]{64}$"),
                         selection: Literal["transcript", "summary", "both"] = "both"):
        packet = handoff(mid, HandoffRequest(base_revision=base_revision, source_hash=source_hash, selection=selection))
        return Response(json.dumps(packet, ensure_ascii=False), media_type="application/json",
                        headers={"Content-Disposition": f'attachment; filename="{mid}-handoff.json"'})

    @router.get("/meetings/{mid}/export")
    def export(mid: str):
        return Response(
            json.dumps(services.meeting(mid), ensure_ascii=False, indent=2),
            media_type="application/json",
            headers={"Content-Disposition": f'attachment; filename="{mid}.json"'},
        )

    @router.get("/meetings/{mid}/document")
    def document(mid: str):
        exists(mid)
        return document_store.document(mid)

    @router.put("/meetings/{mid}/document")
    def save_document(mid: str, body: Document):
        mutate(mid)
        if any(len(v) > 80 for v in body.speaker_names.values()):
            raise HTTPException(422, "姓名最多 80 字")
        return guard(
            lambda: document_store.save_document(
                mid,
                body.base_revision,
                body.source_hash,
                [s.model_dump() for s in body.segments],
                body.speaker_names,
            )
        )

    @router.get("/meetings/{mid}/versions")
    def versions(mid: str):
        exists(mid)
        return dict(
            active_revision=document_store.document(mid)["revision"],
            versions=document_store.versions(mid),
        )

    @router.post("/meetings/{mid}/versions/restore")
    def restore(mid: str, body: Restore):
        mutate(mid)
        return guard(
            lambda: document_store.restore(mid, body.revision, body.base_revision)
        )

    @router.get("/meetings/{mid}/export.md")
    def markdown(mid: str):
        meeting = exists(mid)
        doc = document_store.document(mid)
        text = "# " + meeting["title"] + "\n\n" + doc["transcript"]
        if meeting.get("summary"):
            text += "\n\n## 纪要\n\n" + meeting["summary"]["text"]
        return Response(
            text,
            media_type="text/markdown",
            headers={"Content-Disposition": f'attachment; filename="{mid}.md"'},
        )

    return router
