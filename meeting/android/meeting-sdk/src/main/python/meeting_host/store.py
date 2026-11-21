import json, os, sqlite3, uuid
from pathlib import Path
from datetime import datetime, timezone
from contextlib import contextmanager

ROOT = Path(os.getenv("MEETING_DATA", Path(__file__).resolve().parent.parent / "data"))
ROOT.mkdir(parents=True, exist_ok=True)


@contextmanager
def connection():
    db = sqlite3.connect(ROOT / "meetings.sqlite3", timeout=30)
    db.row_factory = sqlite3.Row
    try:
        with db:
            yield db
    finally:
        db.close()


def initialize():
    with connection() as db:
        db.execute("PRAGMA journal_mode=WAL")
        db.execute(
            "CREATE TABLE IF NOT EXISTS meetings (id TEXT PRIMARY KEY,title TEXT,created TEXT,status TEXT,audio TEXT,result TEXT,summary TEXT,error TEXT)"
        )
        db.execute(
            "UPDATE meetings SET status='interrupted', error='录音服务已中断，正在检查已保存音频' WHERE status='recording'"
        )
        db.execute(
            "UPDATE meetings SET status='failed', error='Service restarted during processing; submit again' WHERE status IN ('processing','summarizing')"
        )


def create(title, status="created"):
    mid = uuid.uuid4().hex
    with connection() as db:
        db.execute(
            "INSERT INTO meetings VALUES (?,?,?,?,?,?,?,?)",
            (
                mid,
                title,
                datetime.now(timezone.utc).isoformat(),
                status,
                None,
                None,
                None,
                None,
            ),
        )
    return get(mid)


def get(mid):
    with connection() as db:
        row = db.execute("SELECT * FROM meetings WHERE id=?", (mid,)).fetchone()
    if row is None:
        raise KeyError(mid)
    item = dict(row)
    for field in ("result", "summary"):
        if item[field]:
            item[field] = json.loads(item[field])
    item.pop("audio")  # internal paths never form part of the API
    return item


def update(mid, **fields):
    assert fields and set(fields) <= {"status", "audio", "result", "summary", "error"}
    values = [
        json.dumps(v, ensure_ascii=False) if k in ("result", "summary") else v
        for k, v in fields.items()
    ]
    with connection() as db:
        db.execute(
            "UPDATE meetings SET " + ",".join(k + "=?" for k in fields) + " WHERE id=?",
            [*values, mid],
        )


def listing():
    with connection() as db:
        rows = db.execute(
            "SELECT id,title,created,status,error FROM meetings ORDER BY created DESC LIMIT 100"
        ).fetchall()
    return [dict(r) for r in rows]
