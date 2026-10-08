package io.github.zopulus.ffc.util;

import java.util.function.BooleanSupplier;

/** Coalesces requests and invalidates retries from an older request. */
public final class RetryingTask {
    public interface Ticket { void cancel(); }
    public interface Scheduler { Ticket schedule(Runnable task, long delayMs); }
    private final Scheduler scheduler;
    private final BooleanSupplier task;
    private final long[] delays;
    private long generation;
    private int failures;
    private Ticket pending;
    public RetryingTask(Scheduler scheduler, BooleanSupplier task, long... delays) {
        this.scheduler = scheduler;
        this.task = task;
        this.delays = delays.clone();
    }
    public synchronized void request() {
        if (pending != null) pending.cancel();
        long token = ++generation;
        failures = 0;
        pending = scheduler.schedule(() -> run(token), 0);
    }
    private void run(long token) {
        synchronized (this) { if (token != generation) return; }
        boolean success = task.getAsBoolean();
        synchronized (this) {
            if (token != generation) return;
            pending = null;
            if (!success && failures < delays.length) {
                long delay = delays[failures++];
                pending = scheduler.schedule(() -> run(token), delay);
            }
        }
    }
}
