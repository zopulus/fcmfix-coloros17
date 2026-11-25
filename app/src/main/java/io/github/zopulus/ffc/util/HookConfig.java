package io.github.zopulus.ffc.util;

import org.json.JSONObject;

/** Used only by the serial hook configuration loader. A transient read failure retains that source. */
public final class HookConfig {
    private JSONObject lastLocal;
    private JSONObject lastRemote;

    public ConfigSnapshot resolve(JSONObject local, JSONObject remote) throws Exception {
        if (local == null && remote == null) throw new IllegalStateException("No configuration source available");
        local = ConfigPersistence.newest(local, lastLocal);
        remote = ConfigPersistence.newest(remote, lastRemote);
        // A read-only merge must not advance its revision each time the same inputs are reloaded.
        JSONObject selected = ConfigPersistence.reconcile(local, remote, 0);
        ConfigSnapshot result = ConfigCodec.fromJson(selected);
        lastLocal = local;
        lastRemote = remote;
        return result;
    }
}
