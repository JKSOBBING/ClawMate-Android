"""Immutable audio takes, durable PCM checkpoints and interrupted-take recovery."""

import json
import os
import struct
import time
import uuid
import wave
from pathlib import Path
from datetime import datetime, timezone
from . import store


def now():
    return datetime.now(timezone.utc).isoformat()


def initialize():
    with store.connection() as db:
        db.executescript(
            """
        CREATE TABLE IF NOT EXISTS audio_assets(id TEXT PRIMARY KEY,mid TEXT,path TEXT,created TEXT,
            source TEXT,state TEXT,duration REAL DEFAULT 0,checkpoint TEXT,error TEXT);
        CREATE TABLE IF NOT EXISTS audio_heads(mid TEXT PRIMARY KEY,audio_id TEXT);
        """
        )
        # Only the active legacy document can be associated with the remaining old file.
        for meeting in db.execute(
            "SELECT id,audio,status,result FROM meetings"
        ).fetchall():
            head = db.execute(
                "SELECT audio_id FROM audio_heads WHERE mid=?", (meeting["id"],)
            ).fetchone()
            legacy = None
            if not head and meeting["audio"] and Path(meeting["audio"]).is_file():
                legacy = uuid.uuid5(
                    uuid.NAMESPACE_URL, "meeting-audio:" + meeting["id"]
                ).hex
                state = (
                    "interrupted" if meeting["status"] == "interrupted" else "completed"
                )
                try:
                    with wave.open(meeting["audio"]) as wav:
                        duration = wav.getnframes() / wav.getframerate()
                except (OSError, wave.Error, EOFError):
                    duration = 0
                db.execute(
                    "INSERT OR IGNORE INTO audio_assets VALUES (?,?,?,?,?,?,?,?,?)",
                    (
                        legacy,
                        meeting["id"],
                        meeting["audio"],
                        now(),
                        "legacy",
                        state,
                        duration,
                        meeting["result"],
                        None,
                    ),
                )
                db.execute(
                    "INSERT OR IGNORE INTO audio_heads VALUES (?,?)",
                    (meeting["id"], legacy),
                )
            elif head:
                legacy = head["audio_id"]
            current = db.execute(
                "SELECT revision FROM document_heads WHERE mid=?", (meeting["id"],)
            ).fetchone()
            for row in db.execute(
                "SELECT revision,body FROM document_versions WHERE mid=?",
                (meeting["id"],),
            ).fetchall():
                body = json.loads(row["body"])
                if "audio_id" not in body:
                    body["audio_id"] = (
                        legacy
                        if current and row["revision"] == current["revision"]
                        else None
                    )
                    db.execute(
                        "UPDATE document_versions SET body=? WHERE mid=? AND revision=?",
                        (
                            json.dumps(body, ensure_ascii=False),
                            meeting["id"],
                            row["revision"],
                        ),
                    )
            if meeting["result"]:
                result = json.loads(meeting["result"])
                if isinstance(result, dict) and "audio_id" not in result:
                    result["audio_id"] = legacy
                    db.execute(
                        "UPDATE meetings SET result=? WHERE id=?",
                        (json.dumps(result, ensure_ascii=False), meeting["id"]),
                    )


def active_id(mid, db=None):
    if db is None:
        with store.connection() as connection:
            return active_id(mid, connection)
    row = db.execute("SELECT audio_id FROM audio_heads WHERE mid=?", (mid,)).fetchone()
    return row["audio_id"] if row else None


def allocate(mid, source):
    aid = uuid.uuid4().hex
    path = store.ROOT / "audio" / mid / (aid + ".wav")
    path.parent.mkdir(parents=True, exist_ok=True)
    with store.connection() as db:
        db.execute(
            "INSERT INTO audio_assets VALUES (?,?,?,?,?,?,?,?,?)",
            (aid, mid, str(path), now(), source, "preparing", 0, None, None),
        )
    return aid, path


def path(mid, aid=None):
    aid = aid or active_id(mid)
    with store.connection() as db:
        row = db.execute(
            "SELECT path FROM audio_assets WHERE mid=? AND id=?", (mid, aid)
        ).fetchone()
    if not row:
        raise ValueError("此正文没有可确认的关联音频")
    return Path(row["path"])


def info(mid, aid):
    with store.connection() as db:
        row = db.execute(
            "SELECT * FROM audio_assets WHERE mid=? AND id=?", (mid, aid)
        ).fetchone()
    if not row:
        raise ValueError("音频版本不存在")
    result = dict(row)
    file = Path(result.pop("path"))
    result.pop("checkpoint")
    result["available"] = file.is_file() and result["duration"] > 0
    result["recoverable"] = result["state"] == "interrupted" and result["available"]
    return result


def listing(mid):
    with store.connection() as db:
        ids = [
            r["id"]
            for r in db.execute(
                "SELECT id FROM audio_assets WHERE mid=? ORDER BY created DESC", (mid,)
            )
        ]
    return [info(mid, aid) for aid in ids]


def update(aid, state, duration=None, checkpoint=None, error=None):
    fields = dict(state=state, error=error)
    if duration is not None:
        fields["duration"] = duration
    if checkpoint is not None:
        fields["checkpoint"] = json.dumps(checkpoint, ensure_ascii=False)
    with store.connection() as db:
        db.execute(
            "UPDATE audio_assets SET "
            + ",".join(k + "=?" for k in fields)
            + " WHERE id=?",
            [*fields.values(), aid],
        )


def repair_pcm(file):
    # Our recorder uses a canonical 44-byte PCM header. Never guess other formats.
    with Path(file).open("r+b") as raw:
        header = raw.read(44)
        if (
            len(header) != 44
            or header[:4] != b"RIFF"
            or header[8:12] != b"WAVE"
            or header[36:40] != b"data"
        ):
            raise ValueError("录音文件头不完整，无法自动恢复")
        if (
            struct.unpack_from("<HHI", header, 20) != (1, 1, 16000)
            or struct.unpack_from("<H", header, 34)[0] != 16
        ):
            raise ValueError("录音格式不支持自动恢复")
        size = max(0, os.fstat(raw.fileno()).st_size - 44) // 2 * 2
        raw.seek(4)
        raw.write(struct.pack("<I", size + 36))
        raw.seek(40)
        raw.write(struct.pack("<I", size))
        raw.flush()
        os.fsync(raw.fileno())
    return size / 32000


def recover_pending():
    with store.connection() as db:
        rows = db.execute(
            "SELECT * FROM audio_assets WHERE state IN ('recording','paused','finalizing','preparing','interrupted')"
        ).fetchall()
    recovered = []
    for row in rows:
        try:
            duration = repair_pcm(row["path"])
            checkpoint = (
                json.loads(row["checkpoint"])
                if row["checkpoint"]
                else dict(segments=[])
            )
            if not isinstance(checkpoint, dict):
                checkpoint = dict(segments=[])
            already_recovered = (
                row["state"] == "interrupted"
                and checkpoint.get("recording_interrupted")
                and duration == row["duration"]
            )
            checkpoint.update(audio_id=row["id"], recording_interrupted=True)
            checkpoint.setdefault("metrics", {})["audio_seconds"] = duration
            update(
                row["id"],
                "interrupted",
                duration,
                checkpoint,
                "录音中断，已保留写入的音频",
            )
            if active_id(row["mid"]) == row["id"] and not already_recovered:
                recovered.append((row["mid"], checkpoint))
        except OSError:
            update(row["id"], "unavailable", error="录音文件不存在或无法读取")
        except ValueError as error:
            update(row["id"], "unavailable", error=str(error))
    return recovered


class CaptureWriter:
    def __init__(self, mid, aid, file):
        self.mid = mid
        self.aid = aid
        self.count = 0
        self.last_sync = 0.0
        self.closed = False
        self.raw = Path(file).open("w+b")
        self.wav = wave.open(self.raw, "wb")
        self.wav.setparams((1, 2, 16000, 0, "NONE", "not compressed"))
        self.checkpoint(dict(segments=[], audio_id=aid), "recording", True)

    def write(self, data):
        self.wav.writeframesraw(data)
        self.count += len(data) // 2

    def read_samples(self, start, end):
        """Read a flushed, closed speech span without moving the recording cursor."""
        if not 0 <= start < end <= self.count:
            raise ValueError('Invalid recorded speech span')
        with Path(self.raw.name).open('rb') as source:
            source.seek(44 + start * 2)
            data = source.read((end - start) * 2)
        if len(data) != (end - start) * 2:
            raise ValueError('Recorded speech span has not been flushed')
        import numpy as np
        return np.frombuffer(data, dtype='<i2').astype(np.float32) / 32768

    def checkpoint(self, result, state="recording", force=False):
        if not force and time.monotonic() - self.last_sync < 1:
            return
        self.wav.writeframes(b"")
        self.raw.flush()
        os.fsync(self.raw.fileno())
        update(self.aid, state, self.count / 16000, result)
        self.last_sync = time.monotonic()

    def close(self, result, state):
        if self.closed:
            return
        try:
            self.checkpoint(result, state, True)
        finally:
            try:
                self.wav.close()
            finally:
                try:
                    self.raw.close()
                finally:
                    self.closed = True
