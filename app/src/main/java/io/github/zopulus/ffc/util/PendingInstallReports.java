package io.github.zopulus.ffc.util;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** Owned by the installation executor. Keep one report per installed package until acknowledged or removed. */
public final class PendingInstallReports {
    private static final long[] RETRY_MS = {200, 600, 1500};
    private static final long RECOVERY_MS = 300_000;
    private final RetryingTask.Scheduler scheduler;
    private final Map<String, Report> reports = new HashMap<>();

    private static final class Report {
        final BooleanSupplier send;
        int failures;
        RetryingTask.Ticket timer;
        Report(BooleanSupplier send) { this.send = send; }
    }

    public PendingInstallReports(RetryingTask.Scheduler scheduler) { this.scheduler = scheduler; }

    public void installed(String pkg, BooleanSupplier send) {
        removed(pkg);
        Report report = new Report(send);
        reports.put(pkg, report);
        attempt(pkg, report);
    }

    public void removed(String pkg) {
        Report old = reports.remove(pkg);
        if (old != null && old.timer != null) old.timer.cancel();
    }

    public void recovered() {
        reports.forEach((pkg, report) -> {
            if (report.failures <= RETRY_MS.length) return;
            report.timer.cancel();
            report.failures = RETRY_MS.length;
            report.timer = scheduler.schedule(() -> attempt(pkg, report), 0);
        });
    }

    private void attempt(String pkg, Report report) {
        if (reports.get(pkg) != report) return;
        if (report.send.getAsBoolean()) {
            reports.remove(pkg);
            return;
        }
        int step = report.failures;
        long delay = step < RETRY_MS.length ? RETRY_MS[step] : RECOVERY_MS;
        report.failures = Math.min(step + 1, RETRY_MS.length + 1);
        report.timer = scheduler.schedule(() -> attempt(pkg, report), delay);
    }
}
