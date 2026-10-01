package io.github.zopulus.ffc.util;

import java.util.concurrent.ConcurrentHashMap;

/** Package attribution and expiry are one immutable record, replaced atomically per UID. */
public final class FcmDeliveryWindow {
    private static final class Entry {
        final String packageName;
        final long deadline;
        Entry(String packageName, long deadline) { this.packageName = packageName; this.deadline = deadline; }
    }
    private final ConcurrentHashMap<Integer, Entry> entries = new ConcurrentHashMap<>();

    public void begin(int uid, String packageName, long now) {
        if (uid < 0 || packageName == null || packageName.isEmpty()) return;
        Entry next = new Entry(packageName, now + 20_000L);
        entries.compute(uid, (key, previous) -> previous == null || next.deadline >= previous.deadline
                ? next : previous);
    }

    public String packageName(int uid, long now) {
        Entry current = entries.computeIfPresent(uid, (key, entry) -> now < entry.deadline ? entry : null);
        return current == null ? null : current.packageName;
    }

    public boolean contains(int uid, long now) { return packageName(uid, now) != null; }
}
