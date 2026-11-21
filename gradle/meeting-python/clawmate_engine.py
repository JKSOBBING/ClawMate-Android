"""Keep commitments in generated meeting notes grounded in the original speech."""
import json
import re

from .engine import Engine as SdkEngine


def source_lines(transcript):
    lines = (re.sub(r"^\[speaker_\d+\]\s*", "", line.strip()) for line in transcript.splitlines())
    return [line for line in lines if line]


def grounded_lists(summary, lines):
    # Preserve the sentence linking an owner to their own assignment and deadline.
    # A model can swap these fields even when all three words appear in the same meeting.
    sentences = [part.strip() for line in lines for part in re.split(r"(?<=[。！？!?])", line) if part.strip()]
    def exact_quotes(items):
        if not isinstance(items, list):
            return []
        requested = {item.strip().rstrip("。.!！?？") for item in items if isinstance(item, str)}
        return [line for line in sentences if line.rstrip("。.!！?？") in requested][:12]

    actions = exact_quotes(summary.get("actionItems", []))
    for line in sentences:
        if re.search(r"负责|需要|请.{1,24}(?:于|在|前|完成|提交|归还)|(?:之前|前)整理|交给|再核对", line) and line not in actions:
            actions.append(line)
    summary["actionItems"] = actions[:12]
    summary["decisions"] = exact_quotes(summary.get("decisions", []))
    summary["openQuestions"] = exact_quotes(summary.get("openQuestions", []))
    return summary


class Engine(SdkEngine):
    def summary(self, transcript, instructions="", job=None):
        lines = source_lines(transcript)
        if len(lines) == 1 and len(lines[0]) <= 80:
            if job:
                job.check()
            return {"text": "原文摘录\n" + lines[0], "engine": "verbatim", "input_chunks": 1}
        result = super().summary(transcript, instructions, job)
        if "actionItems" not in instructions:
            return result
        text = result["text"].strip()
        text = re.sub(r"^```(?:json)?\s*|\s*```$", "", text, flags=re.I)
        try:
            structured = json.loads(text)
            if not isinstance(structured, dict) or not isinstance(structured.get("overview"), str) or not structured["overview"].strip():
                raise ValueError("Missing overview")
            result["text"] = json.dumps(grounded_lists(structured, lines), ensure_ascii=False)
        except (TypeError, ValueError):
            result["text"] = "模型未返回完整纪要，以下为原文摘录\n" + "\n".join(lines[:4])
        return result
