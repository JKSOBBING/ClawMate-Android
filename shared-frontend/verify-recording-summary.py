"""Regression cases from OPPO recording acceptance, independent of model weights."""
import importlib.util
import sys
from pathlib import Path
from types import ModuleType

sys.dont_write_bytecode = True
sdk = ModuleType("meeting_host.engine")
sdk.Engine = object
sys.modules[sdk.__name__] = sdk
path = Path(__file__).resolve().parents[1] / "gradle/meeting-python/clawmate_engine.py"
spec = importlib.util.spec_from_file_location("meeting_host.clawmate_engine", path)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

quote = "开放时间早上9点至下午5点。"
assert module.Engine().summary("[speaker_01] " + quote)["text"] == "原文摘录\n" + quote
line = "小李负责统计借阅数量，小王在周五之前整理借阅清单。"
mixed = {"actionItems": [{"负责人": "小李", "期限": "周五", "任务": "整理借阅清单"}],
         "decisions": ["小王同意在周五之后整理清单。"], "openQuestions": []}
assert module.grounded_lists(mixed, [line])["actionItems"] == [line]
assert mixed["decisions"] == []
print("PASS short recording quote and original owner/deadline attribution")
