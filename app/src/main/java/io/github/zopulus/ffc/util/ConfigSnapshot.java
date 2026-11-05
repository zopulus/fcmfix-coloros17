package io.github.zopulus.ffc.util;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** One immutable view of one RemotePreferences read; partial updates are never published. */
public final class ConfigSnapshot {
    public final long revision;
    public final Set<String> allowList;
    public final Set<String> installEvents;
    public final Map<String, Boolean> options;

    public ConfigSnapshot(Map<String, ?> values) {
        Object stamp = values.get("revision");
        if (stamp != null && (!(stamp instanceof Number) || ((Number) stamp).longValue() < 0)) throw new IllegalArgumentException("Invalid revision");
        revision = stamp == null ? 0 : ((Number) stamp).longValue();
        allowList = readStringSet(values, "allowList");
        installEvents = readStringSet(values, ConfigSchema.KEY_INSTALL_EVENTS);
        Map<String, Boolean> flags = new HashMap<>();
        for (String key : ConfigSchema.OPTIONS) {
            Object value = values.get(key);
            if (value != null && !(value instanceof Boolean)) throw new IllegalArgumentException("Invalid " + key);
            boolean defaultValue = ConfigSchema.defaultValue(key);
            if ("dozeGoogleWhitelist".equals(key)) defaultValue = flags.get("deepSleepGoogleWhitelist");
            flags.put(key, value == null ? defaultValue : Boolean.TRUE.equals(value));
        }
        if (!flags.get("deepSleepGoogleWhitelist")) flags.put("rootDeepSleepNetworkWhitelist", false);
        options = Collections.unmodifiableMap(flags);
    }

    /** A no-op write must also preserve initialization and finish schema migration. */
    public boolean matchesPreferences(Map<String, ?> values) {
        if (!Boolean.TRUE.equals(values.get("init"))
                || !Long.valueOf(revision).equals(values.get("revision"))
                || !allowList.equals(values.get("allowList"))
                || !installEvents.equals(values.get(ConfigSchema.KEY_INSTALL_EVENTS))) return false;
        for (String key : ConfigSchema.OPTIONS)
            if (!options.get(key).equals(values.get(key))) return false;
        return Collections.disjoint(values.keySet(), ConfigSchema.RETIRED_KEYS);
    }

    private static Set<String> readStringSet(Map<String, ?> values, String key) {
        Set<String> strings = new LinkedHashSet<>();
        Object raw = values.get(key);
        if (raw != null) {
            if (!(raw instanceof Set<?>)) throw new IllegalArgumentException("Invalid " + key);
            for (Object name : (Set<?>) raw) {
                if (!(name instanceof String)) throw new IllegalArgumentException("Invalid string in " + key);
                strings.add((String) name);
            }
        }
        return Collections.unmodifiableSet(strings);
    }
}
