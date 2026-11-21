"""Compatibility facade for workspace persistence. New code can import domain repositories."""

from .persistence.common import now, encode
from .persistence.schema import initialize
from .persistence.metadata import metadata, set_metadata, list_meetings
from .persistence.documents import (
    body_from_result,
    _current,
    fingerprint,
    document,
    _append,
    capture_model,
    reset_document,
    begin_audio,
    recover_recordings,
    save_document,
    versions,
    restore,
)
from .persistence.templates import (
    templates,
    get_template,
    save_template,
    delete_template,
    BUILTINS,
)
from .persistence.tasks import JobCancelled, Job, task, tasks, cancel
