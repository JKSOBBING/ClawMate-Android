package local.meetingnotes.sdk

import org.junit.Assert.*
import org.junit.Test

class EventSubscriptionsTest {
    @Test fun closingPageDropsQueuedEventsAndPreservesOtherSubscribers() {
        val queue = ArrayDeque<() -> Unit>()
        val events = EventSubscriptions({ queue.add(it) }, { throw it })
        val oldPage = mutableListOf<String>()
        val newPage = mutableListOf<String>()
        val old = events.subscribe { oldPage.add(it) }
        events.publish("final")
        old.close()
        old.close()
        events.subscribe { newPage.add(it) }
        events.publish("refined")
        while(queue.isNotEmpty()) queue.removeFirst().invoke()
        assertTrue(oldPage.isEmpty())
        assertEquals(listOf("refined"), newPage)
    }

    @Test fun brokenListenerDoesNotInterruptCaptureOrOtherPages() {
        val failures = mutableListOf<Exception>()
        val received = mutableListOf<String>()
        val events = EventSubscriptions({ it() }, { failures.add(it) })
        events.subscribe { error("page destroyed") }
        events.subscribe { received.add(it) }
        events.publish("done")
        assertEquals(listOf("done"), received)
        assertEquals(1, failures.size)
    }
}
