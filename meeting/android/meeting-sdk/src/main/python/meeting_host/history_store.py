"""Inbox lifecycle and reversible history management, independent of inference."""

import json
import uuid
from datetime import datetime, timezone
from . import store


def now():
    return datetime.now(timezone.utc).isoformat()


def initialize():
    with store.connection() as db:
        columns = {r["name"] for r in db.execute("PRAGMA table_info(meeting_meta)")}
        for name, definition in dict(
            draft="INTEGER NOT NULL DEFAULT 0",
            source="TEXT NOT NULL DEFAULT 'manual'",
            pinned="INTEGER NOT NULL DEFAULT 0",
            trashed_at="TEXT",
            updated="TEXT",
        ).items():
            if name not in columns:
                db.execute(
                    "ALTER TABLE meeting_meta ADD COLUMN " + name + " " + definition
                )
        db.execute("INSERT OR IGNORE INTO meeting_meta(mid) SELECT id FROM meetings")
        db.execute(
            "UPDATE meeting_meta SET updated=(SELECT created FROM meetings WHERE id=mid) WHERE updated IS NULL"
        )
        db.executescript(
            """
        CREATE TRIGGER IF NOT EXISTS history_created AFTER INSERT ON meetings BEGIN
          INSERT OR IGNORE INTO meeting_meta(mid,updated) VALUES (NEW.id,NEW.created);
        END;
        CREATE TRIGGER IF NOT EXISTS history_content AFTER UPDATE ON meetings BEGIN
          UPDATE meeting_meta SET updated=strftime('%Y-%m-%dT%H:%M:%f+00:00','now') WHERE mid=NEW.id;
        END;
        CREATE TRIGGER IF NOT EXISTS history_metadata AFTER UPDATE OF tags,archived,template,draft,pinned,trashed_at ON meeting_meta BEGIN
          UPDATE meeting_meta SET updated=strftime('%Y-%m-%dT%H:%M:%f+00:00','now') WHERE mid=NEW.mid;
        END;
        CREATE TRIGGER IF NOT EXISTS history_document AFTER INSERT ON document_heads BEGIN
          UPDATE meeting_meta SET updated=strftime('%Y-%m-%dT%H:%M:%f+00:00','now') WHERE mid=NEW.mid;
        END;
        CREATE TRIGGER IF NOT EXISTS history_document_update AFTER UPDATE ON document_heads BEGIN
          UPDATE meeting_meta SET updated=strftime('%Y-%m-%dT%H:%M:%f+00:00','now') WHERE mid=NEW.mid;
        END;
        CREATE INDEX IF NOT EXISTS history_state ON meeting_meta(trashed_at,draft,archived);
        """
        )


def create_draft(source):
    mid = uuid.uuid4().hex
    created = now()
    title = (
        ("录音" if source == "recording" else "上传音频")
        + " · "
        + datetime.now().strftime("%m-%d %H:%M")
    )
    with store.connection() as db:
        db.execute(
            "INSERT INTO meetings VALUES (?,?,?,?,?,?,?,?)",
            (mid, title, created, "created", None, None, None, None),
        )
        db.execute(
            "UPDATE meeting_meta SET draft=1,source=? WHERE mid=?", (source, mid)
        )
    return mid


def _editable(db, mid):
    row = db.execute(
        "SELECT m.status,x.* FROM meetings m JOIN meeting_meta x ON x.mid=m.id WHERE m.id=?",
        (mid,),
    ).fetchone()
    if not row:
        raise ValueError("记录不存在，请刷新列表")
    if row["status"] in ("recording", "processing", "summarizing"):
        raise ValueError("请等待录音或处理结束")
    return row


def promote(mid, title, tags):
    with store.connection() as db:
        db.execute("BEGIN IMMEDIATE")
        row = _editable(db, mid)
        if row["trashed_at"]:
            raise ValueError("请先从回收站恢复此记录")
        if not row["draft"]:
            return  # Retrying an acknowledged save must not overwrite later edits.
        asset = db.execute(
            "SELECT a.state FROM audio_heads h JOIN audio_assets a ON a.id=h.audio_id WHERE h.mid=?",
            (mid,),
        ).fetchone()
        if not asset or asset["state"] not in ("completed", "recovered"):
            raise ValueError("请先完成录音、上传处理，或恢复中断录音")
        db.execute("UPDATE meetings SET title=? WHERE id=?", (title, mid))
        db.execute(
            "UPDATE meeting_meta SET draft=0,tags=? WHERE mid=?",
            (json.dumps(tags, ensure_ascii=False), mid),
        )


def batch(ids, action):
    fields = dict(
        pin=("pinned", 1),
        unpin=("pinned", 0),
        archive=("archived", 1),
        unarchive=("archived", 0),
        trash=("trashed_at", now()),
        restore=("trashed_at", None),
    )
    if action not in fields:
        raise ValueError("不支持的操作")
    with store.connection() as db:
        db.execute("BEGIN IMMEDIATE")
        for mid in ids:
            row = _editable(db, mid)
            if action not in ("trash", "restore") and row["trashed_at"]:
                raise ValueError("请先恢复回收站中的记录")
            if action in ("archive", "unarchive") and row["draft"]:
                raise ValueError("请先将待整理记录保存为会议，再归档")
        key, value = fields[action]
        db.executemany(
            "UPDATE meeting_meta SET " + key + "=? WHERE mid=?",
            [(value, mid) for mid in ids],
        )
    return dict(changed=len(ids))


def listing(
    q="",
    view="meetings",
    status="",
    source="",
    since=None,
    until=None,
    sort="updated",
    page=1,
    page_size=20,
):
    conditions = []
    args = []
    if view == "trash":
        conditions.append("x.trashed_at IS NOT NULL")
    else:
        conditions.append("x.trashed_at IS NULL")
        if view == "inbox":
            conditions.append("x.draft=1")
        elif view == "archived":
            conditions.extend(["x.archived=1", "x.draft=0"])
        elif view == "pinned":
            conditions.append("x.pinned=1")
        elif view == "meetings":
            conditions.extend(["x.draft=0", "x.archived=0"])
    for field, value in [("m.status", status), ("x.source", source)]:
        if value:
            conditions.append(field + "=?")
            args.append(value)
    if since:
        conditions.append("julianday(m.created)>=julianday(?)")
        args.append(since)
    if until:
        conditions.append("julianday(m.created)<julianday(?)")
        args.append(until)
    order = {
        "updated": "julianday(x.updated) DESC",
        "created": "julianday(m.created) DESC",
        "oldest": "julianday(m.created) ASC",
        "title": "m.title COLLATE NOCASE",
    }[sort]
    with store.connection() as db:
        totals = db.execute(
            """SELECT COALESCE(SUM(trashed_at IS NULL AND draft=0 AND archived=0),0),
          COALESCE(SUM(trashed_at IS NULL AND draft=1),0),COALESCE(SUM(trashed_at IS NULL AND draft=0 AND archived=1),0),
          COALESCE(SUM(trashed_at IS NULL AND pinned=1),0),COALESCE(SUM(trashed_at IS NOT NULL),0) FROM meeting_meta"""
        ).fetchone()
        counts = dict(zip(("meetings", "inbox", "archived", "pinned", "trash"), totals))
        rows = db.execute(
            """SELECT m.id,m.title,m.created,m.status,m.error,m.result,m.summary,x.*,
          v.body AS document_body,(SELECT SUM(duration) FROM audio_assets WHERE mid=m.id) AS audio_seconds
          FROM meetings m JOIN meeting_meta x ON x.mid=m.id
          LEFT JOIN document_heads h ON h.mid=m.id LEFT JOIN document_versions v ON v.mid=h.mid AND v.revision=h.revision
          WHERE """
            + " AND ".join(conditions)
            + " ORDER BY x.pinned DESC,"
            + order
            + ",m.id",
            args,
        ).fetchall()
    items = []
    needle = q.strip().casefold()
    for row in rows:
        item = dict(row)
        tags = json.loads(item["tags"])
        raw_body = item.pop("document_body")
        raw_result = item.pop("result")
        body = json.loads(raw_body or raw_result or "null") or {}
        summary = json.loads(item.pop("summary") or "null")
        text = "\n".join(s.get("text", "") for s in body.get("segments", []))
        summary_text = summary.get("text", "") if isinstance(summary, dict) else ""
        if (
            needle
            and needle
            not in (
                item["title"] + " " + " ".join(tags) + " " + text + " " + summary_text
            ).casefold()
        ):
            continue
        item.pop("template", None)
        item.pop("mid", None)
        item["tags"] = tags
        for key in ("draft", "archived", "pinned"):
            item[key] = bool(item[key])
        item["preview"] = text[:180]
        items.append(item)
    total = len(items)
    return dict(
        items=items[(page - 1) * page_size : page * page_size],
        total=total,
        page=page,
        page_size=page_size,
        counts=counts,
    )
