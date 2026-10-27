package rocks.pkb.plektra.core.suggestions

import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OrderedPersistenceQueueTest {
    @Test fun blockedWriterAcceptsLaterUpdatesAndFlushKeepsTheirOrder() {
        val queue = OrderedPersistenceQueue("ordered-test")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val order = Collections.synchronizedList(mutableListOf<Int>())
        try {
            queue.enqueue { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); order.add(1) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            queue.enqueue { order.add(2); completed.countDown() }
            assertFalse(completed.await(25, TimeUnit.MILLISECONDS))
            release.countDown()
            queue.flush()
            assertEquals(listOf(1, 2), order.toList())
        } finally { release.countDown(); queue.flush(); queue.closeWhenDrained() }
    }

    @Test fun failedAcceptedUpdateIsRetriedOnceWithoutDuplicatingItsSuccess() {
        val queue = OrderedPersistenceQueue("retry-test")
        val failed = CountDownLatch(1)
        val attempts = AtomicInteger()
        val persisted = AtomicInteger()
        try {
            queue.enqueue {
                if (attempts.incrementAndGet() == 1) {
                    failed.countDown()
                    throw IOException("synthetic disk failure")
                }
                persisted.incrementAndGet()
            }
            assertTrue(failed.await(5, TimeUnit.SECONDS))
            queue.flush()
            queue.flush()
            assertEquals(2, attempts.get())
            assertEquals(1, persisted.get())
        } finally { queue.flush(); queue.closeWhenDrained() }
    }

    @Test fun closingDoesNotCancelUpdatesAcceptedWhileTheDiskWasBlocked() {
        val queue = OrderedPersistenceQueue("close-test")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val complete = CountDownLatch(2)
        try {
            queue.enqueue { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); complete.countDown() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            queue.enqueue { complete.countDown() }
            queue.closeWhenDrained()
            release.countDown()
            assertTrue(complete.await(5, TimeUnit.SECONDS))
            queue.flush()
        } finally { release.countDown(); queue.closeWhenDrained() }
    }
}
