package io.github.zopulus.ffc.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Installation health is separate from an actual OEM network policy verification. */
public final class HookHealth {
    private HookHealth() {}
    private static final String[] REQUIRED = {
            "OplusProxyWakeLock",
            "OplusProxyBroadcast",
            "setGmsRestricted",
            "isGoogleRestricInfoOn",
            "isAppClassifyRestricted",
            "isAllowStartFromBindService",
            "isAllowStartFromStartService",
            "isSysRestrictionCpn",
            "OAppNetControlService",
            "HansSceneManager FCM window",
            "HansCGroup FCM window",
            "CpnProxy broadcast",
            "isGmsRestricted",
            "weak-signal net whitelist",
            "validStartProcessFromBroadcast",
            "malicious broadcast check",
            "malicious service check",
            "link-start broadcast check",
            "Hans job FCM window"
    };
    public static String missing(String installed, boolean needDoze, boolean needSleep,
                                 boolean doze, boolean alarm, boolean deepSleepAlarm,
                                 boolean sleep, boolean network) {
        Set<String> names = new HashSet<>(Arrays.asList(installed.split(",")));
        List<String> missing = new ArrayList<>();
        for (String name : REQUIRED) if (!names.contains(name)) missing.add(name);
        if (needDoze && !doze) missing.add("Doze whitelist");
        if (!alarm) missing.add("Google wakeup alarm");
        if (!deepSleepAlarm) missing.add("Deep-sleep wakeup alarm");
        if (needSleep && !sleep) missing.add("Sleep network whitelist");
        if (!network) missing.add("Google network policy hooks");
        return String.join(",", missing);
    }
    public static boolean isHealthy(boolean systemCurrent, boolean batteryCurrent,
                                    String missing, String policyState) {
        return systemCurrent && batteryCurrent && missing.isEmpty() && "verified".equals(policyState);
    }
    public static boolean isCurrent(int boot, int version, int currentBoot, int currentVersion) {
        return currentBoot >= 0 && boot == currentBoot && version == currentVersion;
    }
}
