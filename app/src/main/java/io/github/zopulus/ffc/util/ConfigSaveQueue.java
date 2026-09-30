package io.github.zopulus.ffc.util;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Process lifetime queue: activity recreation must not cancel a config write. */
public final class ConfigSaveQueue {
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "FCMFix-save");
        thread.setDaemon(true);
        return thread;
    });
    private ConfigSaveQueue() {}
    public static void submit(Runnable task) { WRITER.execute(task); }
}
