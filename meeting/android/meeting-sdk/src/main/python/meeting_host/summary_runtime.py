"""A leased, on-demand local llama process. No Android or inference imports."""

from contextlib import contextmanager
import os
from pathlib import Path
import subprocess
import threading
import time
import urllib.request


class SummaryRuntime:
    def __init__(self, native, model, logs, port=18081, threads=4, idle_seconds=60,
                 process_factory=subprocess.Popen, clock=time.monotonic, probe=None):
        self.native, self.model, self.logs = Path(native), Path(model), Path(logs)
        self.port, self.threads, self.idle_seconds = port, threads, idle_seconds
        self._factory, self._clock = process_factory, clock
        self._probe = probe or self._health
        self._lock = threading.RLock()
        self._process = None
        self._users = 0
        self._last_used = clock()
        self._closed = False

    def _health(self):
        try:
            with urllib.request.urlopen(f"http://127.0.0.1:{self.port}/health", timeout=1) as response:
                return response.status == 200
        except OSError:
            return False

    def _start(self):
        if self._closed:
            raise RuntimeError("本地摘要运行器已关闭")
        if self._process and self._process.poll() is None:
            return
        if not self.model.is_file():
            raise ValueError("摘要模型尚未准备，请下载摘要模型组")
        self.logs.mkdir(parents=True, exist_ok=True)
        environment = dict(os.environ, LD_LIBRARY_PATH=str(self.native))
        environment.pop("GGML_BACKEND_PATH", None)
        with (self.logs / "llama.log").open("ab") as log:
            self._process = self._factory([
                str(self.native / "libmeeting_llama.so"), "-m", str(self.model),
                "--host", "127.0.0.1", "--port", str(self.port), "-c", "8192",
                "-t", str(self.threads), "-ngl", "0"], env=environment,
                cwd=self.logs.parent, stdout=log, stderr=log)
        deadline = time.monotonic() + 120
        while not self._probe():
            if self._process.poll() is not None:
                raise RuntimeError("本地摘要启动失败，请检查 llama.log")
            if time.monotonic() >= deadline:
                self._stop()
                raise RuntimeError("本地摘要启动超时")
            time.sleep(.2)

    @contextmanager
    def session(self):
        with self._lock:
            self._start()
            self._users += 1
        try:
            yield
        finally:
            with self._lock:
                self._users -= 1
                self._last_used = self._clock()

    def _stop(self):
        if self._process and self._process.poll() is None:
            self._process.terminate()
            try:
                self._process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self._process.kill(); self._process.wait(timeout=5)
        self._process = None

    def release(self, force=False):
        with self._lock:
            if self._users:
                return False
            if force or self._clock() - self._last_used >= self.idle_seconds:
                self._stop()
                return True
            return False

    def status(self):
        # Reading status never waits for model startup or an inference lease.
        process = self._process
        return dict(loaded=bool(process and process.poll() is None), active=self._users,
                    idle_seconds=self.idle_seconds)

    def close(self):
        with self._lock:
            self._closed = True
            self._stop()
