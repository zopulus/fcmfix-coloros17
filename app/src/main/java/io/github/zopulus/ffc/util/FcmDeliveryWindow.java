package io.github.zopulus.ffc.util;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Package attribution and expiry are one immutable record, replaced atomically per UID. */
public final class FcmDeliveryWindow {
    private static final long WINDOW_DURATION_MS = 20_000L;

    private static final class Entry {
        final String packageName;
        final long deadline;
        Entry(String packageName, long deadline) { this.packageName = packageName; this.deadline = deadline; }
    }

    private final ConcurrentHashMap<Integer, Entry> entries = new ConcurrentHashMap<>();
    private final AtomicLong activeUntilElapsed = new AtomicLong(Long.MIN_VALUE);

    public boolean hasActiveWindow(long now) {
        return now < activeUntilElapsed.get();
    }

    public void begin(int uid, String packageName, long now) {
        if (uid < 0 || packageName == null || packageName.isEmpty()) return;
        long deadline = now > Long.MAX_VALUE - WINDOW_DURATION_MS ? Long.MAX_VALUE : now + WINDOW_DURATION_MS;
        Entry next = new Entry(packageName, deadline);
        entries.compute(uid, (key, previous) -> previous == null || next.deadline >= previous.deadline
                ? next : previous);
        activeUntilElapsed.accumulateAndGet(deadline, Math::max);
        if (entries.size() > 32) {
            entries.entrySet().removeIf(item -> now >= item.getValue().deadline);
        }
    }

    public String packageName(int uid, long now) {
        if (now >= activeUntilElapsed.get()) return null;
        Entry current = entries.computeIfPresent(uid, (key, entry) -> now < entry.deadline ? entry : null);
        return current == null ? null : current.packageName;
    }

    public boolean contains(int uid, long now) { return packageName(uid, now) != null; }

    public boolean contains(int uid, String target, long now) {
        return target != null && target.equals(packageName(uid, now));
    }
}
