package io.github.zopulus.ffc.util;

import java.util.LinkedHashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** One merged revision is independently attempted in both existing configuration stores. */
public final class ConfigPersistence {
    private ConfigPersistence() {}
    public interface Store {
        JSONObject read() throws Exception;
        void write(JSONObject json) throws Exception;
    }
    public static final class Result {
        public final JSONObject config;
        public boolean localSaved, remoteSaved;
        public Exception localFailure;
        private Result(JSONObject config) { this.config = config; }
    }

    private static JSONObject readValid(Store store) {
        try {
            JSONObject config = store.read();
            if (config != null) ConfigCodec.fromJson(config);
            return config;
        } catch (Exception unavailable) { return null; }
    }

    public static boolean preferRemote(JSONObject local, long remoteRevision) {
        if (local == null) return true;
        long localRevision = local.optLong("revision", 0);
        return remoteRevision > localRevision ||
                remoteRevision == localRevision && local.optBoolean("bootstrapDefaults", false);
    }

    static JSONObject newest(JSONObject local, JSONObject remote) {
        return remote != null && preferRemote(local, remote.optLong("revision", 0)) ? remote : local;
    }

    /** A fresh read is required before retrying a preference write that raced a newer snapshot. */
    public static final class RemoteChanged extends java.io.IOException { }

    private static final String REMOTE_BASE = "pendingRemoteBase";

    /** Record the pre-edit baseline when mutating local storage without an immediate remote write. */
    public static void recordOfflineBase(JSONObject mutated, JSONObject baselineBeforeEdit) throws Exception {
        if (mutated == null || baselineBeforeEdit == null || mutated.has(REMOTE_BASE)) return;
        JSONObject base = new JSONObject(baselineBeforeEdit.toString());
        base.remove(REMOTE_BASE);
        base.remove("bootstrapDefaults");
        mutated.put(REMOTE_BASE, base);
    }

    private static JSONObject readRemote(Store remote) throws Exception {
        JSONObject json = remote.read();
        if (json != null) ConfigCodec.fromJson(json);
        return json;
    }

    // Offline edits carry their original baseline only in the local file. Reapply their
    // deltas onto a newer remote snapshot instead of letting a newer wall clock win.
    static JSONObject reconcile(JSONObject local, JSONObject remote, long now) throws Exception {
        JSONObject base = local == null ? null : local.optJSONObject(REMOTE_BASE);
        if (remote == null || base == null || !preferRemote(base, remote.optLong("revision", 0)))
            return newest(local, remote);
        JSONObject merged = ConfigEdits.merge(remote, base, local);
        ConfigSnapshot remoteSnapshot = ConfigCodec.fromJson(remote);
        LinkedHashSet<String> events = new LinkedHashSet<>(remoteSnapshot.installEvents);
        Set<String> oldEvents = ConfigCodec.fromJson(base).installEvents;
        for (String event : ConfigCodec.fromJson(local).installEvents)
            if (!oldEvents.contains(event)) events.add(event);
        while (events.size() > 64) events.remove(events.iterator().next());
        merged.put(ConfigSchema.KEY_INSTALL_EVENTS, new JSONArray(events));
        ConfigSnapshot result = ConfigCodec.fromJson(merged);
        if (remoteSnapshot.revision >= local.optLong("revision")
                && result.allowList.equals(remoteSnapshot.allowList)
                && result.options.equals(remoteSnapshot.options)
                && result.installEvents.equals(remoteSnapshot.installEvents)) return remote;
        merged.put(REMOTE_BASE, remote);
        merged.put("revision", ConfigEdits.nextRevision(Math.max(local.optLong("revision"), remote.optLong("revision")), now));
        return merged;
    }

    public static Result save(Store local, Store remote, JSONObject previous, JSONObject requested,
                              long requestedRevision, long now) throws Exception {
        return save(local, remote, previous, requested, requestedRevision, now, null);
    }

    public static Result save(Store local, Store remote, JSONObject previous, JSONObject requested,
                              long requestedRevision, long now, JSONObject remoteOnlyEdit) throws Exception {
        JSONObject disk = newest(readValid(local), remoteOnlyEdit);
        for (int attempt = 0; ; attempt++) {
            JSONObject fromRemote = null;
            boolean readable = true;
            try { fromRemote = readRemote(remote); } catch (Exception unavailable) { readable = false; }
            JSONObject current = reconcile(disk, fromRemote, now);
            if (current == null) current = previous;
            JSONObject merged = ConfigEdits.merge(current, previous, requested);
            merged.remove(REMOTE_BASE);
            merged.put("revision", ConfigEdits.nextRevision(Math.max(current.optLong("revision"), requestedRevision - 1), now));
            Result result = new Result(merged);
            if (readable) {
                try { remote.write(merged); result.remoteSaved = true; }
                catch (RemoteChanged race) { if (attempt < 2) continue; throw race; }
                catch (Exception unavailable) { result.remoteSaved = false; }
            }
            if (!result.remoteSaved) {
                JSONObject base = current.optJSONObject(REMOTE_BASE);
                merged.put(REMOTE_BASE, base != null ? base : new JSONObject(current.toString()));
            }
            try { local.write(merged); result.localSaved = true; }
            catch (Exception error) { result.localFailure = error; }
            return result;
        }
    }

    public static JSONObject synchronize(Store local, Store remote, JSONObject memory, long now) throws Exception {
        JSONObject disk = readValid(local);
        JSONObject known = newest(disk, memory);
        for (int attempt = 0; ; attempt++) {
            JSONObject fromRemote;
            try { fromRemote = readRemote(remote); }
            catch (Exception unavailable) { return known; }
            JSONObject selected = reconcile(known, fromRemote, now);
            if (selected == null) return null;
            boolean clearBase = selected.has(REMOTE_BASE);
            JSONObject clean = clearBase ? new JSONObject(selected.toString()) : selected;
            clean.remove(REMOTE_BASE);
            if (selected != fromRemote) {
                try { remote.write(clean); }
                catch (RemoteChanged race) { if (attempt < 2) continue; throw race; }
                catch (Exception unavailable) { return known; }
            }
            if (clean != disk) {
                try { local.write(clean); } catch (Exception unavailable) { /* Use the verified remote copy. */ }
            }
            return clean;
        }
    }
}
