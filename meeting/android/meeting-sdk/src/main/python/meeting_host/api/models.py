"""Models HTTP routes using explicit application services."""

import numpy as np
from fastapi import APIRouter, HTTPException

from ..refinement_provider import candidate as refinement_candidate
from .schemas import RefinementProfileRequest, RefinementSettingsRequest


def router(services):
    router = APIRouter(prefix="/api/v1", tags=["models"])

    @router.get("/refinement")
    def refinement_options():
        return services.refinement.view()

    @router.put("/refinement")
    def refinement_settings(body: RefinementSettingsRequest):
        try:
            return services.refinement.settings(body.active_id, body.auto_enabled)
        except ValueError as e:
            raise HTTPException(422, str(e))

    @router.post("/refinement/profiles", status_code=201)
    def refinement_create(body: RefinementProfileRequest):
        try:
            return services.refinement.save(body.model_dump())
        except ValueError as e:
            raise HTTPException(422, str(e))

    @router.put("/refinement/profiles/{profile_id}")
    def refinement_edit(profile_id: str, body: RefinementProfileRequest):
        try:
            return services.refinement.save(body.model_dump(), profile_id)
        except ValueError as e:
            raise HTTPException(422, str(e))

    @router.delete("/refinement/profiles/{profile_id}")
    def refinement_delete(profile_id: str):
        try:
            return services.refinement.delete(profile_id)
        except ValueError as e:
            raise HTTPException(422, str(e))

    @router.post("/refinement/profiles/{profile_id}/test")
    async def refinement_test(profile_id: str):
        try:
            profile = services.refinement.snapshot(profile_id)
            if profile["kind"] == "local_firered":
                await services.infer_refinement(services.engine.firered)
            elif profile["kind"] == "local_qwen":
                await services.infer(services.engine.qwen_asr)
            elif profile["kind"] == "local_sensevoice":
                await services.infer(services.engine.sensevoice)
            else:
                await services.infer(
                    refinement_candidate,
                    profile,
                    np.zeros(16000, dtype=np.float32),
                    "接口连接测试。",
                    services.engine,
                )
            return dict(
                ok=True,
                message=(
                    "模型已就绪"
                    if profile.get("builtin")
                    else "接口调用成功（测试样本，不含会议内容）"
                ),
            )
        except ValueError as e:
            raise HTTPException(400, str(e))
        except Exception:
            raise HTTPException(400, "模型测试未通过，请检查模型文件或接口配置")

    return router
