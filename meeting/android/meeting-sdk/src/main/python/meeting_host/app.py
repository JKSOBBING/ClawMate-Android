"""Application composition. Start with uvicorn meeting_host.app:app."""

from contextlib import asynccontextmanager
import asyncio
from contextlib import suppress
from fastapi import FastAPI
from .runtime import MeetingRuntime
from .api import install
from .api.schemas import ProcessRequest


def create_app(runtime=None):
    services = runtime if runtime is not None else MeetingRuntime()

    @asynccontextmanager
    async def lifespan(app):
        services.initialize()
        monitor = asyncio.create_task(services.idle_monitor()) if getattr(services, "idle_seconds", 0) else None
        try:
            yield
        finally:
            if monitor:
                monitor.cancel()
                with suppress(asyncio.CancelledError):
                    await monitor
            services.close()

    app = FastAPI(title="Meeting Notes Host", version="1.0.0", lifespan=lifespan)
    app.state.runtime = services
    install(app, services)
    return app


app = create_app()
# Keep the historical Python settings access used by integration scripts.
refinement = app.state.runtime.refinement
