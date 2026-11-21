"""Health, capability discovery and local web assets."""

import os, platform
from pathlib import Path
from fastapi import APIRouter
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles
from .capabilities import CAPABILITIES

WEB_ROOT = Path(__file__).resolve().parents[1] / "web"


class NoCacheStaticFiles(StaticFiles):
    async def get_response(self, path, scope):
        response = await super().get_response(path, scope)
        response.headers["Cache-Control"] = "no-store"
        return response


def install(app, services):
    router = APIRouter()

    @router.get("/api/v1/runtime/status")
    def runtime_status():
        summary = getattr(services.engine, "summary_runtime", None)
        return dict(v=1, active=len(services.active), loaded_models=services.engine.loaded_models(),
                    summary=summary.status() if summary else dict(loaded=False, managed=False),
                    model_idle_seconds=services.idle_seconds)

    @router.post("/api/v1/runtime/release")
    async def release_models():
        await services.release_models(force=True)
        return dict(ok=True)

    @router.get("/")
    def console():
        return FileResponse(
            services.ROOT / "console.html",
            headers={
                "Cache-Control": "no-store, no-cache, must-revalidate",
                "Pragma": "no-cache",
                "Expires": "0",
            },
        )

    @router.get("/api/v1/capabilities")
    def capabilities():
        return CAPABILITIES

    @router.get("/api/v1/health")
    def health():
        expected = [
            "streaming/encoder.int8.onnx",
            "streaming/decoder.int8.onnx",
            "streaming/tokens.txt",
            "sensevoice/model.int8.onnx",
            "sensevoice/tokens.txt",
            "silero_vad.onnx",
            "campplus.onnx",
            "sherpa-onnx-pyannote-segmentation-3-0/model.onnx",
            "qwen.gguf",
            "qwen-asr/conv_frontend.onnx",
            "qwen-asr/encoder.int8.onnx",
            "qwen-asr/decoder.int8.onnx",
            "qwen-asr/tokenizer/vocab.json",
            "qwen-asr/tokenizer/merges.txt",
        ]
        missing = [p for p in expected if not (services.MODEL / p).is_file()]
        return dict(
            status="ready" if not missing else "models_missing",
            missing_models=missing,
            protocol=dict(sample_rate=16000, channels=1, format="pcm_s16le", version=1),
            queued_jobs=len(services.active),
            capabilities=dict(
                offline_models=["qwen3_asr", "sensevoice"],
                qwen_live_mode="vad_segmented",
                qwen_live_max_speech_seconds=8,
                progressive_refinement=dict(model="firered2", available=services.engine.firered_available(), threads=2),
            ),
            runtime=dict(
                device=os.getenv("MEETING_DEVICE_LABEL", "本机"),
                platform=platform.system(),
                architecture=platform.machine(),
                python=platform.python_version(),
                inference_provider="cpu",
                pid=os.getpid(),
            ),
        )

    app.include_router(router)
    app.mount("/assets", NoCacheStaticFiles(directory=WEB_ROOT), name="assets")
    # Legacy asset URLs remain usable for an existing WebView or debugging tool.
    aliases = {
        "/workspace-ui.js": "js/workspace.js",
        "/history-ui.js": "js/history.js",
        "/appearance-ui.js": "js/appearance.js",
        "/workspace.css": "styles/workspace.css",
    }

    def asset_handler(relative):
        def serve():
            media = "text/css" if relative.endswith(".css") else "text/javascript"
            return FileResponse(
                WEB_ROOT / relative,
                media_type=media,
                headers={"Cache-Control": "no-store"},
            )

        return serve

    for url, relative in aliases.items():
        app.add_api_route(
            url, asset_handler(relative), methods=["GET"], include_in_schema=False
        )
