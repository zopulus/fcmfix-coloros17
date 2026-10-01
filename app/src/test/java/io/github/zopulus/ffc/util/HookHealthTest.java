package io.github.zopulus.ffc.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class HookHealthTest {
    private static final String ALL = "OplusProxyWakeLock,OplusProxyBroadcast,setGmsRestricted,isGoogleRestricInfoOn,isAppClassifyRestricted,isAllowStartFromBindService,isAllowStartFromStartService,isSysRestrictionCpn,OAppNetControlService,HansSceneManager FCM window,HansCGroup FCM window,CpnProxy broadcast,isGmsRestricted,weak-signal net whitelist,validStartProcessFromBroadcast,malicious broadcast check,malicious service check,link-start broadcast check,Hans job FCM window";
    @Test public void missingOneProtectionCannotPass() {
        assertEquals("", HookHealth.missing(ALL, true, true, true, true, true, true, true));
        for (String name : ALL.split(",")) {
            java.util.List<String> names = new java.util.ArrayList<>(java.util.Arrays.asList(ALL.split(",")));
            names.remove(name);
            assertEquals(name, HookHealth.missing(String.join(",", names), true, true, true, true, true, true, true));
        }
    }
    @Test public void disabledWhitelistsAreOptionalButAlarmAndNetworkHooksAreMandatory() {
        assertEquals("", HookHealth.missing(ALL, false, false, false, true, true, false, true));
        assertEquals("Doze whitelist", HookHealth.missing(ALL, true, false, false, true, true, false, true));
        assertEquals("Sleep network whitelist", HookHealth.missing(ALL, false, true, false, true, true, false, true));
        assertEquals("Google wakeup alarm", HookHealth.missing(ALL, false, false, false, false, true, false, true));
        assertEquals("Deep-sleep wakeup alarm", HookHealth.missing(ALL, false, false, false, true, false, false, true));
        assertEquals("Google network policy hooks", HookHealth.missing(ALL, false, false, false, true, true, false, false));
    }
    @Test public void installedHooksDoNotImplySuccessfulNetworkVerification() {
        assertTrue(HookHealth.isHealthy(true, true, "", "verified"));
        assertFalse(HookHealth.isHealthy(true, true, "", "pending"));
        assertFalse(HookHealth.isHealthy(true, true, "", "failed"));
        assertFalse(HookHealth.isHealthy(true, true, "Hans job FCM window", "verified"));
        assertFalse(HookHealth.isHealthy(false, true, "", "verified"));
        assertFalse(HookHealth.isHealthy(true, false, "", "verified"));
    }
    @Test public void oldBootOrVersionCannotPass() {
        assertTrue(HookHealth.isCurrent(142, 88, 142, 88));
        assertFalse(HookHealth.isCurrent(141, 88, 142, 88));
        assertFalse(HookHealth.isCurrent(142, 87, 142, 88));
        assertFalse(HookHealth.isCurrent(-1, 88, -1, 88));
    }
}
