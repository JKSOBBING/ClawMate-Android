"""Tasks HTTP routes using explicit application services."""

from fastapi import APIRouter, HTTPException

from ..persistence import tasks as task_store
from .common import guard
from .schemas import ProcessRequest


def router(services):
    router = APIRouter(prefix="/api/v1", tags=["tasks"])

    @router.post("/meetings/{mid}/summary", status_code=202)
    async def summarize(mid: str):
        return await services.summarize(mid)

    @router.get("/tasks")
    def tasks(meeting_id: str | None = None):
        return task_store.tasks(meeting_id)

    @router.get("/tasks/{tid}")
    def task(tid: str):
        return guard(lambda: task_store.task(tid))

    @router.post("/tasks/{tid}/cancel", status_code=202)
    def cancel(tid: str):
        return guard(lambda: task_store.cancel(tid))

    @router.post("/tasks/{tid}/retry", status_code=202)
    async def retry(tid: str):
        task = guard(lambda: task_store.task(tid))
        if task["status"] not in ("failed", "cancelled", "interrupted"):
            raise HTTPException(409, "只有失败、取消或中断的任务可重试")
        if task["kind"] == "refine":
            args = task["args"]
            return await services.reprocess(task["mid"], ProcessRequest(**args))
        return await services.summarize(task["mid"])

    return router
