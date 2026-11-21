"""Schema initialization and startup recovery coordination."""

from .. import store, audio_store as audio
from .common import now


def initialize():
    with store.connection() as db:
        db.executescript(
            """
        CREATE TABLE IF NOT EXISTS meeting_meta(mid TEXT PRIMARY KEY, tags TEXT NOT NULL DEFAULT '[]', archived INTEGER NOT NULL DEFAULT 0, template TEXT);
        CREATE TABLE IF NOT EXISTS document_versions(mid TEXT,revision INTEGER,origin TEXT,created TEXT,body TEXT,PRIMARY KEY(mid,revision));
        CREATE TABLE IF NOT EXISTS document_heads(mid TEXT PRIMARY KEY,revision INTEGER);
        CREATE TABLE IF NOT EXISTS workspace_templates(id TEXT PRIMARY KEY,body TEXT);
        CREATE TABLE IF NOT EXISTS workspace_tasks(id TEXT PRIMARY KEY,mid TEXT,kind TEXT,status TEXT,stage TEXT,progress REAL,args TEXT,error TEXT,created TEXT,updated TEXT);
        """
        )
        db.execute(
            "UPDATE workspace_tasks SET status='interrupted',error='服务重启，可重试',updated=? WHERE status IN ('queued','running','cancel_requested')",
            (now(),),
        )
    audio.initialize()
    from .. import history_store

    history_store.initialize()
