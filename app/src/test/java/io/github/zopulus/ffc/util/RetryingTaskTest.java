package io.github.zopulus.ffc.util;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class RetryingTaskTest {
    private static final class Entry implements RetryingTask.Ticket {
        final Runnable action;
        final long delay;
        boolean cancelled;
        Entry(Runnable action, long delay) { this.action = action; this.delay = delay; }
        public void cancel() { cancelled = true; }
    }
    private static final class Scheduler implements RetryingTask.Scheduler {
        final List<Entry> entries = new ArrayList<>();
        public RetryingTask.Ticket schedule(Runnable action, long delay) {
            Entry entry = new Entry(action, delay);
            entries.add(entry);
            return entry;
        }
        void run(int index) { entries.get(index).action.run(); }
    }
    @Test public void transientFailureRetriesAndStopsOnSuccess() {
        Scheduler scheduler = new Scheduler();
        AtomicInteger calls = new AtomicInteger();
        RetryingTask task = new RetryingTask(scheduler, () -> calls.incrementAndGet() == 2, 1000, 3000);
        task.request();
        scheduler.run(0);
        assertEquals(1000, scheduler.entries.get(1).delay);
        scheduler.run(1);
        assertEquals(2, calls.get());
        assertEquals(2, scheduler.entries.size());
    }
    @Test public void permanentFailureIsBoundedAndFreshRequestCanRecover() {
        Scheduler scheduler = new Scheduler();
        AtomicInteger calls = new AtomicInteger();
        RetryingTask task = new RetryingTask(scheduler, () -> { calls.incrementAndGet(); return false; }, 1000, 3000);
        task.request();
        scheduler.run(0); scheduler.run(1); scheduler.run(2);
        assertEquals(3, calls.get());
        assertEquals(3, scheduler.entries.size());
        task.request(); scheduler.run(3);
        assertEquals(4, calls.get());
        assertEquals(1000, scheduler.entries.get(4).delay);
    }
    @Test public void oldRetryCannotRunAfterNewRequestEvenIfCancellationRaces() {
        Scheduler scheduler = new Scheduler();
        AtomicInteger calls = new AtomicInteger();
        RetryingTask task = new RetryingTask(scheduler, () -> { calls.incrementAndGet(); return false; }, 1000);
        task.request(); scheduler.run(0);
        task.request();
        assertTrue(scheduler.entries.get(1).cancelled);
        scheduler.run(1);
        assertEquals(1, calls.get());
        scheduler.run(2);
        assertEquals(2, calls.get());
    }
    @Test public void inFlightOldFailureCannotScheduleRetryForNewRequest() {
        Scheduler scheduler = new Scheduler();
        AtomicInteger calls = new AtomicInteger();
        RetryingTask[] task = new RetryingTask[1];
        task[0] = new RetryingTask(scheduler, () -> {
            if (calls.incrementAndGet() == 1) task[0].request();
            return false;
        }, 1000);
        task[0].request(); scheduler.run(0);
        assertEquals(2, scheduler.entries.size());
        assertEquals(0, scheduler.entries.get(1).delay);
        scheduler.run(1);
        assertEquals(3, scheduler.entries.size());
        assertEquals(1000, scheduler.entries.get(2).delay);
    }
}
