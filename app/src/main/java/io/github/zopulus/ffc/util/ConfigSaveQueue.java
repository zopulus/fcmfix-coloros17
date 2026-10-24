package io.github.zopulus.ffc.util;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Process lifetime queue: activity recreation must not cancel a config write. */
public final class ConfigSaveQueue {
    private static volatile Thread writerThread;
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "FCMFix-save");
        thread.setDaemon(true);
        writerThread = thread;
        return thread;
    });
    private ConfigSaveQueue() {}
    public static void submit(Runnable task) { WRITER.execute(task); }

    /** Keep the provider request active until persistence finishes; never acknowledge an enqueue. */
    public static <T> T call(Callable<T> task) throws Exception {
        if (Thread.currentThread() == writerThread) return task.call();
        Future<T> future = WRITER.submit(task);
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            future.cancel(false);
            Thread.currentThread().interrupt();
            throw error;
        } catch (TimeoutException error) {
            future.cancel(false);
            throw error;
        } catch (ExecutionException error) {
            if (error.getCause() instanceof Exception) throw (Exception) error.getCause();
            throw new IllegalStateException("Configuration task failed", error.getCause());
        }
    }
}
