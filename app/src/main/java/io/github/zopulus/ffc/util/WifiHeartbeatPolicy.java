package io.github.zopulus.ffc.util;

/**
 * Per-Wi-Fi-session adaptive FCM heartbeat state machine.
 *
 * <p>Observes GMS transport lifecycle signals ({@code GCM_HB_ALARM}, {@code GCM_READ},
 * {@code GCM_CONN_ALARM}) within a single physical Wi-Fi session and tightens periodic
 * {@code GCM_HB_ALARM} delivery windows only after repeated probe-triggered silent disconnects.
 * No SSID/BSSID data is read or persisted across sessions.
 */
public final class WifiHeartbeatPolicy {
    public static final String GMS_PACKAGE = "com.google.android.gms";
    public static final String TAG_HB_ALARM = "GCM_HB_ALARM";
    public static final String TAG_CONN_ALARM = "GCM_CONN_ALARM";
    public static final String TAG_READ_WAKELOCK = "GCM_READ";

    /** GMS schedules a ~60s GCM_HB_ALARM while awaiting HeartbeatAck; periodic alarms exceed 65s. */
    static final long ACK_ALARM_MAX_FUTURITY_MS = 65_000L;
    /** Tightened window cap applied to the ~60s ACK timeout alarm once a session is tightened. */
    static final long TIGHT_ACK_WINDOW_MS = 10_000L;

    /** Minimum silent idle duration before a probe failure counts toward NAT aging. */
    static final long MIN_IDLE_BEFORE_PROBE_MS = 55_000L;
    /** Maximum delay from GCM_HB_ALARM wakeup to GCM_CONN_ALARM (covers 60s + 75% stretched ACK timeout). */
    static final long MAX_PROBE_FAILURE_WINDOW_MS = 125_000L;
    /** Grace window after a periodic reschedule before treating a subsequent reset as unrelated. */
    static final long ACK_SETTLE_GRACE_MS = 8_000L;
    /** Consecutive healthy heartbeats required to clear a single isolated probe failure. */
    static final int HEALTHY_STREAK_TO_CLEAR_SINGLE_FAILURE = 3;
    /** Confirmed probe disconnects required to leave TIER_NONE or advance into TIER_STRICT. */
    static final int FAILURES_TO_ADVANCE_TIER = 2;
    /**
     * Once TIER_NONE has already confirmed silent aging (typically at ~313s due to Android's +75%
     * batching window), a single dead probe at TIER_MILD (180s..210s) proves <= 210s aging and
     * immediately steps down to TIER_STANDARD without wasting another 3.5-minute dead cycle.
     */
    static final int FAILURES_TO_LEAVE_MILD = 1;

    /** Dead-idle threshold separating mild (>= 5m NAT) from standard (2m-4m NAT) tightening. */
    static final long MILD_DEAD_IDLE_THRESHOLD_MS = 280_000L;

    public static final int TIER_NONE = 0;
    public static final int TIER_MILD = 1;     // 180s .. 210s (30s batching window)
    public static final int TIER_STANDARD = 2; //  85s .. 105s (20s batching window)
    public static final int TIER_STRICT = 3;   //  50s ..  60s (10s batching window)

    private static final long TIER_MILD_WHEN_MS = 180_000L;
    private static final long TIER_MILD_WINDOW_MS = 30_000L;
    private static final long TIER_STANDARD_WHEN_MS = 85_000L;
    private static final long TIER_STANDARD_WINDOW_MS = 20_000L;
    private static final long TIER_STRICT_WHEN_MS = 50_000L;
    private static final long TIER_STRICT_WINDOW_MS = 10_000L;

    public static final class AlarmAdjustment {
        public final long whenElapsed;
        public final long windowLength;
        public final long maxWhenElapsed;

        AlarmAdjustment(long whenElapsed, long windowLength) {
            this.whenElapsed = whenElapsed;
            this.windowLength = windowLength;
            this.maxWhenElapsed = whenElapsed + windowLength;
        }
    }

    private boolean enabled = true;
    private long activeWifiHandle = 0L;
    private int tier = TIER_NONE;

    private boolean connecting;
    private long lastActivityElapsed;

    private boolean probePending;
    private long probeStartedElapsed;
    private long probeIdleMs;
    private long probeAckRescheduledElapsed;

    private int pendingFailures;
    private int healthyStreak;
    private long minObservedDeadIdleMs = Long.MAX_VALUE;

    public synchronized void setEnabled(boolean enabled, long nowElapsed) {
        if (this.enabled == enabled) return;
        this.enabled = enabled;
        resetSessionState(nowElapsed);
    }

    public synchronized void onWifiChanged(long wifiNetworkHandle, long nowElapsed) {
        if (this.activeWifiHandle == wifiNetworkHandle) return;
        this.activeWifiHandle = wifiNetworkHandle;
        resetSessionState(nowElapsed);
    }

    private void resetSessionState(long nowElapsed) {
        tier = TIER_NONE;
        connecting = false;
        lastActivityElapsed = nowElapsed;
        clearProbe();
        pendingFailures = 0;
        healthyStreak = 0;
        minObservedDeadIdleMs = Long.MAX_VALUE;
    }

    private void clearProbe() {
        probePending = false;
        probeStartedElapsed = 0L;
        probeIdleMs = 0L;
        probeAckRescheduledElapsed = 0L;
    }

    public synchronized int currentTier() {
        return enabled && activeWifiHandle != 0L ? tier : TIER_NONE;
    }

    /** Called from PowerManagerService#acquireWakeLockInternal for GMS transport wakelocks. */
    public synchronized int onGmsWakeLockAcquired(String tag, long nowElapsed) {
        if (!enabled || activeWifiHandle == 0L || tag == null) return TIER_NONE;
        settleAckedProbeIfSurvived(nowElapsed);

        switch (tag) {
            case TAG_HB_ALARM:
                if (!connecting) {
                    long idle = Math.max(0L, nowElapsed - lastActivityElapsed);
                    probePending = true;
                    probeStartedElapsed = nowElapsed;
                    probeIdleMs = idle;
                    probeAckRescheduledElapsed = 0L;
                }
                break;

            case TAG_READ_WAKELOCK:
                // Inbound data during established state refreshes wire activity; during a pending
                // probe we wait for the periodic alarm reschedule + grace window before clearing,
                // because FIN/RST EOF reads can briefly acquire GCM_READ right before reconnect.
                if (!connecting && !probePending) {
                    lastActivityElapsed = nowElapsed;
                }
                break;

            case TAG_CONN_ALARM:
                int previousTier = tier;
                if (probePending && (nowElapsed - probeStartedElapsed) <= MAX_PROBE_FAILURE_WINDOW_MS) {
                    if (probeIdleMs >= MIN_IDLE_BEFORE_PROBE_MS) {
                        recordProbeFailure(probeIdleMs);
                    }
                }
                clearProbe();
                connecting = true;
                lastActivityElapsed = nowElapsed;
                return tier != previousTier ? tier : TIER_NONE;

            default:
                break;
        }
        return TIER_NONE;
    }

    /**
     * Called from AlarmManagerService#setImplLocked(Alarm) before insertion into mAlarmStore
     * for com.google.android.gms alarms tagged {@code GCM_HB_ALARM}.
     *
     * @return non-null adjustment when the alarm's delivery window should be clamped, or null to leave untouched.
     */
    public synchronized AlarmAdjustment onHeartbeatAlarmScheduled(
            long nowElapsed, long requestedWhenElapsed, long currentWindowLength) {
        if (!enabled || activeWifiHandle == 0L) return null;
        settleAckedProbeIfSurvived(nowElapsed);

        long futurity = requestedWhenElapsed - nowElapsed;
        if (futurity <= ACK_ALARM_MAX_FUTURITY_MS) {
            // ~60s HeartbeatAck timeout alarm: never shorten its trigger time; in tightened tiers,
            // prevent ColorOS from stretching its window to +45s so dead links recover promptly.
            if (tier != TIER_NONE && currentWindowLength > TIGHT_ACK_WINDOW_MS) {
                return new AlarmAdjustment(requestedWhenElapsed, TIGHT_ACK_WINDOW_MS);
            }
            return null;
        }

        // Periodic heartbeat alarm (> 65s): marks either reconnect completion or HeartbeatAck receipt.
        if (connecting) {
            connecting = false;
            lastActivityElapsed = nowElapsed;
        } else if (probePending && probeAckRescheduledElapsed == 0L) {
            probeAckRescheduledElapsed = nowElapsed;
        }

        if (tier == TIER_NONE) return null;

        long targetWhenOffset;
        long targetWindow;
        switch (tier) {
            case TIER_MILD:
                targetWhenOffset = TIER_MILD_WHEN_MS;
                targetWindow = TIER_MILD_WINDOW_MS;
                break;
            case TIER_STANDARD:
                targetWhenOffset = TIER_STANDARD_WHEN_MS;
                targetWindow = TIER_STANDARD_WINDOW_MS;
                break;
            case TIER_STRICT:
            default:
                targetWhenOffset = TIER_STRICT_WHEN_MS;
                targetWindow = TIER_STRICT_WINDOW_MS;
                break;
        }

        long clampedWhen = Math.min(requestedWhenElapsed, nowElapsed + targetWhenOffset);
        long clampedMax = clampedWhen + targetWindow;
        long currentMax = requestedWhenElapsed + Math.max(0L, currentWindowLength);
        if (clampedWhen == requestedWhenElapsed && currentMax <= clampedMax) {
            return null;
        }
        return new AlarmAdjustment(clampedWhen, targetWindow);
    }

    private void settleAckedProbeIfSurvived(long nowElapsed) {
        if (!probePending) return;
        if (probeAckRescheduledElapsed > 0L
                && (nowElapsed - probeAckRescheduledElapsed) >= ACK_SETTLE_GRACE_MS) {
            clearProbe();
            lastActivityElapsed = probeAckRescheduledElapsed;
            healthyStreak++;
            if (pendingFailures > 0
                    && healthyStreak >= HEALTHY_STREAK_TO_CLEAR_SINGLE_FAILURE) {
                pendingFailures = 0;
            }
        } else if ((nowElapsed - probeStartedElapsed) > MAX_PROBE_FAILURE_WINDOW_MS) {
            clearProbe();
        }
    }

    private void recordProbeFailure(long deadIdleMs) {
        healthyStreak = 0;
        if (deadIdleMs < minObservedDeadIdleMs) {
            minObservedDeadIdleMs = deadIdleMs;
        }
        pendingFailures++;
        int required = tier == TIER_MILD ? FAILURES_TO_LEAVE_MILD : FAILURES_TO_ADVANCE_TIER;
        if (pendingFailures < required) return;

        pendingFailures = 0;
        if (tier == TIER_NONE) {
            tier = minObservedDeadIdleMs >= MILD_DEAD_IDLE_THRESHOLD_MS ? TIER_MILD : TIER_STANDARD;
        } else if (tier == TIER_MILD) {
            tier = TIER_STANDARD;
        } else if (tier == TIER_STANDARD) {
            tier = TIER_STRICT;
        }
    }
}
