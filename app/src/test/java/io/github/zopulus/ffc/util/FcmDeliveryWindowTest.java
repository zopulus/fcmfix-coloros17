package io.github.zopulus.ffc.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class FcmDeliveryWindowTest {
    @Test public void invalidUidCannotCreateWindow() {
        FcmDeliveryWindow window = new FcmDeliveryWindow();
        window.begin(-1, "target", 100);
        assertFalse(window.contains(-1, 101));
    }

    @Test public void concurrentRenewalAndExpiryPreserveLatestDeadline() throws Exception {
        FcmDeliveryWindow window = new FcmDeliveryWindow();
        window.begin(10001, "target", 0);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        Thread renewal = new Thread(() -> {
            try { start.await(); } catch (InterruptedException e) { throw new AssertionError(e); }
            for (int i = 0; i < 10000; i++) window.begin(10001, "target", 30000 + i);
        });
        Thread expiry = new Thread(() -> {
            try { start.await(); } catch (InterruptedException e) { throw new AssertionError(e); }
            for (int i = 0; i < 10000; i++) window.contains(10001, 20000);
        });
        renewal.start(); expiry.start(); start.countDown(); renewal.join(); expiry.join();
        assertEquals("target", window.packageName(10001, 59998));
        assertTrue(window.contains(10001, 59998));
        assertFalse(window.contains(10001, 59999));
    }
    @Test public void expiresAtBoundaryAndIsolatesUids() {
        FcmDeliveryWindow window = new FcmDeliveryWindow();
        assertFalse(window.contains(10001, 0));
        window.begin(10001, "target", 100);
        assertTrue(window.contains(10001, 20099));
        assertFalse(window.contains(10002, 100));
        assertFalse(window.contains(10001, 20100));
    }

    @Test public void staleExpiryCannotEraseRenewedAttribution() {
        FcmDeliveryWindow window = new FcmDeliveryWindow();
        window.begin(10001, "old", 0);
        assertNull(window.packageName(10001, 20000));
        window.begin(10001, "new", 20000);
        assertEquals("new", window.packageName(10001, 20001));
        window.begin(10001, "older", 100);
        assertEquals("new", window.packageName(10001, 20002));
        assertNull(window.packageName(10001, 40000));
    }

    @Test public void invalidTargetCannotReplaceValidAttribution() {
        FcmDeliveryWindow window = new FcmDeliveryWindow();
        window.begin(10001, "target", 0);
        window.begin(10001, null, 100);
        window.begin(10001, "", 100);
        assertEquals("target", window.packageName(10001, 200));
    }

    @Test public void lateOlderUpdateCannotShortenWindow() {
        FcmDeliveryWindow window = new FcmDeliveryWindow();
        window.begin(10001, "target", 100);
        window.begin(10001, "target", 500);
        window.begin(10001, "target", 200);
        assertTrue(window.contains(10001, 20499));
        assertFalse(window.contains(10001, 20500));
    }
}
