"""Tasks persistence; no HTTP or model dependencies."""

from .. import store
from .common import encode, now
import json, uuid


class JobCancelled(Exception):
    pass


class Job:
    def __init__(self, mid, kind, args):
        self.id = uuid.uuid4().hex
        with store.connection() as db:
            db.execute(
                "INSERT INTO workspace_tasks VALUES (?,?,?,?,?,?,?,?,?,?)",
                (
                    self.id,
                    mid,
                    kind,
                    "queued",
                    "排队",
                    0.0,
                    encode(args),
                    None,
                    now(),
                    now(),
                ),
            )

    def check(self):
        with store.connection() as db:
            r = db.execute(
                "SELECT status FROM workspace_tasks WHERE id=?", (self.id,)
            ).fetchone()
        if r and r["status"] == "cancel_requested":
            raise JobCancelled()

    def progress(self, stage, value=0.0):
        self.check()
        with store.connection() as db:
            db.execute(
                "UPDATE workspace_tasks SET status='running',stage=?,progress=?,updated=? WHERE id=? AND status!='cancel_requested'",
                (stage, min(1.0, max(0.0, value)), now(), self.id),
            )

    def finish(self, status, error=None):
        with store.connection() as db:
            db.execute(
                "UPDATE workspace_tasks SET status=?,stage=?,progress=CASE WHEN ?='completed' THEN 1 ELSE progress END,error=?,updated=? WHERE id=?",
                (status, status, status, error, now(), self.id),
            )


def task(tid):
    with store.connection() as db:
        r = db.execute("SELECT * FROM workspace_tasks WHERE id=?", (tid,)).fetchone()
    if not r:
        raise ValueError("任务不存在")
    result = dict(r)
    result["args"] = json.loads(result["args"])
    return result


def tasks(mid=None):
    with store.connection() as db:
        rows = db.execute(
            "SELECT id FROM workspace_tasks"
            + (" WHERE mid=?" if mid else "")
            + " ORDER BY created DESC LIMIT 50",
            (mid,) if mid else (),
        ).fetchall()
    return [task(r["id"]) for r in rows]


def cancel(tid):
    with store.connection() as db:
        changed = db.execute(
            "UPDATE workspace_tasks SET status='cancel_requested',updated=? WHERE id=? AND status IN ('queued','running')",
            (now(), tid),
        ).rowcount
        if not changed:
            raise ValueError("任务已结束或正在取消")
    return task(tid)
