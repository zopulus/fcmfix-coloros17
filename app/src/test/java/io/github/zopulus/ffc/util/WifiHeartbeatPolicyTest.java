package io.github.zopulus.ffc.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class WifiHeartbeatPolicyTest {

    @Test
    public void healthyWifiAndCellularRemainUntouched() {
        WifiHeartbeatPolicy policy = new WifiHeartbeatPolicy();
        // Cellular (handle = 0): even probe + reconnect never activates tightening.
        policy.onGmsWakeLockAcquired(WifiHeartbeatPolicy.TAG_HB_ALARM, 200_000L);
        policy.onGmsWakeLockAcquired(WifiHeartbeatPolicy.TAG_CONN_ALARM, 200_200L);
        policy.onGmsWakeLockAcquired(WifiHeartbeatPolicy.TAG_HB_ALARM, 400_000L);
        policy.onGmsWakeLockAcquired(WifiHeartbeatPolicy.TAG_CONN_ALARM, 400_200L);
        assertEquals(WifiHeartbeatPolicy.TIER_NONE, policy.currentTier());
        assertNull(policy.onHeartbeatAlarmScheduled(401_000L, 581_000L, 135_000L));

        // Healthy Wi-Fi: probe -> 60s ACK alarm -> fast HeartbeatAck periodic reschedule.
        policy.onWifiChanged(103L, 500_000L);
        for (int i = 0; i < 5; i++) {
            long t = 700_000L + i * 250_000L;
            policy.onGmsWakeLockAcquired(WifiHeartbeatPolicy.TAG_HB_ALARM, t);
            assertNull(policy.onHeartbeatAlarmScheduled(t + 5L, t + 60_005L, 45_000L));
            policy.onGmsWakeLockAcquired(WifiHeartbeatPolicy.TAG_READ_WAKELOCK, t + 300L);
            assertNull(policy.onHeartbeatAlarmScheduled(t + 305L, t + 180_305L, 135_000L));
        }
        assertEquals(WifiHeartbeatPolicy.TIER_NONE, policy.currentTier());
    }

    @Test
    public void midIdleProxyReloadAndSettledHeartbeatNeverFalsePositive() {
        WifiHeartbeatPolicy policy = new WifiHeartbeatPolicy();
        policy.onWifiChanged(103L, 10_000L);

        // Mid-idle proxy clear_links / node switch without a heartbeat probe.
        for (int i = 0; i < 4; i++) {
            long t = 150_000L + i * 150_000L;
            assertEquals(WifiHeartbeatPolicy.TIER_NONE,
                    policy.onGmsWakeLockAcquired(WifiHeartbeatPolicy.TAG_CONN_ALARM, t));
            assertNull(policy.onHeartbeatAlarmScheduled(t + 800L, t + 180_800L, 135_000L));
        }

        // Healthy HeartbeatAck at +400ms followed 20s later by manual proxy restart.
        long probeAt = 900_000L;
        policy.onGmsWakeLockAcquired(WifiHeartbeatPolicy.TAG_HB_ALARM, probeAt);
        policy.onHeartbeatAlarmScheduled(probeAt + 5L, probeAt + 60_005L, 45_000L);
        policy.onGmsWakeLockAcquired(WifiHeartbeatPolicy.TAG_READ_WAKELOCK, probeAt + 400L);
        policy.onHeartbeatAlarmScheduled(probeAt + 405L, probeAt + 180_405L, 135_000L);
        assertEquals(WifiHeartbeatPolicy.TIER_NONE,
                policy.onGmsWakeLockAcquired(WifiHeartbeatPolicy.TAG_CONN_ALARM, probeAt + 20_000L));
        assertEquals(WifiHeartbeatPolicy.TIER_NONE, policy.currentTier());
    }

    @Test
    public void singleFlukeClearsAfterThreeHealthyHeartbeatsAndCaptivePortalIsIgnored() {
        WifiHeartbeatPolicy policy = new WifiHeartbeatPolicy();
        policy.onWifiChanged(103L, 1_000L);

        // Rapid disconnects (< 55s idle, e.g. captive portal / handshake failure) are ignored.
        policy.onGmsWakeLockAcquired(WifiHeartbeatPolicy.TAG_HB_ALARM, 30_000L);
        policy.onGmsWakeLockAcquired(WifiHeartbeatPolicy.TAG_CONN_ALARM, 30_500L);
        policy.onHeartbeatAlarmScheduled(31_000L, 211_000L, 135_000L);
        policy.onGmsWakeLockAcquired(WifiHeartbeatPolicy.TAG_HB_ALARM, 65_000L);
        policy.onGmsWakeLockAcquired(WifiHeartbeatPolicy.TAG_CONN_ALARM, 65_500L);
        policy.onHeartbeatAlarmScheduled(66_000L, 246_000L, 135_000L);
        assertEquals(WifiHeartbeatPolicy.TIER_NONE, policy.currentTier());

        // One real probe failure at 200s idle...
        policy.onGmsWakeLockAcquired(WifiHeartbeatPolicy.TAG_HB_ALARM, 270_000L);
        assertEquals(WifiHeartbeatPolicy.TIER_NONE,
                policy.onGmsWakeLockAcquired(WifiHeartbeatPolicy.TAG_CONN_ALARM, 270_200L));
        policy.onHeartbeatAlarmScheduled(271_000L, 451_000L, 135_000L);

        // ...followed by 3 healthy heartbeats clears the pending single failure.
        for (int i = 0; i < 3; i++) {
            long t = 470_000L + i * 200_000L;
            policy.onGmsWakeLockAcquired(WifiHeartbeatPolicy.TAG_HB_ALARM, t);
            policy.onHeartbeatAlarmScheduled(t + 300L, t + 180_300L, 135_000L);
        }

        // A later isolated failure still counts as #1 (not #2).
        policy.onGmsWakeLockAcquired(WifiHeartbeatPolicy.TAG_HB_ALARM, 1_100_000L);
        assertEquals(WifiHeartbeatPolicy.TIER_NONE,
                policy.onGmsWakeLockAcquired(WifiHeartbeatPolicy.TAG_CONN_ALARM, 1_100_300L));
        assertEquals(WifiHeartbeatPolicy.TIER_NONE, policy.currentTier());
    }

    @Test
    public void shortNatDropsDirectlyToStandardAndInboundReadRefreshesSilence() {
        WifiHeartbeatPolicy policy = new WifiHeartbeatPolicy();
        policy.onWifiChanged(103L, 0L);

        // Connection stays open 313s total, but GCM_READ at 72s means true silence is 241s (< 280s).
        policy.onGmsWakeLockAcquired(WifiHeartbeatPolicy.TAG_READ_WAKELOCK, 72_000L);
        policy.onGmsWakeLockAcquired(WifiHeartbeatPolicy.TAG_HB_ALARM, 313_000L);
        // Transient GCM_READ on EOF right before RST must not hide the probe failure.
        policy.onGmsWakeLockAcquired(WifiHeartbeatPolicy.TAG_READ_WAKELOCK, 313_180L);
        assertEquals(WifiHeartbeatPolicy.TIER_NONE,
                policy.onGmsWakeLockAcquired(WifiHeartbeatPolicy.TAG_CONN_ALARM, 313_200L));
        policy.onHeartbeatAlarmScheduled(314_000L, 494_000L, 135_000L);

        // Second failure via 105s stretched ACK blackhole timeout (Close err:6) -> TIER_STANDARD!
        policy.onGmsWakeLockAcquired(WifiHeartbeatPolicy.TAG_HB_ALARM, 520_000L);
        policy.onHeartbeatAlarmScheduled(520_005L, 580_005L, 45_000L);
        assertEquals(WifiHeartbeatPolicy.TIER_STANDARD,
                policy.onGmsWakeLockAcquired(WifiHeartbeatPolicy.TAG_CONN_ALARM, 625_010L));

        // Periodic 360s + 270s alarm is clamped to +85s (window 20s -> max +105s).
        WifiHeartbeatPolicy.AlarmAdjustment periodic =
                policy.onHeartbeatAlarmScheduled(626_000L, 986_000L, 270_000L);
        assertNotNull(periodic);
        assertEquals(626_000L + 85_000L, periodic.whenElapsed);
        assertEquals(20_000L, periodic.windowLength);
        assertEquals(626_000L + 105_000L, periodic.maxWhenElapsed);

        // 60s ACK timeout alarm keeps its trigger time but caps stretched +45s window to 10s.
        WifiHeartbeatPolicy.AlarmAdjustment ack =
                policy.onHeartbeatAlarmScheduled(711_000L, 771_000L, 45_000L);
        assertNotNull(ack);
        assertEquals(771_000L, ack.whenElapsed);
        assertEquals(10_000L, ack.windowLength);

        // Switching Wi-Fi handle resets to TIER_NONE.
        policy.onWifiChanged(205L, 800_000L);
        assertEquals(WifiHeartbeatPolicy.TIER_NONE, policy.currentTier());
    }

    @Test
    public void longNatStartsAtMildAndStepsDownWhenNeeded() {
        WifiHeartbeatPolicy policy = new WifiHeartbeatPolicy();
        policy.onWifiChanged(103L, 0L);

        // Two probe failures after ~600s idle -> TIER_MILD (180s .. 210s).
        policy.onGmsWakeLockAcquired(WifiHeartbeatPolicy.TAG_HB_ALARM, 600_000L);
        policy.onGmsWakeLockAcquired(WifiHeartbeatPolicy.TAG_CONN_ALARM, 705_000L);
        policy.onHeartbeatAlarmScheduled(706_000L, 1_066_000L, 270_000L);

        policy.onGmsWakeLockAcquired(WifiHeartbeatPolicy.TAG_HB_ALARM, 1_310_000L);
        assertEquals(WifiHeartbeatPolicy.TIER_MILD,
                policy.onGmsWakeLockAcquired(WifiHeartbeatPolicy.TAG_CONN_ALARM, 1_415_000L));

        WifiHeartbeatPolicy.AlarmAdjustment mild =
                policy.onHeartbeatAlarmScheduled(1_416_000L, 1_776_000L, 270_000L);
        assertNotNull(mild);
        assertEquals(1_416_000L + 180_000L, mild.whenElapsed);
        assertEquals(30_000L, mild.windowLength);

        // One confirmed failure at TIER_MILD immediately steps down to TIER_STANDARD.
        policy.onGmsWakeLockAcquired(WifiHeartbeatPolicy.TAG_HB_ALARM, 1_610_000L);
        assertEquals(WifiHeartbeatPolicy.TIER_STANDARD,
                policy.onGmsWakeLockAcquired(WifiHeartbeatPolicy.TAG_CONN_ALARM, 1_610_200L));
        policy.onHeartbeatAlarmScheduled(1_611_000L, 1_791_000L, 135_000L);

        // In TIER_STANDARD, a single isolated failure clears after 3 healthy heartbeats.
        policy.onGmsWakeLockAcquired(WifiHeartbeatPolicy.TAG_HB_ALARM, 1_700_000L);
        assertEquals(WifiHeartbeatPolicy.TIER_NONE,
                policy.onGmsWakeLockAcquired(WifiHeartbeatPolicy.TAG_CONN_ALARM, 1_700_200L));
        policy.onHeartbeatAlarmScheduled(1_701_000L, 1_881_000L, 135_000L);
        for (int i = 0; i < 3; i++) {
            long t = 1_790_000L + i * 95_000L;
            policy.onGmsWakeLockAcquired(WifiHeartbeatPolicy.TAG_HB_ALARM, t);
            policy.onHeartbeatAlarmScheduled(t + 250L, t + 180_250L, 135_000L);
        }

        // Two confirmed failures at TIER_STANDARD advance to TIER_STRICT.
        policy.onGmsWakeLockAcquired(WifiHeartbeatPolicy.TAG_HB_ALARM, 2_100_000L);
        assertEquals(WifiHeartbeatPolicy.TIER_NONE,
                policy.onGmsWakeLockAcquired(WifiHeartbeatPolicy.TAG_CONN_ALARM, 2_100_200L));
        policy.onHeartbeatAlarmScheduled(2_101_000L, 2_281_000L, 135_000L);
        policy.onGmsWakeLockAcquired(WifiHeartbeatPolicy.TAG_HB_ALARM, 2_190_000L);
        assertEquals(WifiHeartbeatPolicy.TIER_STRICT,
                policy.onGmsWakeLockAcquired(WifiHeartbeatPolicy.TAG_CONN_ALARM, 2_190_200L));

        // Disabling the toggle clears the session immediately.
        policy.setEnabled(false, 2_300_000L);
        assertEquals(WifiHeartbeatPolicy.TIER_NONE, policy.currentTier());
        assertNull(policy.onHeartbeatAlarmScheduled(2_301_000L, 2_661_000L, 270_000L));
    }
}
