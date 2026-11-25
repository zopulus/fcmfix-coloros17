package io.github.zopulus.ffc.xposed;

import android.os.Bundle;
import io.github.zopulus.ffc.libxposed.XposedBridge;
import io.github.zopulus.ffc.util.ConfigCodec;
import io.github.zopulus.ffc.util.ConfigSnapshot;

public final class RemoteConfigReport {
    private RemoteConfigReport() {}
    public static void attach(Bundle request) {
        try {
            android.content.SharedPreferences prefs = XposedBridge.getRemotePreferences("config");
            if (prefs == null) return;
            ConfigSnapshot remote = ConfigCodec.fromPreferences(prefs.getAll());
            request.putBoolean("remoteChecked", true);
            if (remote != null) request.putString("remoteConfig", ConfigCodec.toJson(remote).toString());
        } catch (Exception unavailable) {
            request.putBoolean("remoteChecked", false);
        }
    }
}
