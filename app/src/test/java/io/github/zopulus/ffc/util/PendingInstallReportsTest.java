package io.github.zopulus.ffc.util;

import org.junit.Test;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class PendingInstallReportsTest {
    private final ArrayDeque<Runnable> queue = new ArrayDeque<>();
    private final PendingInstallReports reports = new PendingInstallReports((task, delay) -> {
        queue.add(task);
        return () -> queue.remove(task);
    });

    @Test public void exhaustedReportIsRetriedExactlyOnceOnRecovery() {
        AtomicInteger attempts = new AtomicInteger();
        reports.installed("app", () -> attempts.incrementAndGet() == 5);
        for (int i = 0; i < 3; i++) queue.remove().run();
        reports.recovered();
        reports.recovered();
        assertEquals(1, queue.size());
        queue.remove().run();
        assertEquals(5, attempts.get());
        reports.recovered();
        assertTrue(queue.isEmpty());
    }

    @Test public void parkedReportRecoversEvenWithoutAConnectionCallback() {
        AtomicInteger attempts = new AtomicInteger();
        reports.installed("app", () -> attempts.incrementAndGet() == 6);
        while (!queue.isEmpty() && attempts.get() < 7) queue.remove().run();
        assertEquals(6, attempts.get());
        assertTrue(queue.isEmpty());
    }

    @Test public void uninstallAndReinstallDiscardTheOlderEvent() {
        AtomicInteger old = new AtomicInteger();
        reports.installed("app", () -> { old.incrementAndGet(); return false; });
        Runnable staleRetry = queue.peek();
        reports.removed("app");
        reports.installed("app", () -> true);
        staleRetry.run();
        reports.recovered();
        assertEquals(1, old.get());
        assertTrue(queue.isEmpty());
    }
}
