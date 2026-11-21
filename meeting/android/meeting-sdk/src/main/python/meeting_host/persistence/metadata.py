"""Metadata persistence; no HTTP or model dependencies."""

from .. import store
from .common import encode
import json


def metadata(mid):
    with store.connection() as db:
        r = db.execute("SELECT * FROM meeting_meta WHERE mid=?", (mid,)).fetchone()
    return dict(
        tags=json.loads(r["tags"]) if r else [],
        archived=bool(r["archived"]) if r else False,
        template=json.loads(r["template"]) if r and r["template"] else None,
        draft=bool(r["draft"]) if r else False,
        source=r["source"] if r else "manual",
        pinned=bool(r["pinned"]) if r else False,
        trashed_at=r["trashed_at"] if r else None,
        updated=r["updated"] if r else None,
    )


def set_metadata(mid, values):
    with store.connection() as db:
        db.execute("INSERT OR IGNORE INTO meeting_meta(mid) VALUES (?)", (mid,))
        if "title" in values:
            db.execute("UPDATE meetings SET title=? WHERE id=?", (values["title"], mid))
        for key in ("tags", "archived", "template"):
            if key in values:
                value = (
                    encode(values[key])
                    if key in ("tags", "template")
                    else int(values[key])
                )
                db.execute(
                    "UPDATE meeting_meta SET " + key + "=? WHERE mid=?", (value, mid)
                )
    return metadata(mid)


def list_meetings(q="", archived=False):
    from .. import history_store

    # Preserve the legacy array contract; paginated clients use /history.
    return history_store.listing(
        q, view="archived" if archived else "meetings", sort="created", page_size=200
    )["items"]
