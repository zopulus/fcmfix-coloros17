package io.github.zopulus.ffc.util;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public class DiagnosticLoggerTest {
    @Test public void localLogIsWrittenBeforeDeferredRelay() {
        List<String> local = new ArrayList<>();
        List<String> remote = new ArrayList<>();
        List<Runnable> tasks = new ArrayList<>();
        List<Throwable> errors = new ArrayList<>();
        DiagnosticLogger logger = new DiagnosticLogger(local::add, errors::add, tasks::add);
        logger.log("trusted", true, 0, remote::add);
        assertEquals(List.of("trusted"), local);
        assertTrue(remote.isEmpty());
        assertEquals(1, tasks.size());
        tasks.get(0).run();
        assertEquals(local, remote);
        assertTrue(errors.isEmpty());
    }

    @Test public void duplicatesDoNotWriteLocallyOrQueueAnotherBroadcast() {
        List<String> local = new ArrayList<>();
        List<Runnable> tasks = new ArrayList<>();
        List<Throwable> errors = new ArrayList<>();
        DiagnosticLogger logger = new DiagnosticLogger(local::add, errors::add, tasks::add);
        logger.log("success", true, 0, ignored -> {});
        logger.log("success", true, 1, ignored -> {});
        assertEquals(1, local.size());
        assertEquals(1, tasks.size());
        assertTrue(errors.isEmpty());
    }

    @Test public void importantLogsBypassDiagnosticLimitsAndRelayQueue() {
        List<String> local = new ArrayList<>();
        List<Runnable> tasks = new ArrayList<>();
        List<Throwable> errors = new ArrayList<>();
        DiagnosticLogger logger = new DiagnosticLogger(local::add, errors::add, tasks::add);
        for (int i = 0; i < 100; i++) logger.log("success " + i, true, 0, ignored -> {});
        int before = local.size();
        for (int i = 0; i < 100; i++) logger.log("config failure", false, 0, ignored -> {});
        assertEquals(before + 100, local.size());
        assertEquals(DiagnosticLogLimiter.MAX_BURST, tasks.size());
        assertTrue(errors.isEmpty());
    }

    @Test public void missingContextStillKeepsLocalDiagnostics() {
        List<String> local = new ArrayList<>();
        List<Runnable> tasks = new ArrayList<>();
        List<Throwable> errors = new ArrayList<>();
        DiagnosticLogger logger = new DiagnosticLogger(local::add, errors::add, tasks::add);
        logger.log("early boot", true, 0, null);
        assertEquals(List.of("early boot"), local);
        assertTrue(tasks.isEmpty());
        assertTrue(errors.isEmpty());
    }

    @Test public void rejectedQueueKeepsLocalLogsAndReportsFailureOnlyOnce() {
        List<String> local = new ArrayList<>();
        AtomicInteger failures = new AtomicInteger();
        DiagnosticLogger logger = new DiagnosticLogger(local::add, e -> failures.incrementAndGet(),
                task -> { throw new RejectedExecutionException("full"); });
        for (int i = 0; i < 10; i++) logger.log("success " + i, true, 0, ignored -> fail());
        assertEquals(10, local.size());
        assertEquals(1, failures.get());
    }

    @Test public void relayFailureDoesNotThrowOrEraseTheLocalLog() {
        List<String> local = new ArrayList<>();
        AtomicInteger failures = new AtomicInteger();
        DiagnosticLogger logger = new DiagnosticLogger(local::add, e -> failures.incrementAndGet(), Runnable::run);
        logger.log("success", true, 0, ignored -> { throw new SecurityException("denied"); });
        logger.log("later", true, 1, ignored -> {});
        assertEquals(List.of("success", "later"), local);
        assertEquals(1, failures.get());
    }

    @Test public void brokenLogSinksCannotEscapeIntoHook() {
        DiagnosticLogger logger = new DiagnosticLogger(
                ignored -> { throw new IllegalStateException("local sink"); },
                ignored -> { throw new IllegalStateException("failure sink"); }, Runnable::run);
        logger.log("important", false, 0, null);
        logger.log("diagnostic", true, 1, ignored -> { throw new IllegalStateException("relay"); });
    }

    @Test public void productionRelayRunsOnOwnThreadWithoutCallerThreadLocal() throws Exception {
        ThreadLocal<String> callerState = new ThreadLocal<>();
        callerState.set("caller-only");
        AtomicReference<String> threadName = new AtomicReference<>();
        AtomicReference<String> inherited = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        AtomicInteger failures = new AtomicInteger();
        DiagnosticLogger logger = new DiagnosticLogger(ignored -> {}, e -> failures.incrementAndGet());
        logger.log("one", true, 0, ignored -> {
            threadName.set(Thread.currentThread().getName());
            inherited.set(callerState.get());
            done.countDown();
        });
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals("FCMFix-log", threadName.get());
        assertNull(inherited.get());
        assertEquals("caller-only", callerState.get());
        assertEquals(0, failures.get());
        callerState.remove();
    }

    @Test public void productionQueueIsBoundedAndNeverRunsRelayOnCaller() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(17); // One in-flight plus sixteen queued.
        AtomicInteger local = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();
        AtomicInteger wrongThread = new AtomicInteger();
        DiagnosticLogger logger = new DiagnosticLogger(ignored -> local.incrementAndGet(),
                ignored -> failures.incrementAndGet());
        java.util.function.Consumer<String> relay = ignored -> {
            if (!"FCMFix-log".equals(Thread.currentThread().getName())) wrongThread.incrementAndGet();
            entered.countDown();
            try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            completed.countDown();
        };
        try {
            logger.log("first", true, 0, relay);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            for (int i = 0; i < 31; i++) logger.log("next " + i, true, 0, relay);
            assertEquals(32, local.get());
            assertEquals(1, failures.get());
        } finally {
            release.countDown();
        }
        assertTrue(completed.await(5, TimeUnit.SECONDS));
        assertEquals(0, wrongThread.get());
    }
}
