"""Shared HTTP guards. Domain storage does not import FastAPI."""

from fastapi import HTTPException


class WorkspaceAccess:
    def __init__(self, services):
        self.services = services

    def exists(self, mid):
        return self.services.meeting(mid)

    def mutate(self, mid):
        meeting = self.exists(mid)
        if meeting["trashed_at"]:
            raise HTTPException(409, "请先从回收站恢复此记录")
        if meeting["status"] == "recording":
            raise HTTPException(409, "请先结束录音")
        return meeting


def guard(call):
    try:
        return call()
    except ValueError as e:
        raise HTTPException(409, str(e))


def clean_tags(tags):
    if any(len(t) > 40 for t in tags):
        raise HTTPException(422, "单个标签最多 40 字")
    return list(dict.fromkeys(t.strip() for t in tags if t.strip()))
