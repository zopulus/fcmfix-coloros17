package io.github.zopulus.ffc.util;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class ConfigSnapshotTest {
    @Test public void revisionIsPreservedAndInvalidValuesRejected() {
        assertEquals(0, new ConfigSnapshot(Collections.emptyMap()).revision);
        assertEquals(123, new ConfigSnapshot(Collections.singletonMap("revision", 123L)).revision);
        assertThrows(IllegalArgumentException.class, () -> new ConfigSnapshot(Collections.singletonMap("revision", -1L)));
        assertThrows(IllegalArgumentException.class, () -> new ConfigSnapshot(Collections.singletonMap("revision", "123")));
    }
    @Test public void localGoogleFlagsKeepDefaultsAndRespectOverrides() {
        ConfigSnapshot defaults = new ConfigSnapshot(Collections.emptyMap());
        assertTrue(defaults.options.get("deepSleepGoogleWhitelist"));
        assertTrue(defaults.options.get("disableGoogleNetworkControl"));
        assertFalse(defaults.options.get("rootDeepSleepNetworkWhitelist"));
        Map<String, Object> values = new HashMap<>();
        values.put("deepSleepGoogleWhitelist", false);
        values.put("disableGoogleNetworkControl", false);
        values.put("rootDeepSleepNetworkWhitelist", true);
        ConfigSnapshot custom = new ConfigSnapshot(values);
        assertFalse(custom.options.get("deepSleepGoogleWhitelist"));
        assertFalse(custom.options.get("disableGoogleNetworkControl"));
        assertFalse(custom.options.get("rootDeepSleepNetworkWhitelist"));
        values.put("deepSleepGoogleWhitelist", true);
        assertTrue(new ConfigSnapshot(values).options.get("rootDeepSleepNetworkWhitelist"));
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
