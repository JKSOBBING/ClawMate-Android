"""Templates HTTP routes using explicit application services."""

from fastapi import APIRouter, HTTPException

from ..persistence import metadata as metadata_store, templates as template_store
from .common import WorkspaceAccess, guard
from .schemas import Apply, Template


def router(services):
    router = APIRouter(prefix="/api/v1", tags=["templates"])
    access = WorkspaceAccess(services)
    exists = access.exists
    mutate = access.mutate

    def validate_template(body):
        if not body.name.strip():
            raise HTTPException(422, "模板名称不能为空")
        if body.refinement_profile_id:
            guard(lambda: services.refinement.snapshot(body.refinement_profile_id))

    @router.get("/templates")
    def templates():
        return template_store.templates()

    @router.post("/templates", status_code=201)
    def create_template(body: Template):
        validate_template(body)
        return template_store.save_template(body.model_dump())

    @router.put("/templates/{tid}")
    def edit_template(tid: str, body: Template):
        validate_template(body)
        return guard(lambda: template_store.save_template(body.model_dump(), tid))

    @router.delete("/templates/{tid}")
    def delete_template(tid: str):
        guard(lambda: template_store.delete_template(tid))
        return dict(ok=True)

    @router.get("/meetings/{mid}/settings")
    def settings(mid: str):
        exists(mid)
        return metadata_store.metadata(mid)

    @router.put("/meetings/{mid}/settings")
    def apply_template(mid: str, body: Apply):
        mutate(mid)
        template = guard(lambda: template_store.get_template(body.template_id))
        if template["refinement_profile_id"]:
            guard(
                lambda: services.refinement.snapshot(template["refinement_profile_id"])
            )
        return metadata_store.set_metadata(mid, dict(template=template))

    return router
