package io.github.zopulus.ffc.util;

import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded, per-process filtering for success/diagnostic logs, never delivery decisions. */
public final class DiagnosticLogLimiter {
    static final long REPEAT_INTERVAL_MS = 30_000;
    static final long BURST_INTERVAL_MS = 5_000;
    static final int MAX_BURST = 32;
    static final int MAX_KEYS = 128;
    static final int MAX_MESSAGE_LENGTH = 2048;

    private final Map<String, Long> lastEmitted = new LinkedHashMap<>();
    private long latestTime;
    private long burstStart;
    private int burstCount;
    private long suppressed;

    /** Returns one message (with an aggregate suppression count), or null to skip it. */
    public synchronized String filter(String message, long elapsedRealtime) {
        // Callers may sample the clock before another thread acquires this lock.
        long now = Math.max(latestTime, elapsedRealtime);
        latestTime = now;
        String key = String.valueOf(message);
        if (key.length() > MAX_MESSAGE_LENGTH) {
            key = key.substring(0, MAX_MESSAGE_LENGTH) + " [truncated]";
        }
        if (now - burstStart >= BURST_INTERVAL_MS) {
            burstStart = now;
            burstCount = 0;
        }
        Long previous = lastEmitted.get(key);
        if ((previous != null && now - previous < REPEAT_INTERVAL_MS)
                || burstCount >= MAX_BURST) {
            if (suppressed < Long.MAX_VALUE) suppressed++;
            return null;
        }
        // Keep insertion order equal to last-emission order, not last suppressed access.
        lastEmitted.remove(key);
        if (lastEmitted.size() >= MAX_KEYS) {
            lastEmitted.remove(lastEmitted.keySet().iterator().next());
        }
        lastEmitted.put(key, now);
        burstCount++;
        if (suppressed == 0) return key;
        String result = key + " [suppressed " + suppressed + " diagnostic lines since last output]";
        suppressed = 0;
        return result;
    }

    synchronized int trackedKeys() {
        return lastEmitted.size();
    }
}
