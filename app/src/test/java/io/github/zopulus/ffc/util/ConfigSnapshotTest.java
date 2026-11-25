package io.github.zopulus.ffc.util;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class ConfigSnapshotTest {
    private static Map<String, Object> storedValues() {
        Map<String, Object> values = new HashMap<>();
        values.put("init", true);
        values.put("revision", 7L);
        values.put("allowList", Set.of("target"));
        values.put("installEvents", Set.of("installation"));
        for (String key : ConfigSchema.OPTIONS) values.put(key, ConfigSchema.defaultValue(key));
        return values;
    }

    @Test public void matchingRemoteValuesCanSkipWriting() {
        Map<String, Object> values = storedValues();
        ConfigSnapshot snapshot = new ConfigSnapshot(values);
        assertTrue(snapshot.matchesPreferences(values));
        values.put("unrelated", "preserve");
        assertTrue(snapshot.matchesPreferences(values));
    }

    @Test public void matchingRevisionStillRequiresAllConfigurationContent() {
        Map<String, Object> values = storedValues();
        ConfigSnapshot snapshot = new ConfigSnapshot(values);
        Map<String, Object> changed = new HashMap<>(values);
        changed.put("allowList", Set.of("other"));
        assertFalse(snapshot.matchesPreferences(changed));
        changed = new HashMap<>(values);
        changed.put("installEvents", Set.of("another installation"));
        assertFalse(snapshot.matchesPreferences(changed));
        for (String key : ConfigSchema.OPTIONS) {
            changed = new HashMap<>(values);
            changed.put(key, !snapshot.options.get(key));
            assertFalse(key, snapshot.matchesPreferences(changed));
        }
        changed = new HashMap<>(values);
        changed.put("revision", 8L);
        assertFalse(snapshot.matchesPreferences(changed));
    }

    @Test public void uninitializedAndPartialPreferencesMustBeWritten() {
        Map<String, Object> values = storedValues();
        ConfigSnapshot snapshot = new ConfigSnapshot(values);
        for (String key : values.keySet()) {
            Map<String, Object> partial = new HashMap<>(values);
            partial.remove(key);
            assertFalse(key, snapshot.matchesPreferences(partial));
        }
        values.put("init", false);
        assertFalse(snapshot.matchesPreferences(values));
    }

    @Test public void retiredOptionsRequireMigrationEvenWhenCurrentValuesMatch() {
        Map<String, Object> values = storedValues();
        ConfigSnapshot snapshot = new ConfigSnapshot(values);
        for (String key : ConfigSchema.RETIRED_KEYS) {
            Map<String, Object> legacy = new HashMap<>(values);
            legacy.put(key, false);
            assertFalse(key, snapshot.matchesPreferences(legacy));
        }
    }

    @Test public void revisionIsPreservedAndInvalidValuesRejected() {
        assertEquals(0, new ConfigSnapshot(Collections.emptyMap()).revision);
        assertEquals(123, new ConfigSnapshot(Collections.singletonMap("revision", 123L)).revision);
        assertThrows(IllegalArgumentException.class, () -> new ConfigSnapshot(Collections.singletonMap("revision", -1L)));
        assertThrows(IllegalArgumentException.class, () -> new ConfigSnapshot(Collections.singletonMap("revision", "123")));
    }

    @Test public void activeOptionsKeepDefaultsRespectOverridesAndIgnoreRetiredKeys() {
        ConfigSnapshot defaults = new ConfigSnapshot(Collections.emptyMap());
        assertTrue(defaults.options.get("adaptiveWifiHeartbeat"));
        assertTrue(defaults.options.get("autoAllowNewApps"));
        for (String retired : ConfigSchema.RETIRED_KEYS) {
            assertFalse(retired, defaults.options.containsKey(retired));
        }

        Map<String, Object> values = new HashMap<>();
        values.put("adaptiveWifiHeartbeat", false);
        values.put("autoAllowNewApps", false);
        values.put("deepSleepGoogleWhitelist", false);
        values.put("dozeGoogleWhitelist", false);
        values.put("rootDeepSleepNetworkWhitelist", true);
        ConfigSnapshot custom = new ConfigSnapshot(values);
        assertFalse(custom.options.get("adaptiveWifiHeartbeat"));
        assertFalse(custom.options.get("autoAllowNewApps"));
        assertFalse(custom.options.containsKey("deepSleepGoogleWhitelist"));
        assertFalse(custom.options.containsKey("dozeGoogleWhitelist"));
        assertFalse(custom.options.containsKey("rootDeepSleepNetworkWhitelist"));
    }
    @Test public void missingOptionsUseSafeDefaults() {
        ConfigSnapshot snapshot = new ConfigSnapshot(Collections.emptyMap());
        assertTrue(snapshot.allowList.isEmpty());
        assertFalse(snapshot.options.get("disableAutoCleanNotification"));
        assertFalse(snapshot.options.get("includeIceBoxDisableApp"));
    }
    @Test public void snapshotDoesNotShareMutableState() {
        Set<String> packages = new HashSet<>(Collections.singleton("target"));
        Map<String, Object> values = new HashMap<>();
        values.put("allowList", packages); values.put("includeIceBoxDisableApp", true);
        ConfigSnapshot snapshot = new ConfigSnapshot(values);
        packages.clear(); values.put("includeIceBoxDisableApp", false);
        assertTrue(snapshot.allowList.contains("target"));
        assertTrue(snapshot.options.get("includeIceBoxDisableApp"));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.allowList.add("evil"));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.options.put("disableAutoCleanNotification", true));
    }
    @Test public void corruptConfigIsNotPublished() {
        assertThrows(IllegalArgumentException.class,
                () -> new ConfigSnapshot(Collections.singletonMap("allowList", "not a set")));
        assertThrows(IllegalArgumentException.class,
                () -> new ConfigSnapshot(Collections.singletonMap("includeIceBoxDisableApp", "true")));
    }
}
