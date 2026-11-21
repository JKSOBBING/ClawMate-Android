"""Build the Chaquopy application archive and its asset manifest from checked-in inputs."""
import hashlib
import json
from pathlib import Path
import sys
import zipfile


def package(module, engine, output):
    source = module / "src/main/python"
    assets = module / "src/main/assets/chaquopy"
    output.mkdir(parents=True, exist_ok=True)
    files = {p.relative_to(source).as_posix(): p for p in source.rglob("*")
             if p.is_file() and p.suffix in {".py", ".css", ".html", ".js"}}
    files["meeting_host/clawmate_engine.py"] = engine
    archive = output / "app.imy"
    with zipfile.ZipFile(archive, "w") as bundle:
        for name, path in sorted(files.items()):
            entry = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            entry.create_system = 3
            entry.external_attr = 0o100644 << 16
            bundle.writestr(entry, path.read_bytes(), compress_type=zipfile.ZIP_DEFLATED)
    config = json.loads((module / "runtime-config.json").read_text(encoding="utf-8"))
    inputs = {p.relative_to(assets).as_posix(): p for p in assets.rglob("*") if p.is_file()}
    inputs[archive.name] = archive
    config["assets"] = {name: hashlib.sha1(path.read_bytes()).hexdigest()
                        for name, path in sorted(inputs.items())}
    (output / "build.json").write_bytes((json.dumps(config, indent=2) + "\n").encode())


if __name__ == "__main__":
    package(*(Path(arg) for arg in sys.argv[1:]))
