package io.github.zopulus.ffc.util;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.util.Set;
import static org.junit.Assert.*;

public class ConfigEditsTest {
    private JSONObject config(String... packages) throws Exception {
        return new JSONObject().put("allowList", new JSONArray(packages))
                .put("autoAllowNewApps", true);
    }

    @Test public void unrelatedUiEditPreservesConcurrentAutomaticAddition() throws Exception {
        JSONObject previous = config("manual");
        JSONObject requested = config("manual").put("disableAutoCleanNotification", true);
        JSONObject merged = ConfigEdits.merge(config("manual", "automatic"), previous, requested);
        assertEquals(Set.of("manual", "automatic"), ConfigCodec.fromJson(merged).allowList);
        assertTrue(merged.getBoolean("disableAutoCleanNotification"));
    }

    @Test public void manualRemovalStaysOffUntilANewInstallation() throws Exception {
        JSONObject merged = ConfigEdits.merge(config("target"), config("target"),
                config().put("autoAllowNewApps", false));
        assertTrue(ConfigCodec.fromJson(merged).allowList.isEmpty());
        JSONObject ordinaryReload = new JSONObject(merged.toString());
        assertTrue(ConfigCodec.fromJson(ordinaryReload).allowList.isEmpty());
        merged.put("autoAllowNewApps", true);
        assertFalse(NewAppAutoAllow.add(merged, "target", true, false, "update-event"));
        assertTrue(NewAppAutoAllow.add(merged, "target", true, true, "new-event"));
    }

    @Test public void editsRetireLegacyExclusionsWithoutChangingOtherPackages() throws Exception {
        JSONObject current = config("other").put("autoAllowExcluded", new JSONArray("[\"target\",\"excluded\"]"));
        JSONObject merged = ConfigEdits.merge(current, config("other"), config("other", "target"));
        assertEquals(Set.of("other", "target"), ConfigCodec.fromJson(merged).allowList);
        assertFalse(merged.has("autoAllowExcluded"));
    }

    @Test public void staleUiSnapshotDoesNotOverwriteAnUnrelatedOption() throws Exception {
        JSONObject previous = config();
        JSONObject current = config().put("includeIceBoxDisableApp", true);
        JSONObject merged = ConfigEdits.merge(current, previous, config("target"));
        assertTrue(merged.getBoolean("includeIceBoxDisableApp"));
    }

    @Test public void revisionAlwaysMovesForwardAndOverflowIsRejected() {
        assertEquals(101, ConfigEdits.nextRevision(100, 20));
        assertEquals(200, ConfigEdits.nextRevision(100, 200));
        assertThrows(ArithmeticException.class, () -> ConfigEdits.nextRevision(Long.MAX_VALUE, 1));
    }
}
