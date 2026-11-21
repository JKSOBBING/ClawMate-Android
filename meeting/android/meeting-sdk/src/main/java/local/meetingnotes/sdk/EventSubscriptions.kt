package local.meetingnotes.sdk

import java.io.Closeable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/** Queued callbacks check cancellation again, so a detached page is never called. */
internal class EventSubscriptions(
    private val dispatch: (() -> Unit) -> Unit,
    private val onFailure: (Exception) -> Unit,
) {
    private class Subscription(val listener: (String) -> Unit) {
        val active = AtomicBoolean(true)
    }

    private val subscriptions = CopyOnWriteArrayList<Subscription>()

    fun subscribe(listener: (String) -> Unit): Closeable {
        val subscription = Subscription(listener)
        subscriptions.add(subscription)
        return Closeable {
            subscription.active.set(false)
            subscriptions.remove(subscription)
        }
    }

    fun publish(event: String) {
        for (subscription in subscriptions) dispatch {
            if (subscription.active.get()) {
                try {
                    subscription.listener(event)
                } catch (error: Exception) {
                    onFailure(error)
                }
            }
        }
    }
}
