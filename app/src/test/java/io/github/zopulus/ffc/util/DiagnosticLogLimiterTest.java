package io.github.zopulus.ffc.util;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class DiagnosticLogLimiterTest {
    @Test public void repeatedSuccessIsMergedUntilExactBoundary() {
        DiagnosticLogLimiter limiter = new DiagnosticLogLimiter();
        assertEquals("unfreeze app", limiter.filter("unfreeze app", 100));
        assertNull(limiter.filter("unfreeze app", 101));
        assertNull(limiter.filter("unfreeze app", 30_099));
        assertEquals("unfreeze app [suppressed 2 diagnostic lines since last output]",
                limiter.filter("unfreeze app", 30_100));
        assertEquals("another app", limiter.filter("another app", 30_100));
    }

    @Test public void differentPackagesAndStagesKeepTheirFirstMessage() {
        DiagnosticLogLimiter limiter = new DiagnosticLogLimiter();
        assertNotNull(limiter.filter("window app.a", 0));
        assertNotNull(limiter.filter("unfreeze app.a", 0));
        assertNotNull(limiter.filter("window app.b", 0));
        assertNull(limiter.filter("window app.a", 1));
    }

    @Test public void burstBudgetBoundsUniqueMessagesAndRecovers() {
        DiagnosticLogLimiter limiter = new DiagnosticLogLimiter();
        for (int i = 0; i < DiagnosticLogLimiter.MAX_BURST; i++) {
            assertNotNull(limiter.filter("stage " + i, 0));
        }
        assertNull(limiter.filter("overflow", 4999));
        assertEquals("overflow [suppressed 1 diagnostic lines since last output]",
                limiter.filter("overflow", 5000));
        assertEquals("new stage", limiter.filter("new stage", 5000));
    }

    @Test public void suppressedMessagesDoNotConsumeTheBurstBudget() {
        DiagnosticLogLimiter limiter = new DiagnosticLogLimiter();
        limiter.filter("one", 0);
        for (int i = 0; i < 1000; i++) assertNull(limiter.filter("one", 1));
        for (int i = 1; i < DiagnosticLogLimiter.MAX_BURST; i++) {
            assertNotNull(limiter.filter("unique " + i, 1));
        }
    }

    @Test public void storedKeysRemainBoundedUnderUniqueMessageChurn() {
        DiagnosticLogLimiter limiter = new DiagnosticLogLimiter();
        for (int i = 0; i < 1000; i++) {
            assertNotNull(limiter.filter("unique " + i, i * 5000L));
            assertTrue(limiter.trackedKeys() <= DiagnosticLogLimiter.MAX_KEYS);
        }
        assertEquals(DiagnosticLogLimiter.MAX_KEYS, limiter.trackedKeys());
    }

    @Test public void longMessagesAreBoundedAndNullIsSafe() {
        DiagnosticLogLimiter limiter = new DiagnosticLogLimiter();
        String large = "a".repeat(10_000);
        String output = limiter.filter(large, 0);
        assertEquals(DiagnosticLogLimiter.MAX_MESSAGE_LENGTH + " [truncated]".length(), output.length());
        assertNull(limiter.filter(large, 1));
        assertTrue(limiter.filter(null, 1).startsWith("null"));
    }

    @Test public void staleConcurrentClockCannotReopenAWindow() {
        DiagnosticLogLimiter limiter = new DiagnosticLogLimiter();
        assertNotNull(limiter.filter("one", 60_000));
        assertNull(limiter.filter("one", 0));
        assertNull(limiter.filter("one", 89_999));
        assertNotNull(limiter.filter("one", 90_000));
    }

    @Test public void concurrentDuplicatesEmitOnlyOnce() throws Exception {
        DiagnosticLogLimiter limiter = new DiagnosticLogLimiter();
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger emitted = new AtomicInteger();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                    if (limiter.filter("same", 100) != null) emitted.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            threads.add(thread);
            thread.start();
        }
        start.countDown();
        for (Thread thread : threads) thread.join();
        assertEquals(1, emitted.get());
        assertTrue(limiter.filter("same", 30_100).contains("suppressed 15 diagnostic lines"));
    }
}
