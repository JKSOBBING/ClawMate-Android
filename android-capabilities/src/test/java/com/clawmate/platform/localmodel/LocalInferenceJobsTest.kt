package com.clawmate.platform.localmodel

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class LocalInferenceJobsTest {
    private val question = """[{"role":"user","content":"你好"}]"""

    @Test fun cancelKeepsModelBusyUntilNativeUnwindsAndDropsLateText() {
        val wrote = CountDownLatch(1)
        val release = CountDownLatch(1)
        LocalInferenceJobs(::File) { _, messages, threads, cancelled, emit ->
            assertEquals("你好", messages.last().second)
            assertEquals(2, threads)
            emit("你")
            wrote.countDown()
            release.await(5, TimeUnit.SECONDS)
            assertTrue(cancelled())
            emit("迟到")
        }.use { jobs ->
            jobs.start("first", "fixture", question, 2)
            assertTrue(wrote.await(5, TimeUnit.SECONDS))
            assertEquals("你", jobs.poll("first").text)
            assertFalse(jobs.cancel("other"))
            assertTrue(jobs.cancel("first"))
            assertTrue(jobs.uses("fixture"))
            assertThrows(IllegalStateException::class.java) { jobs.start("new", "fixture", question, 2) }
            release.countDown()
            awaitFinished(jobs)
            assertEquals(LocalInferenceJobs.Snapshot("cancelled", "你"), jobs.poll("first"))
        }
    }

    @Test fun errorsAreVisibleAndRetryUsesFreshTranscript() {
        var attempt = 0
        LocalInferenceJobs(::File) { _, _, _, _, emit ->
            if (attempt++ == 0) error("不支持的架构")
            emit("你"); emit("好")
        }.use { jobs ->
            assertThrows(IllegalArgumentException::class.java) {
                jobs.start("malformed", "fixture", """[{"role":"tool","content":"test"}]""", 1)
            }
            jobs.start("first", "fixture", question, 1)
            awaitFinished(jobs)
            assertEquals("不支持的架构", jobs.poll("first").error)
            jobs.start("retry", "fixture", question, 1)
            awaitFinished(jobs)
            assertEquals(LocalInferenceJobs.Snapshot("done", "你好"), jobs.poll("retry"))
            assertEquals("error", jobs.poll("first").state)
        }
    }

    private fun awaitFinished(jobs: LocalInferenceJobs) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (jobs.uses("fixture") && System.nanoTime() < end) Thread.sleep(2)
        assertFalse("worker did not return", jobs.uses("fixture"))
    }
}
