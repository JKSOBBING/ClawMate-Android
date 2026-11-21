"""Immutable text handoff. Meeting storage remains the only writer of source data."""

import hashlib
import json
import uuid
from . import store
from .persistence import documents


def initialize():
    with store.connection() as db:
        db.execute("CREATE TABLE IF NOT EXISTS integration_identity (id INTEGER PRIMARY KEY CHECK(id=1), value TEXT NOT NULL)")
        db.execute("INSERT OR IGNORE INTO integration_identity VALUES (1,?)", (uuid.uuid4().hex,))


def export(mid, revision, source_hash, selection="both"):
    if selection not in ("transcript", "summary", "both"):
        raise ValueError("请选择正文或纪要")
    with store.connection() as db:
        db.execute("BEGIN")
        row = db.execute("SELECT title,created,status,summary FROM meetings WHERE id=?", (mid,)).fetchone()
        if row is None:
            raise KeyError(mid)
        if row["status"] in ("recording", "processing", "summarizing"):
            raise ValueError("会议仍在处理中，请完成后再导入")
        body = documents._current(db, mid)
        fingerprint = documents.fingerprint(body)
        if body["revision"] != revision or fingerprint != source_hash:
            raise ValueError("正文已更新，请重新查看并确认导入")
        transcript = "\n".join("[" + body["speaker_names"].get(s.get("speaker", "unknown"), s.get("speaker", "unknown"))
                               + "] " + s["text"] for s in body["segments"])
        summary = json.loads(row["summary"]) if row["summary"] else None
        if not summary or summary.get("source_hash") != fingerprint:
            summary = None
        if selection == "summary" and not summary:
            raise ValueError("当前正文没有可确认来源的纪要，请重新生成")
        if not transcript.strip():
            raise ValueError("没有可导入的正文")
        text = transcript if selection in ("both", "transcript") else ""
        if summary and selection in ("both", "summary"):
            text += ("\n\n" if text else "") + "纪要\n" + summary["text"]
        meta = db.execute("SELECT tags FROM meeting_meta WHERE mid=?", (mid,)).fetchone()
        identity = db.execute("SELECT value FROM integration_identity WHERE id=1").fetchone()[0]
        payload = dict(v=1, source=dict(namespace="on-device-meeting-notes", instance_id=identity,
                       meeting_id=mid, audio_id=body.get("audio_id"), revision=revision, source_hash=fingerprint),
                       title=row["title"], created=row["created"], tags=json.loads(meta["tags"]) if meta else [],
                       selection=selection, text=text, summary_included=bool(summary and selection != "transcript"))
    raw = json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return dict(v=1, export_id=hashlib.sha256(raw.encode()).hexdigest(), payload=raw)
