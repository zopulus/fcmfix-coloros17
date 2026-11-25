package io.github.zopulus.ffc;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import io.github.zopulus.ffc.util.ConfigFile;
import io.github.zopulus.ffc.util.ConfigSchema;
import io.github.zopulus.ffc.util.ConfigCodec;
import io.github.zopulus.ffc.util.ConfigSnapshot;
import io.github.zopulus.ffc.util.IceboxUtils;
import io.github.zopulus.ffc.util.ProviderConfig;

/** Only the module, system server and the scoped Battery process can read configuration. */
public class ConfigProvider extends ContentProvider {
    @Override public boolean onCreate() {
        ModuleService.INSTANCE.bindXposedService(getContext());
        return true;
    }
    private boolean isBattery(int uid) {
        try { return getContext().getPackageManager().getPackageUid("com.oplus.battery", 0) == uid; }
        catch (Exception ignored) { return false; }
    }
    private void requireReader() {
        int uid = Binder.getCallingUid();
        if (uid != Process.myUid() && uid != Process.SYSTEM_UID && !isBattery(uid))
            throw new SecurityException("Configuration reader is not authorized");
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        requireReader();
        MatrixCursor data = new MatrixCursor(new String[]{"key", "value"});
        if (!"/config".equals(uri.getPath())) return data;
        try {
            org.json.JSONObject local = ConfigFile.read(getContext());
            ConfigSnapshot snapshot = ConfigCodec.fromJson(local);
            if (ConfigSchema.HOOK_SNAPSHOT.equals(selection)) {
                // The unmodified file carries the baseline needed to replay offline edits.
                data.addRow(new Object[]{ConfigSchema.HOOK_SNAPSHOT, local.toString()});
                return data;
            }
            if (local.optBoolean("bootstrapDefaults")) data.addRow(new Object[]{"bootstrapDefaults", "1"});
            data.addRow(new Object[]{"init", "1"});
            data.addRow(new Object[]{"revision", Long.toString(snapshot.revision)});
            for (String key : ConfigSchema.OPTIONS)
                data.addRow(new Object[]{key, snapshot.options.get(key) ? "1" : "0"});
            for (String name : snapshot.allowList) data.addRow(new Object[]{"allowList", name});
        } catch (Exception error) { android.util.Log.w("fcmfix", "Config file unavailable", error); }
        return data;
    }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        if ("newAppInstalled".equals(method)) {
            return io.github.zopulus.ffc.util.NewAppInstallStore.handle(getContext(), arg, extras);
        }
        requireReader();
        if ("activateFrozenApp".equals(method)) {
            if (Binder.getCallingUid() != Process.SYSTEM_UID || arg == null) throw new SecurityException("Invalid activation request");
            Bundle result = new Bundle();
            long deadline = extras == null ? 0 : extras.getLong("deadline", 0);
            long now = android.os.SystemClock.elapsedRealtime();
            if (!io.github.zopulus.ffc.util.ActivationDeadline.isValid(deadline, now)) return result;
            long identity = Binder.clearCallingIdentity();
            try {
                result.putBoolean("enabled", IceboxUtils.activeAppBeforeDeadline(getContext(), arg, deadline, () -> {
                    ConfigSnapshot config = ConfigCodec.fromJson(ProviderConfig.latest(getContext(), extras, true));
                    return config.permitsIceBox(arg, extras.getLong("minimumRevision", 0));
                }));
            } catch (Exception error) { android.util.Log.w("fcmfix", "Frozen app activation failed", error); }
            finally { Binder.restoreCallingIdentity(identity); }
            return result;
        }
        return super.call(method, arg, extras);
    }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
