"""Templates persistence; no HTTP or model dependencies."""

from .. import store
from .common import encode
import json, uuid

BUILTINS = [
    dict(
        id="default",
        name="通用会议",
        refinement_profile_id=None,
        auto_refine=True,
        summary_instructions="",
        builtin=True,
    ),
    dict(
        id="standup",
        name="项目周会",
        refinement_profile_id=None,
        auto_refine=True,
        summary_instructions="按进展、问题、决定、待办组织纪要；负责人和期限缺失时写未提及。",
        builtin=True,
    ),
]


def templates():
    with store.connection() as db:
        return BUILTINS + [
            json.loads(r["body"])
            for r in db.execute("SELECT body FROM workspace_templates ORDER BY id")
        ]


def get_template(tid):
    for t in templates():
        if t["id"] == tid:
            return t
    raise ValueError("模板不存在")


def save_template(body, tid=None):
    if tid in {t["id"] for t in BUILTINS}:
        raise ValueError("内置模板可复制，不能修改或删除")
    if tid:
        get_template(tid)
    t = dict(body, id=tid or "template-" + uuid.uuid4().hex[:12], builtin=False)
    with store.connection() as db:
        db.execute(
            "INSERT OR REPLACE INTO workspace_templates VALUES (?,?)",
            (t["id"], encode(t)),
        )
    return t


def delete_template(tid):
    if get_template(tid)["builtin"]:
        raise ValueError("不能删除内置模板")
    with store.connection() as db:
        db.execute("DELETE FROM workspace_templates WHERE id=?", (tid,))
