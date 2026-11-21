"""Download groups and readiness; weights remain outside APKs."""

GROUPS = {
    "basic": ("sensevoice/model.int8.onnx", "sensevoice/tokens.txt", "silero_vad.onnx"),
    "speakers": ("campplus.onnx", "sherpa-onnx-pyannote-segmentation-3-0/model.onnx"),
    "firered": ("firered2/encoder.int8.onnx", "firered2/decoder.int8.onnx", "firered2/tokens.txt"),
    "qwen_asr": ("qwen-asr/conv_frontend.onnx", "qwen-asr/encoder.int8.onnx", "qwen-asr/decoder.int8.onnx",
                 "qwen-asr/tokenizer/vocab.json", "qwen-asr/tokenizer/merges.txt"),
    "paraformer": ("streaming/encoder.int8.onnx", "streaming/decoder.int8.onnx", "streaming/tokens.txt"),
    "summary": ("qwen.gguf",),
}


def group_for(path):
    path = path.replace("\\", "/").removeprefix("models/")
    for group, expected in GROUPS.items():
        if path in expected:
            return group
    if "segmentation" in path:
        return "speakers"
    if path.startswith("qwen-asr"):
        return "qwen_asr"
    if path.startswith("firered"):
        return "firered"
    return None


def group_status(model_dir, downloads=()):
    return [dict(id=key, ready=not (missing := [p for p in files if not (model_dir / p).is_file()]),
                 missing=missing, downloadable=any(group_for(item["path"]) == key for item in downloads))
            for key, files in GROUPS.items()]
