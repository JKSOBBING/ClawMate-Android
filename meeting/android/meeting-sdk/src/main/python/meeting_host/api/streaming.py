"""WebSocket mode selection; implementations share one runtime."""

from typing import Literal
from fastapi import APIRouter, WebSocket
from ..live_sensevoice import dual_stream
from ..streaming.paraformer import paraformer_stream


def router(services):
    router = APIRouter(prefix="/api/v1", tags=["audio"])

    @router.websocket("/meetings/{mid}/stream")
    async def stream(
        ws: WebSocket,
        mid: str,
        compare_qwen: bool = False,
        mode: Literal["paraformer", "sensevoice_dual"] = "sensevoice_dual",
    ):
        await ws.accept()
        if mode == "sensevoice_dual":
            return await dual_stream(ws, mid, services)
        return await paraformer_stream(ws, mid, services, compare_qwen)

    return router
