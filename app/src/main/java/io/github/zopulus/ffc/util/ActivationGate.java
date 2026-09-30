package io.github.zopulus.ffc.util;

import java.util.concurrent.*;

/** Waits only for activation, never captures or replays a framework broadcast invocation. */
public final class ActivationGate {
    private ActivationGate() {}
    public static boolean await(ExecutorService worker, Callable<Boolean> task, long timeoutMs) {
        Future<Boolean> future = null;
        try {
            future = worker.submit(task);
            return Boolean.TRUE.equals(future.get(timeoutMs, TimeUnit.MILLISECONDS));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException | TimeoutException | RejectedExecutionException error) {
            return false;
        } finally {
            if (future != null && !future.isDone()) {
                future.cancel(true);
                if (worker instanceof ThreadPoolExecutor && future instanceof Runnable)
                    ((ThreadPoolExecutor) worker).remove((Runnable) future);
            }
        }
    }
}
