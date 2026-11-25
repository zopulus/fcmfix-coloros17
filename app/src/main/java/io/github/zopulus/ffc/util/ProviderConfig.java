package io.github.zopulus.ffc.util;

import android.content.Context;
import android.os.Bundle;
import io.github.libxposed.service.XposedService;
import io.github.zopulus.ffc.ModuleService;
import java.io.FileNotFoundException;
import java.io.IOException;
import org.json.JSONArray;
import org.json.JSONObject;

/** Resolve the configuration again in the process that will perform the operation. */
public final class ProviderConfig {
    private ProviderConfig() {}
    public static JSONObject latest(Context context, Bundle extras, boolean recheckRemote) throws Exception {
        JSONObject remote = null;
        String reported = extras.getString("remoteConfig");
        if (reported != null) remote = new JSONObject(reported);
        // A cold manager can use the reporting process's snapshot without a second IPC read.
        // An older reporter, or one that could not read preferences, needs the bound service.
        boolean checked = extras.getBoolean("remoteChecked");
        XposedService service = ModuleService.INSTANCE.getConnection().getValue();
        if (service != null && (!checked || recheckRemote)) {
            try {
                ConfigSnapshot current = ConfigCodec.fromPreferences(service.getRemotePreferences("config").getAll());
                if (current != null) remote = ConfigPersistence.newest(remote, ConfigCodec.toJson(current));
                checked = true;
            } catch (RuntimeException unavailable) {
                if (!checked) throw unavailable;
            }
        }
        if (!checked) throw new IOException("Remote configuration unavailable");
        ConfigSnapshot pending = ModuleService.INSTANCE.getRemoteOnlyEdit();
        if (pending != null) remote = ConfigPersistence.newest(remote, ConfigCodec.toJson(pending));
        JSONObject local;
        try {
            local = ConfigFile.read(context);
            ConfigCodec.fromJson(local);
        } catch (FileNotFoundException missing) {
            local = null;
        } catch (Exception unreadable) {
            if (remote == null) throw unreadable;
            local = null;
        }
        JSONObject json = ConfigPersistence.reconcile(local, remote, System.currentTimeMillis());
        return json == null ? new JSONObject().put("allowList", new JSONArray()) : json;
    }
}
