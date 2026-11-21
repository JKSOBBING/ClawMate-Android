"""API composition: each router owns one public capability group."""

from . import meetings, audio, documents, templates, tasks, models, streaming, system


def install(app, services):
    for module in (models, meetings, audio, documents, templates, tasks, streaming):
        app.include_router(module.router(services))
    system.install(app, services)
