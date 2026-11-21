"""Fetch the reviewed public frontend files, preserving their pinned Git blob IDs."""
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
from pathlib import Path
import sys
import time
from urllib.parse import quote
from urllib.request import urlopen


def fetch(entry):
    name = entry["path"]
    target = staging / name
    target.resolve().relative_to(staging)
    url = "https://raw.githubusercontent.com/doulujiyao12/mobiinfra-oh/{}/{}".format(
        entry["ref"], quote(name))
    for attempt in range(3):
        try:
            with urlopen(url, timeout=45) as response:
                content = response.read(8 * 1024 * 1024 + 1)
            break
        except OSError:
            if attempt == 2:
                raise
            time.sleep(2 ** attempt)
    if len(content) > 8 * 1024 * 1024:
        raise ValueError("Oversized source: " + name)
    header = b"blob %d\0" % len(content)
    if hashlib.sha1(header + content).hexdigest() != entry["gitBlob"]:
        raise ValueError("Pinned blob mismatch: " + name)
    target.parent.mkdir(parents=True, exist_ok=True)
    with target.open("xb") as stream:
        stream.write(content)


if __name__ == "__main__":
    staging = Path(sys.argv[1]).resolve(strict=True)
    with ThreadPoolExecutor(max_workers=10) as workers:
        list(workers.map(fetch, json.load(sys.stdin)))
