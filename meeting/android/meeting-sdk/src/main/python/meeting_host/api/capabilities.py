"""Capability catalogue consumed by the UI and contract validation."""

CAPABILITIES = dict(
    version=1,
    groups=[
        dict(
            id="meetings",
            name="会议管理",
            anchor="meeting-workspace",
            operations=[
                "GET /meetings",
                "POST /meetings",
                "PATCH /meetings/{id}",
                "POST /inbox",
                "POST /meetings/{id}/promote",
                "GET /history",
                "POST /history/batch",
            ],
        ),
        dict(
            id="audio",
            name="录音与音频",
            anchor="audio-workspace",
            operations=[
                "WS /meetings/{id}/stream",
                "POST /meetings/{id}/audio",
                "GET /meetings/{id}/audio",
                "GET /meetings/{id}/audio/versions",
                "POST /meetings/{id}/audio/{audio_id}/recover",
            ],
        ),
        dict(
            id="documents",
            name="正文与版本",
            anchor="document-workspace",
            operations=[
                "GET /meetings/{id}/document",
                "PUT /meetings/{id}/document",
                "GET /meetings/{id}/versions",
                "POST /meetings/{id}/versions/restore",
                "GET /meetings/{id}/export.md",
            ],
        ),
        dict(
            id="tasks",
            name="后台任务",
            anchor="task-workspace",
            operations=[
                "GET /tasks",
                "GET /tasks/{id}",
                "POST /tasks/{id}/cancel",
                "POST /tasks/{id}/retry",
                "POST /meetings/{id}/process",
                "POST /meetings/{id}/summary",
            ],
        ),
        dict(
            id="templates",
            name="场景模板",
            anchor="template-workspace",
            operations=[
                "GET /templates",
                "POST /templates",
                "PUT /templates/{id}",
                "DELETE /templates/{id}",
                "GET /meetings/{id}/settings",
                "PUT /meetings/{id}/settings",
            ],
        ),
        dict(
            id="models",
            name="模型配置",
            anchor="refinement-settings",
            operations=[
                "GET /refinement",
                "PUT /refinement",
                "POST /refinement/profiles",
                "PUT /refinement/profiles/{id}",
                "DELETE /refinement/profiles/{id}",
                "POST /refinement/profiles/{id}/test",
            ],
        ),
    ],
)
