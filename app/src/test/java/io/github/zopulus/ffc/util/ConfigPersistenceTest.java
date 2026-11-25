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

    @Test public void newerRemoteOptOutBlocksEnrollmentUsingOldLocalSettings() throws Exception {
        JSONObject local = config(1).put(ConfigSchema.KEY_AUTO_ALLOW_NEW_APPS, true);
        JSONObject remote = config(2, "existing").put(ConfigSchema.KEY_AUTO_ALLOW_NEW_APPS, false);
        JSONObject current = ConfigPersistence.newest(local, remote);
        assertFalse(NewAppAutoAllow.add(current, "new.app", true, true, "event"));
        assertEquals(Set.of("existing"), ConfigCodec.fromJson(current).allowList);
        assertEquals(2, current.getLong("revision"));
    }

    @Test public void queuedInstallHonorsRemoteOnlyEditMadeAfterReport() throws Exception {
        JSONObject reported = config(7).put(ConfigSchema.KEY_AUTO_ALLOW_NEW_APPS, true);
        Store local = new Store(config(7)), remote = new Store(reported);
        local.writeFails = true;
        ConfigPersistence.Result saved = ConfigPersistence.save(local, remote, reported,
                config(7).put(ConfigSchema.KEY_AUTO_ALLOW_NEW_APPS, false), 8, 9);
        assertTrue(saved.remoteSaved && !saved.localSaved);
        JSONObject available = ConfigPersistence.newest(reported, saved.config);
        JSONObject current = ConfigPersistence.newest(local.value, available);
        assertFalse(NewAppAutoAllow.add(current, "new.app", true, true, "event"));
    }

    @Test public void newerLocalInstallKeepsRemoteReceiptAndBothAllowedPackages() throws Exception {
        JSONObject remote = config(5, "manual").put("installEvents", new JSONArray().put("older"));
        JSONObject reported = ConfigCodec.toJson(ConfigCodec.fromJson(remote));
        JSONObject current = ConfigPersistence.newest(config(2), reported);
        assertTrue(NewAppAutoAllow.add(current, "new.app", true, true, "new-event"));
        current.put("revision", 6);
        assertSame(current, ConfigPersistence.newest(current, remote));
        assertEquals(Set.of("older", "new-event"), ConfigCodec.fromJson(current).installEvents);
        assertEquals(Set.of("manual", "new.app"), ConfigCodec.fromJson(current).allowList);
    }

    @Test public void failedRemoteReadCannotTriggerAWriteAndNextSyncUsesRecoveredCopy() throws Exception {
        Store local = new Store(config(4, "old")), remote = new Store(config(8, "latest"));
        remote.readFails = true;
        assertEquals(4, ConfigPersistence.synchronize(local, remote, null, 10).getLong("revision"));
        assertEquals(0, remote.writes);
        remote.readFails = false;
        assertEquals(8, ConfigPersistence.synchronize(local, remote, null, 11).getLong("revision"));
        assertEquals(Set.of("latest"), ConfigCodec.fromJson(local.value).allowList);
        assertEquals(0, remote.writes);
    }

    @Test public void remoteChangeBeforeCommitReappliesOnlyRequestedEdits() throws Exception {
        Store local = new Store(config(1, "a"));
        Store remote = new Store(config(1, "a")) {
            boolean first = true;
            @Override public void write(JSONObject json) throws Exception {
                if (first) {
                    first = false;
                    value = config(30, "a", "concurrent");
                    throw new ConfigPersistence.RemoteChanged();
                }
                super.write(json);
            }
        };
        ConfigPersistence.save(local, remote, config(1, "a"), config(1, "b"), 9, 20);
        assertEquals(Set.of("b", "concurrent"), ConfigCodec.fromJson(remote.value).allowList);
        assertEquals(remote.value.toString(), local.value.toString());
        assertEquals(1, local.writes);
    }

    @Test public void passiveRepairRereadsRemoteBeforeOverwriting() throws Exception {
        Store local = new Store(config(5, "old"));
        Store remote = new Store(config(1)) {
            @Override public void write(JSONObject ignored) throws Exception {
                value = config(8, "fresh");
                throw new ConfigPersistence.RemoteChanged();
            }
        };
        ConfigPersistence.synchronize(local, remote, null, 20);
        assertEquals(Set.of("fresh"), ConfigCodec.fromJson(local.value).allowList);
        assertEquals(8, local.value.getLong("revision"));
    }

    @Test public void offlineEditMergesAfterRestartEvenWhenItsTimestampIsHigher() throws Exception {
        Store local = new Store(config(1, "removed"));
        Store remote = new Store(config(4, "removed", "remote").put("autoAllowNewApps", false));
        remote.readFails = true;
        ConfigPersistence.save(local, remote, config(1, "removed"), config(1, "manual"), 99, 100);
        assertEquals(0, remote.writes);
        Store reopened = new Store(new JSONObject(local.value.toString()));
        remote.readFails = false;
        ConfigPersistence.synchronize(reopened, remote, null, 105);
        ConfigSnapshot actual = ConfigCodec.fromJson(remote.value);
        assertEquals(Set.of("manual", "remote"), actual.allowList);
        assertFalse(actual.options.get("autoAllowNewApps"));
        assertEquals(reopened.value.toString(), remote.value.toString());
        assertFalse(remote.value.has("pendingRemoteBase"));
    }

    @Test public void iceBoxRequiresBothLatestPermissionAndCurrentRevision() throws Exception {
        JSONObject local = config(1);
        JSONObject remote = config(2, "frozen").put("includeIceBoxDisableApp", true);
        ConfigSnapshot enabled = ConfigCodec.fromJson(ConfigPersistence.reconcile(local, remote, 5));
        assertTrue(enabled.permitsIceBox("frozen", 2));
        assertFalse(enabled.permitsIceBox("other", 2));
        assertFalse(enabled.permitsIceBox("frozen", 3));
        JSONObject removed = config(3).put("includeIceBoxDisableApp", true);
        assertFalse(ConfigCodec.fromJson(ConfigPersistence.reconcile(removed, remote, 5)).permitsIceBox("frozen", 2));
        assertFalse(ConfigCodec.fromJson(config(4, "frozen")).permitsIceBox("frozen", 2));
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

    @Test public void backgroundAutoInstallWithRecordedOfflineBaseSurvivesNewerRemoteSync() throws Exception {
        JSONObject baseline = config(10, "existing");
        JSONObject local = new JSONObject(baseline.toString());
        assertTrue(NewAppAutoAllow.add(local, "new.fcm.app", true, true, "evt-1"));
        local.put("revision", ConfigEdits.nextRevision(10, 50));
        ConfigPersistence.recordOfflineBase(local, baseline);

        Store localStore = new Store(local);
        Store remoteStore = new Store(config(200, "existing", "remote.app"));
        JSONObject synced = ConfigPersistence.synchronize(localStore, remoteStore, null, 205);
        ConfigSnapshot snapshot = ConfigCodec.fromJson(synced);
        assertEquals(Set.of("existing", "remote.app", "new.fcm.app"), snapshot.allowList);
        assertEquals(Set.of("evt-1"), snapshot.installEvents);
        assertFalse(localStore.value.has("pendingRemoteBase"));
        assertFalse(remoteStore.value.has("pendingRemoteBase"));
        assertEquals(localStore.value.toString(), remoteStore.value.toString());
    }
}
