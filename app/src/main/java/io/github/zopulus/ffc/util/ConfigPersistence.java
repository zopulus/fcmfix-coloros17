package io.github.zopulus.ffc.util;

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

    public static Result save(Store local, Store remote, JSONObject previous, JSONObject requested,
                              long requestedRevision, long now) throws Exception {
        JSONObject current = readValid(local);
        JSONObject remoteConfig = readValid(remote);
        long currentRevision = current == null ? -1 : current.optLong("revision", 0);
        long remoteRevision = remoteConfig == null ? -1 : remoteConfig.optLong("revision", 0);
        if (remoteConfig != null && (current == null
                || remoteRevision > currentRevision
                || current.optBoolean("bootstrapDefaults", false)
                    && remoteRevision == currentRevision)) {
            current = remoteConfig;
            currentRevision = remoteRevision;
        }
        if (current == null) {
            current = previous;
            currentRevision = previous.optLong("revision", 0);
        }
        JSONObject merged = ConfigEdits.merge(current, previous, requested);
        merged.put("revision", ConfigEdits.nextRevision(
                Math.max(currentRevision, requestedRevision - 1), now));
        Result result = new Result(merged);
        try { local.write(merged); result.localSaved = true; }
        catch (Exception error) { result.localFailure = error; }
        try { remote.write(merged); result.remoteSaved = true; }
        catch (Exception unavailable) { result.remoteSaved = false; }
        return result;
    }
}
