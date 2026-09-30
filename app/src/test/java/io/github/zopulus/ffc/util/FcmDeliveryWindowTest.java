package io.github.zopulus.ffc.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class FcmDeliveryWindowTest {
    @Test public void invalidUidCannotCreateWindow() {
        FcmDeliveryWindow window = new FcmDeliveryWindow();
        window.begin(-1, 100);
        assertFalse(window.contains(-1, 101));
    }

    @Test public void concurrentRenewalAndExpiryPreserveLatestDeadline() throws Exception {
        FcmDeliveryWindow window = new FcmDeliveryWindow();
        window.begin(10001, 0);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        Thread renewal = new Thread(() -> {
            try { start.await(); } catch (InterruptedException e) { throw new AssertionError(e); }
            for (int i = 0; i < 10000; i++) window.begin(10001, 30000 + i);
        });
        Thread expiry = new Thread(() -> {
            try { start.await(); } catch (InterruptedException e) { throw new AssertionError(e); }
            for (int i = 0; i < 10000; i++) window.contains(10001, 20000);
        });
        renewal.start(); expiry.start(); start.countDown(); renewal.join(); expiry.join();
        assertTrue(window.contains(10001, 59998));
        assertFalse(window.contains(10001, 59999));
    }
    @Test public void expiresAtBoundaryAndIsolatesUids() {
        FcmDeliveryWindow window = new FcmDeliveryWindow();
        assertFalse(window.contains(10001, 0));
        window.begin(10001, 100);
        assertTrue(window.contains(10001, 20099));
        assertFalse(window.contains(10002, 100));
        assertFalse(window.contains(10001, 20100));
    }

    @Test public void lateOlderUpdateCannotShortenWindow() {
        FcmDeliveryWindow window = new FcmDeliveryWindow();
        window.begin(10001, 100);
        window.begin(10001, 500);
        window.begin(10001, 200);
        assertTrue(window.contains(10001, 20499));
        assertFalse(window.contains(10001, 20500));
    }
}
