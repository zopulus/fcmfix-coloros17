package io.github.zopulus.ffc.util;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
import java.util.concurrent.*;
public class ConfigSaveQueueTest {
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
