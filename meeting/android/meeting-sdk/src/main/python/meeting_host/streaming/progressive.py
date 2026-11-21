"""One background consumer; queued work holds only IDs, never recording PCM."""
import asyncio
import time


class ProgressiveRefiner:
    def __init__(self, infer, decode, read_samples, publish):
        self.infer = infer
        self.decode = decode
        self.read_samples = read_samples
        self.publish = publish
        self.queue = asyncio.Queue()
        self.stats = dict(submitted=0, completed=0, failed=0, processing_seconds=0.0)
        self.task = asyncio.create_task(self.run())

    def submit(self, segment_id, start, end):
        self.stats['submitted'] += 1
        self.queue.put_nowait((segment_id, start, end))

    def decode_span(self, start, end):
        return self.decode(self.read_samples(start, end))

    async def run(self):
        while True:
            work = await self.queue.get()
            if work is None:
                return
            segment_id, start, end = work
            began = time.perf_counter()
            try:
                text = await self.infer(self.decode_span, start, end)
                if not isinstance(text, str) or not any(c.isalnum() for c in text):
                    raise ValueError('Empty refinement output')
                update = dict(text=text.strip(), firered_text=text.strip(), refinement_status='refined')
            except asyncio.CancelledError:
                raise
            except Exception:
                self.stats['failed'] += 1
                update = dict(refinement_status='failed')
            seconds = time.perf_counter() - began
            self.stats['completed'] += 1
            self.stats['processing_seconds'] += seconds
            await self.publish(segment_id, dict(update, refinement_seconds=seconds))

    async def finish(self):
        self.queue.put_nowait(None)
        await self.task

    async def cancel(self):
        self.task.cancel()
        try:
            await self.task
        except (asyncio.CancelledError, Exception):
            pass
