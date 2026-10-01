package io.github.zopulus.ffc.util;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** One immutable view of one RemotePreferences read; partial updates are never published. */
public final class ConfigSnapshot {
    public final long revision;
    public final Set<String> allowList;
    public final Map<String, Boolean> options;

    public ConfigSnapshot(Map<String, ?> values) {
        Object stamp = values.get("revision");
        if (stamp != null && (!(stamp instanceof Number) || ((Number) stamp).longValue() < 0)) throw new IllegalArgumentException("Invalid revision");
        revision = stamp == null ? 0 : ((Number) stamp).longValue();
        Set<String> packages = new HashSet<>();
        Object raw = values.get("allowList");
        if (raw != null) {
            if (!(raw instanceof Set<?>)) throw new IllegalArgumentException("Invalid allowList");
            for (Object name : (Set<?>) raw) {
                if (!(name instanceof String)) throw new IllegalArgumentException("Invalid package name");
                packages.add((String) name);
            }
        }
        allowList = Collections.unmodifiableSet(packages);
        Map<String, Boolean> flags = new HashMap<>();
        for (String key : new String[]{"disableAutoCleanNotification", "includeIceBoxDisableApp", "deepSleepGoogleWhitelist", "dozeGoogleWhitelist", "disableGoogleNetworkControl", "rootDeepSleepNetworkWhitelist"}) {
            Object value = values.get(key);
            if (value != null && !(value instanceof Boolean)) throw new IllegalArgumentException("Invalid " + key);
            boolean defaultValue = "deepSleepGoogleWhitelist".equals(key) || "disableGoogleNetworkControl".equals(key);
            if ("dozeGoogleWhitelist".equals(key)) defaultValue = flags.get("deepSleepGoogleWhitelist");
            flags.put(key, value == null ? defaultValue : Boolean.TRUE.equals(value));
        }
        if (!flags.get("deepSleepGoogleWhitelist")) flags.put("rootDeepSleepNetworkWhitelist", false);
        options = Collections.unmodifiableMap(flags);
    }
}
