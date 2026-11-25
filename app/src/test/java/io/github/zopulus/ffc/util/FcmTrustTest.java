package io.github.zopulus.ffc.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class FcmTrustTest {
    @Test public void onlyReceiveBroadcastsNeedSenderLookup() {
        assertTrue(FcmTrust.isDeliveryBroadcast(FcmTrust.RECEIVE));
        assertFalse(FcmTrust.isDeliveryBroadcast(null));
        assertFalse(FcmTrust.isDeliveryBroadcast("com.google.firebase.MESSAGING_EVENT"));
        assertFalse(FcmTrust.isDeliveryBroadcast("evil.android.c2dm.intent.RECEIVE"));
    }
    @Test public void jobRequiresCurrentPermissionAndMatchingPackages() {
        assertTrue(FcmTrust.allowsJob("target", "target", "target", true, true));
        assertFalse(FcmTrust.allowsJob("target", "target", "target", false, true));
        assertFalse(FcmTrust.allowsJob("target", "target", "target", true, false));
        assertFalse(FcmTrust.allowsJob("target", "sharedUidSibling", "sharedUidSibling", true, true));
        assertFalse(FcmTrust.allowsJob("target", "target", "sharedUidSibling", true, true));
        assertFalse(FcmTrust.allowsJob("target", "target", null, true, true));
        assertFalse(FcmTrust.allowsJob(null, "target", "target", true, true));
    }

    @Test public void destinationMustBeExplicitAndCoherent() {
        assertEquals("target", FcmTrust.target(null, "target"));
        assertEquals("target", FcmTrust.target("target", null));
        assertEquals("target", FcmTrust.target("target", "target"));
        assertNull(FcmTrust.target("target", "evil"));
        assertNull(FcmTrust.target(null, null));
    }
    @Test public void selfServiceNeedsWindowAndCannotSpoofReceiveOrTaskReady() {
        assertTrue(FcmTrust.allowsService("com.google.firebase.MESSAGING_EVENT", false, true, false));
        assertFalse(FcmTrust.allowsService("com.google.firebase.MESSAGING_EVENT", false, false, false));
        assertFalse(FcmTrust.allowsService(FcmTrust.RECEIVE, false, true, false));
        assertTrue(FcmTrust.allowsService(FcmTrust.TASK_READY, true, false, true));
        assertFalse(FcmTrust.allowsService(FcmTrust.TASK_READY, false, true, true));
        assertFalse(FcmTrust.allowsService(FcmTrust.TASK_READY, true, false, false));
        assertFalse(FcmTrust.isAction(FcmTrust.TASK_READY));
    }
    @Test public void rejectsUntrustedSender() {
        assertTrue(FcmTrust.isGmsSender(10123, new String[]{"com.google.android.gms"}));
        assertFalse(FcmTrust.isGmsSender(10124, new String[]{"evil"}));
        assertFalse(FcmTrust.isGmsSender(1000, new String[]{"com.google.android.gms"}));
        assertFalse(FcmTrust.isGmsSender(10123, null));
    }
    @Test public void actionsAreExact() {
        assertTrue(FcmTrust.isAction(FcmTrust.RECEIVE));
        assertTrue(FcmTrust.isAction("com.google.firebase.MESSAGING_EVENT"));
        assertFalse(FcmTrust.isAction("evil.android.c2dm.intent.RECEIVE"));
        assertFalse(FcmTrust.isAction(null));
        assertFalse(FcmTrust.isAction(""));
    }

    @Test public void nestedUntrustedEntryMasksAndRestoresOuterTrust() {
        assertFalse(FcmTrust.hasActiveScope());
        Runnable noopMask = FcmTrust.enter(null, -1);
        assertFalse(FcmTrust.hasActiveScope());
        noopMask.run();
        assertFalse(FcmTrust.hasActiveScope());
        Runnable outer = FcmTrust.enter("target", 10123);
        try {
            assertTrue(FcmTrust.hasActiveScope());
            assertTrue(FcmTrust.matches(FcmTrust.RECEIVE, "target"));
            Runnable inner = FcmTrust.enter(null, -1);
            try {
                assertFalse(FcmTrust.hasActiveScope());
                assertFalse(FcmTrust.matches(FcmTrust.RECEIVE, "target"));
            } finally { inner.run(); }
            assertTrue(FcmTrust.hasActiveScope());
            assertTrue(FcmTrust.matches(FcmTrust.RECEIVE, "target"));
            assertFalse(FcmTrust.matches(FcmTrust.RECEIVE, "other"));
            assertFalse(FcmTrust.matches("com.google.firebase.MESSAGING_EVENT", "target"));
        } finally { outer.run(); }
        assertFalse(FcmTrust.hasActiveScope());
        assertFalse(FcmTrust.matches(FcmTrust.RECEIVE, "target"));
    }

    @Test public void trustDoesNotCrossThreads() throws Exception {
        Runnable restore = FcmTrust.enter("target", 10123);
        java.util.concurrent.atomic.AtomicBoolean trusted = new java.util.concurrent.atomic.AtomicBoolean(true);
        try {
            Thread thread = new Thread(() -> trusted.set(FcmTrust.matches(FcmTrust.RECEIVE, "target")));
            thread.start(); thread.join();
            assertFalse(trusted.get());
        } finally { restore.run(); }
    }
}
