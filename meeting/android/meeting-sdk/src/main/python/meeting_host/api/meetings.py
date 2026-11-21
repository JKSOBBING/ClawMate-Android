"""Meetings HTTP routes using explicit application services."""

from datetime import datetime, timezone
from typing import Literal

from fastapi import APIRouter, HTTPException, Query

from .. import history_store as history
from ..persistence import metadata as metadata_store
from .common import WorkspaceAccess, guard, clean_tags
from .schemas import Batch, Draft, MeetingCreate, Metadata, Promote


def router(services):
    router = APIRouter(prefix="/api/v1", tags=["meetings"])
    access = WorkspaceAccess(services)
    exists = access.exists
    mutate = access.mutate

    @router.post("/meetings", status_code=201)
    def create(body: MeetingCreate):
        return services.store.create(body.title)

    @router.get("/meetings")
    def listing(q: str = "", archived: bool = False):
        return services.workspace.list_meetings(q, archived)

    @router.get("/meetings/{mid}")
    def get(mid: str):
        return services.meeting(mid)

    @router.post("/inbox", status_code=201)
    def draft(body: Draft):
        return services.meeting(history.create_draft(body.source))

    @router.post("/meetings/{mid}/promote")
    def promote(mid: str, body: Promote):
        exists(mid)
        if mid in services.active:
            raise HTTPException(409, "请等待处理结束")
        title = body.title.strip()
        if not title:
            raise HTTPException(422, "标题不能为空")
        guard(lambda: history.promote(mid, title, clean_tags(body.tags)))
        return services.meeting(mid)

    @router.get("/history")
    def history_list(
        q: str = Query("", max_length=500),
        view: Literal[
            "meetings", "inbox", "archived", "pinned", "trash", "all"
        ] = "meetings",
        status: Literal[
            "",
            "created",
            "recording",
            "recorded",
            "processing",
            "summarizing",
            "ready",
            "failed",
            "interrupted",
        ] = "",
        source: Literal["", "manual", "recording", "upload"] = "",
        since: datetime | None = None,
        until: datetime | None = None,
        sort: Literal["updated", "created", "oldest", "title"] = "updated",
        page: int = Query(1, ge=1),
        page_size: int = Query(20, ge=1, le=100),
    ):
        def utc(value):
            if value is None:
                return None
            if value.tzinfo is None:
                value = value.replace(tzinfo=timezone.utc)
            return value.astimezone(timezone.utc).isoformat()

        start, end = utc(since), utc(until)
        if start and end and start >= end:
            raise HTTPException(422, "结束日期须晚于开始日期")
        return history.listing(
            q, view, status, source, start, end, sort, page, page_size
        )

    @router.post("/history/batch")
    def history_batch(body: Batch):
        ids = list(dict.fromkeys(body.ids))
        if any(mid in services.active for mid in ids):
            raise HTTPException(409, "选中记录仍在处理，请稍后再试")
        return guard(lambda: history.batch(ids, body.action))

    @router.patch("/meetings/{mid}")
    def metadata(mid: str, body: Metadata):
        item = mutate(mid)
        values = body.model_dump(exclude_none=True)
        if "archived" in values:
            if item["draft"]:
                raise HTTPException(409, "请先将待整理记录保存为会议，再归档")
            if mid in services.active or item["status"] in (
                "processing",
                "summarizing",
            ):
                raise HTTPException(409, "请等待处理结束")
        if "title" in values:
            values["title"] = values["title"].strip()
            if not values["title"]:
                raise HTTPException(422, "标题不能为空")
        if "tags" in values:
            values["tags"] = clean_tags(values["tags"])
        metadata_store.set_metadata(mid, values)
        return services.meeting(mid)

    return router
