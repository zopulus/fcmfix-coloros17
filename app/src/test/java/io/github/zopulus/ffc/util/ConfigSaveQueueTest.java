package io.github.zopulus.ffc.util;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
import java.util.concurrent.*;
public class ConfigSaveQueueTest {
    @Test public void acknowledgedCallWaitsForEarlierWritesAndItsOwnCommit() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1),
                callStarted = new CountDownLatch(1);
        ConfigSaveQueue.submit(() -> {
            entered.countDown();
            try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        ExecutorService caller = Executors.newSingleThreadExecutor();
        java.util.concurrent.atomic.AtomicBoolean committed = new java.util.concurrent.atomic.AtomicBoolean();
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            Future<Boolean> reply = caller.submit(() -> {
                callStarted.countDown();
                return ConfigSaveQueue.call(() -> { committed.set(true); return true; });
            });
            assertTrue(callStarted.await(2, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> reply.get(100, TimeUnit.MILLISECONDS));
            assertFalse(committed.get());
            assertFalse(reply.isDone());
            release.countDown();
            assertTrue(reply.get(2, TimeUnit.SECONDS));
            assertTrue(committed.get());
        } finally { release.countDown(); caller.shutdownNow(); }
    }

    @Test public void failedCommitIsReportedToCaller() {
        java.io.IOException failure = new java.io.IOException("disk full");
        assertSame(failure, assertThrows(java.io.IOException.class,
                () -> ConfigSaveQueue.call(() -> { throw failure; })));
    }

    @Test public void nestedCallOnWriterDoesNotDeadlock() throws Exception {
        assertEquals("saved", ConfigSaveQueue.call(() -> ConfigSaveQueue.call(() -> "saved")));
    }

    @Test public void writesRunSeriallyAndInSubmissionOrder() throws Exception {
        List<Integer> order = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), finished = new CountDownLatch(1);
        ConfigSaveQueue.submit(() -> { entered.countDown(); try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } order.add(1); });
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            ConfigSaveQueue.submit(() -> { order.add(2); finished.countDown(); });
            assertTrue(order.isEmpty());
        } finally { release.countDown(); }
        assertTrue(finished.await(2, TimeUnit.SECONDS));
        assertEquals(Arrays.asList(1,2), order);
    }
}
