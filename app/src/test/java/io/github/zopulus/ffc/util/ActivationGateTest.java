package io.github.zopulus.ffc.util;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.concurrent.*;
public class ActivationGateTest {
    @Test public void timedOutQueuedTaskIsRemovedAndNeverStarted() throws Exception {
        ThreadPoolExecutor worker = new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(2));
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean ran = new java.util.concurrent.atomic.AtomicBoolean();
        worker.execute(() -> { entered.countDown(); try { release.await(); } catch (InterruptedException ignored) {} });
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertFalse(ActivationGate.await(worker, () -> { ran.set(true); return true; }, 50));
            assertEquals(0, worker.getQueue().size());
            release.countDown();
            assertTrue(ActivationGate.await(worker, () -> true, 1000));
            assertFalse(ran.get());
        } finally { release.countDown(); worker.shutdownNow(); }
    }
    @Test public void successAndFailureAreReturned() {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            assertTrue(ActivationGate.await(worker, () -> true, 1000));
            assertFalse(ActivationGate.await(worker, () -> false, 1000));
            assertFalse(ActivationGate.await(worker, () -> { throw new Exception("denied"); }, 1000));
        } finally { worker.shutdownNow(); }
    }
    @Test public void blockedActivationTimesOutAndDoesNotReplayWork() {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        CountDownLatch blocked = new CountDownLatch(1);
        try {
            assertFalse(ActivationGate.await(worker, () -> { blocked.await(); return true; }, 50));
            assertTrue(ActivationGate.await(worker, () -> true, 1000));
        } finally { blocked.countDown(); worker.shutdownNow(); }
    }
    @Test public void rejectedQueueDoesNotBlockDelivery() {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        worker.shutdown();
        assertFalse(ActivationGate.await(worker, () -> true, 1000));
    }
}
