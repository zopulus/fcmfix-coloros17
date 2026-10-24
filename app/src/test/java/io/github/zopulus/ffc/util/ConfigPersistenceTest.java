package io.github.zopulus.ffc.util;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.io.IOException;
import java.util.Set;
import static org.junit.Assert.*;

public class ConfigPersistenceTest {
    private JSONObject config(long revision, String... allowed) throws Exception {
        return new JSONObject().put("allowList", new JSONArray(allowed)).put("revision", revision);
    }
    private static class Store implements ConfigPersistence.Store {
        JSONObject value;
        boolean readFails, writeFails;
        int writes;
        Store(JSONObject value) { this.value = value; }
        public JSONObject read() throws Exception {
            if (readFails) throw new IOException("unreadable");
            return value;
        }
        public void write(JSONObject json) throws Exception {
            writes++;
            if (writeFails) throw new IOException("unwritable");
            value = new JSONObject(json.toString());
        }
    }

    @Test public void remoteSaveStillRunsWhenLocalWriteFails() throws Exception {
        Store local = new Store(config(1)), remote = new Store(config(1));
        local.writeFails = true;
        ConfigPersistence.Result result = ConfigPersistence.save(local, remote, config(1), config(1, "target"), 2, 10);
        assertFalse(result.localSaved);
        assertTrue(result.remoteSaved);
        assertEquals(1, remote.writes);
        assertEquals(Set.of("target"), ConfigCodec.fromJson(remote.value).allowList);
        assertEquals(10, remote.value.getLong("revision"));
    }

    @Test public void unreadableLocalFallsBackToNewestRemoteWithoutLosingPackages() throws Exception {
        Store local = new Store(null), remote = new Store(config(30, "automatic"));
        local.readFails = true;
        local.writeFails = true;
        ConfigPersistence.Result result = ConfigPersistence.save(local, remote, config(1), config(1, "manual"), 2, 10);
        assertTrue(result.remoteSaved);
        assertEquals(Set.of("automatic", "manual"), ConfigCodec.fromJson(remote.value).allowList);
        assertEquals(31, remote.value.getLong("revision"));
    }

    @Test public void localSaveSurvivesRemoteFailure() throws Exception {
        Store local = new Store(config(1)), remote = new Store(null);
        remote.readFails = true;
        remote.writeFails = true;
        ConfigPersistence.Result result = ConfigPersistence.save(local, remote, config(1), config(1, "target"), 2, 10);
        assertTrue(result.localSaved);
        assertFalse(result.remoteSaved);
        assertEquals(Set.of("target"), ConfigCodec.fromJson(local.value).allowList);
    }

    @Test public void bothFailuresAreReported() throws Exception {
        Store local = new Store(config(1)), remote = new Store(config(1));
        local.writeFails = remote.writeFails = true;
        ConfigPersistence.Result result = ConfigPersistence.save(local, remote, config(1), config(1, "target"), 2, 10);
        assertFalse(result.localSaved);
        assertFalse(result.remoteSaved);
        assertNotNull(result.localFailure);
        assertEquals(1, local.writes);
        assertEquals(1, remote.writes);
    }

    @Test public void newestLocalAdditionAndManualRemovalReachBothStoresWithSameRevision() throws Exception {
        JSONObject current = config(30, "automatic", "removed");
        Store local = new Store(current), remote = new Store(config(1, "removed"));
        ConfigPersistence.Result result = ConfigPersistence.save(local, remote,
                config(1, "removed"), config(1, "manual"), 2, 10);
        assertTrue(result.localSaved && result.remoteSaved);
        assertEquals(Set.of("automatic", "manual"), ConfigCodec.fromJson(local.value).allowList);
        assertFalse(ConfigCodec.fromJson(remote.value).allowList.contains("removed"));
        assertEquals(local.value.toString(), remote.value.toString());
    }

    @Test public void equalRevisionLegacyRemoteWinsOverBootstrapDefaults() throws Exception {
        Store local = new Store(config(0).put("bootstrapDefaults", true));
        Store remote = new Store(config(0, "legacy"));
        ConfigPersistence.save(local, remote, config(0), config(0).put("disableAutoCleanNotification", true), 1, 10);
        assertEquals(Set.of("legacy"), ConfigCodec.fromJson(local.value).allowList);
        assertTrue(local.value.getBoolean("disableAutoCleanNotification"));
    }

    @Test public void eventReceiptSurvivesNewestRemoteSelectionAndBothWrites() throws Exception {
        JSONObject latest = config(30).put("installEvents", new JSONArray("[\"handled-event\"]"));
        Store local = new Store(config(1)), remote = new Store(latest);
        ConfigPersistence.save(local, remote, config(1), config(1, "manual"), 2, 10);
        assertEquals(Set.of("handled-event"), ConfigCodec.fromJson(local.value).installEvents);
        assertEquals(Set.of("handled-event"), ConfigCodec.fromJson(remote.value).installEvents);
    }
}
