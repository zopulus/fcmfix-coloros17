package io.github.zopulus.ffc.util;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Local logs first; optional remote diagnostics are bounded, best-effort and non-blocking. */
public final class DiagnosticLogger {
    private final DiagnosticLogLimiter limiter = new DiagnosticLogLimiter();
    private final Executor executor;
    private final Consumer<String> localLog;
    private final Consumer<Throwable> failureLog;
    private final AtomicBoolean failureReported = new AtomicBoolean();

    public DiagnosticLogger(Consumer<String> localLog, Consumer<Throwable> failureLog) {
        this(localLog, failureLog, newExecutor());
    }

    DiagnosticLogger(Consumer<String> localLog, Consumer<Throwable> failureLog, Executor executor) {
        this.localLog = localLog;
        this.failureLog = failureLog;
        this.executor = executor;
    }

    private static Executor newExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(16), task -> {
                    Thread thread = new Thread(task, "FCMFix-log");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    public void log(String text, boolean diagnostic, long now, Consumer<String> relay) {
        String output = diagnostic ? limiter.filter(text, now) : String.valueOf(text);
        if (output == null) return;
        try {
            localLog.accept(output);
        } catch (Throwable error) {
            reportFailure(error);
        }
        if (!diagnostic || relay == null) return;
        try {
            // No Binder/framework call, intent, callback parameter or trust state is replayed.
            // Only the module's diagnostic text is handed to our own worker thread.
            executor.execute(() -> {
                try {
                    relay.accept(output);
                } catch (Throwable error) {
                    reportFailure(error);
                }
            });
        } catch (Throwable error) {
            // Queue saturation must never block (or fall back onto) the framework thread.
            reportFailure(error);
        }
    }

    private void reportFailure(Throwable error) {
        if (!failureReported.compareAndSet(false, true)) return;
        try {
            failureLog.accept(error);
        } catch (Throwable ignored) {
            // Failure reporting must not escape into the intercepted system method either.
        }
    }
}
