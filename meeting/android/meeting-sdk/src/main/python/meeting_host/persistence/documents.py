"""Documents persistence; no HTTP or model dependencies."""

from .. import store, audio_store as audio
from .common import encode, now
import hashlib, json


def body_from_result(result):
    return dict(
        segments=(result or {}).get("segments", []),
        speaker_names={},
        audio_id=(result or {}).get("audio_id"),
    )


def _current(db, mid):
    row = db.execute(
        "SELECT v.* FROM document_heads h JOIN document_versions v ON v.mid=h.mid AND v.revision=h.revision WHERE h.mid=?",
        (mid,),
    ).fetchone()
    if row:
        return dict(
            revision=row["revision"],
            origin=row["origin"],
            created=row["created"],
            **json.loads(row["body"])
        )
    row = db.execute("SELECT result FROM meetings WHERE id=?", (mid,)).fetchone()
    if not row:
        raise KeyError(mid)
    body = body_from_result(json.loads(row["result"]) if row["result"] else None)
    if not body["segments"] and not body["audio_id"]:
        body["audio_id"] = audio.active_id(mid, db)
    return dict(revision=0, origin="model", created=None, **body)


def fingerprint(body):
    return hashlib.sha256(
        encode([body["segments"], body["speaker_names"], body.get("audio_id")]).encode()
    ).hexdigest()


def document(mid):
    with store.connection() as db:
        body = _current(db, mid)
    body["source_hash"] = fingerprint(body)
    body["transcript"] = "\n".join(
        "["
        + body["speaker_names"].get(
            s.get("speaker", "unknown"), s.get("speaker", "unknown")
        )
        + "] "
        + s["text"]
        for s in body["segments"]
    )
    return body


def _append(db, mid, body, origin, activate=True):
    revision = db.execute(
        "SELECT COALESCE(MAX(revision),0)+1 FROM document_versions WHERE mid=?", (mid,)
    ).fetchone()[0]
    db.execute(
        "INSERT INTO document_versions VALUES (?,?,?,?,?)",
        (mid, revision, origin, now(), encode(body)),
    )
    if activate:
        db.execute(
            "INSERT OR REPLACE INTO document_heads VALUES (?,?)", (mid, revision)
        )
    return revision


def capture_model(mid, result):
    with store.connection() as db:
        db.execute("BEGIN IMMEDIATE")
        current = _current(db, mid)
        _append(
            db, mid, body_from_result(result), "model", current["origin"] != "manual"
        )


def reset_document(mid):
    # A deliberate new recording/upload starts a new document, retaining old versions.
    with store.connection() as db:
        db.execute("BEGIN IMMEDIATE")
        if db.execute("SELECT 1 FROM document_heads WHERE mid=?", (mid,)).fetchone():
            _append(
                db,
                mid,
                dict(segments=[], speaker_names={}, audio_id=audio.active_id(mid, db)),
                "model",
            )


def begin_audio(mid, aid):
    with store.connection() as db:
        db.execute("BEGIN IMMEDIATE")
        asset = db.execute(
            "SELECT path FROM audio_assets WHERE id=? AND mid=?", (aid, mid)
        ).fetchone()
        if not asset:
            raise ValueError("音频版本不存在")
        current = _current(db, mid)
        if current["revision"] == 0 and current["segments"]:
            _append(
                db,
                mid,
                dict(
                    segments=current["segments"],
                    speaker_names=current["speaker_names"],
                    audio_id=current.get("audio_id"),
                ),
                "model",
                False,
            )
        db.execute("INSERT OR REPLACE INTO audio_heads VALUES (?,?)", (mid, aid))
        _append(db, mid, dict(segments=[], speaker_names={}, audio_id=aid), "model")
        db.execute(
            "UPDATE meetings SET audio=?,result=NULL,summary=NULL,error=NULL WHERE id=?",
            (asset["path"], mid),
        )


def recover_recordings():
    for mid, result in audio.recover_pending():
        capture_model(mid, result)
        store.update(
            mid,
            status="interrupted",
            result=result,
            summary=None,
            error="录音中断，已保留写入的音频，可恢复后整理",
        )


def save_document(mid, revision, source_hash, segments, names):
    with store.connection() as db:
        db.execute("BEGIN IMMEDIATE")
        current = _current(db, mid)
        if current["revision"] != revision or fingerprint(current) != source_hash:
            raise ValueError("正文已更新，请重新载入后编辑")
        if not segments:
            raise ValueError("暂无可编辑正文")
        if len(segments) != len(current["segments"]):
            raise ValueError("本版编辑保持原始段落数量和时间位置")
        allowed = {s.get("speaker", "unknown") for s in current["segments"]} | {
            "unknown"
        }
        merged = []
        for original, edit in zip(current["segments"], segments):
            if edit["speaker"] not in allowed:
                raise ValueError("请选择当前会议已有说话人")
            row = dict(original, text=edit["text"], speaker=edit["speaker"])
            if edit["speaker"] != original.get("speaker"):
                row["speaker_status"] = "manual"
            merged.append(row)
        if not set(names) <= allowed:
            raise ValueError("姓名对应的说话人不存在")
        # Preserve pre-edit content as a restorable initial version.
        if current["revision"] == 0:
            _append(
                db,
                mid,
                dict(
                    segments=current["segments"],
                    speaker_names={},
                    audio_id=current.get("audio_id"),
                ),
                "model",
                False,
            )
        _append(
            db,
            mid,
            dict(
                segments=merged, speaker_names=names, audio_id=current.get("audio_id")
            ),
            "manual",
        )
        db.execute("UPDATE meetings SET summary=NULL WHERE id=?", (mid,))
    return document(mid)


def versions(mid):
    with store.connection() as db:
        return [
            dict(
                revision=r["revision"],
                origin=r["origin"],
                created=r["created"],
                audio_id=json.loads(r["body"]).get("audio_id"),
            )
            for r in db.execute(
                "SELECT revision,origin,created,body FROM document_versions WHERE mid=? ORDER BY revision DESC",
                (mid,),
            )
        ]


def restore(mid, revision, base_revision):
    with store.connection() as db:
        db.execute("BEGIN IMMEDIATE")
        if _current(db, mid)["revision"] != base_revision:
            raise ValueError("正文已更新，请刷新版本列表")
        row = db.execute(
            "SELECT body FROM document_versions WHERE mid=? AND revision=?",
            (mid, revision),
        ).fetchone()
        if not row:
            raise ValueError("版本不存在")
        body = json.loads(row["body"])
        aid = body.get("audio_id")
        if aid:
            asset = db.execute(
                "SELECT path FROM audio_assets WHERE id=? AND mid=?", (aid, mid)
            ).fetchone()
            if not asset:
                raise ValueError("关联音频不存在")
            db.execute("INSERT OR REPLACE INTO audio_heads VALUES (?,?)", (mid, aid))
            db.execute("UPDATE meetings SET audio=? WHERE id=?", (asset["path"], mid))
        _append(db, mid, body, "manual")
        db.execute("UPDATE meetings SET summary=NULL WHERE id=?", (mid,))
    return document(mid)
