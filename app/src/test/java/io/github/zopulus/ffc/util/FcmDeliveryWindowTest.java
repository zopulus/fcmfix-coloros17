package io.github.zopulus.ffc.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class FcmDeliveryWindowTest {
    @Test public void fastPathStartsInactiveAndExpiresCleanlyWithoutPerUidLookup() {
        FcmDeliveryWindow window = new FcmDeliveryWindow();
        assertFalse(window.hasActiveWindow(0L));
        assertNull(window.packageName(10123, 0L));

        window.begin(10123, "com.example.chat", 1_000L);
        assertTrue(window.hasActiveWindow(1_000L));
        assertTrue(window.hasActiveWindow(20_999L));
        assertEquals("com.example.chat", window.packageName(10123, 20_999L));
        assertNull(window.packageName(10124, 15_000L));

        assertFalse(window.hasActiveWindow(21_000L));
        assertNull(window.packageName(10123, 21_000L));
    }

    @Test public void overlappingWindowsTrackFurthestDeadlineAndSaturateNearLongMax() {
        FcmDeliveryWindow window = new FcmDeliveryWindow();
        window.begin(10123, "com.example.first", 5_000L);
        window.begin(10124, "com.example.second", 12_000L);
        assertNull(window.packageName(10123, 26_000L));
        assertEquals("com.example.second", window.packageName(10124, 26_000L));
        assertTrue(window.hasActiveWindow(31_999L));
        assertFalse(window.hasActiveWindow(32_000L));

        window.begin(10125, "com.example.edge", Long.MAX_VALUE - 5_000L);
        assertTrue(window.hasActiveWindow(Long.MAX_VALUE - 1L));
        assertEquals("com.example.edge", window.packageName(10125, Long.MAX_VALUE - 1L));
        assertFalse(window.hasActiveWindow(Long.MAX_VALUE));
    }

    @Test public void expiredEntriesArePrunedWhenBurstExceedsThreshold() {
        FcmDeliveryWindow window = new FcmDeliveryWindow();
        for (int i = 0; i < 40; i++) {
            window.begin(10_000 + i, "com.example.app" + i, 1_000L);
        }
        window.begin(20_000, "com.example.fresh", 30_000L);
        assertEquals("com.example.fresh", window.packageName(20_000, 30_000L));
        assertNull(window.packageName(10_000, 30_000L));
    }
}
