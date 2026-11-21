"""Shared serialization and UTC timestamps for repositories."""

import json
from datetime import datetime, timezone


def now():
    return datetime.now(timezone.utc).isoformat()


def encode(value):
    return json.dumps(value, ensure_ascii=False)
