package io.github.zopulus.ffc;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import android.provider.Settings;
import io.github.zopulus.ffc.util.ConfigFile;
import io.github.zopulus.ffc.util.ConfigSchema;
import io.github.zopulus.ffc.util.ConfigCodec;
import io.github.zopulus.ffc.util.ConfigSnapshot;
import io.github.zopulus.ffc.util.HookHealth;
import io.github.zopulus.ffc.util.IceboxUtils;
import org.json.JSONArray;
import org.json.JSONObject;

/** Only the module, system server and the scoped Battery process can read configuration. */
public class ConfigProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
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
            ConfigSnapshot snapshot =
                    ConfigCodec.fromJson(ConfigFile.read(getContext()));
            data.addRow(new Object[]{"init", "1"});
            data.addRow(new Object[]{"revision", Long.toString(snapshot.revision)});
            for (String key : ConfigSchema.OPTIONS)
                data.addRow(new Object[]{key, snapshot.options.get(key) ? "1" : "0"});
            for (String name : snapshot.allowList) data.addRow(new Object[]{"allowList", name});
        } catch (Exception error) { android.util.Log.w("fcmfix", "Config file unavailable", error); }
        return data;
    }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        requireReader();
        SharedPreferences status = getContext().getSharedPreferences("hook_status", Context.MODE_PRIVATE);
        int boot = Settings.Global.getInt(getContext().getContentResolver(), "boot_count", -1);
        if ("recordHookStatus".equals(method)) {
            int uid = Binder.getCallingUid();
            if (extras == null || uid == Process.myUid()) throw new SecurityException("Invalid hook status reporter");
            String reporter = extras.getString("reporter");
            String prefix;
            if ("android".equals(reporter) && uid == Process.SYSTEM_UID) prefix = "system";
            else if ("com.oplus.battery".equals(reporter) && isBattery(uid)) prefix = "battery";
            else throw new SecurityException("Invalid hook status source");
            boolean saved = status.edit().putInt(prefix + ".boot", boot)
                    .putInt(prefix + ".version", extras.getInt("version"))
                    .putBoolean(prefix + ".active", extras.getBoolean("active"))
                    .putBoolean(prefix + ".doze", extras.getBoolean("doze"))
                    .putBoolean(prefix + ".alarm", extras.getBoolean("alarm"))
                    .putBoolean(prefix + ".deepSleepAlarm", extras.getBoolean("deepSleepAlarm"))
                    .putString(prefix + ".oplusProtections", extras.getString("oplusProtections", ""))
                    .putBoolean(prefix + ".deepSleep", extras.getBoolean("deepSleep"))
                    .putBoolean(prefix + ".network", extras.getBoolean("network"))
                    .putBoolean(prefix + ".nightWhitelist", extras.getBoolean("nightWhitelist"))
                    .putBoolean(prefix + ".networkFirewallGuarded", extras.getBoolean("networkFirewallGuarded"))
                    .putString(prefix + ".networkPolicyState", extras.getString("networkPolicyState", "pending"))
                    .putString(prefix + ".networkPolicyDetail", extras.getString("networkPolicyDetail", "")).commit();
            if (!saved) throw new IllegalStateException("Hook status persistence failed");
            return Bundle.EMPTY;
        }
        if ("hookStatus".equals(method)) {
            boolean system = HookHealth.isCurrent(status.getInt("system.boot", -2),
                    status.getInt("system.version", -1), boot, BuildConfig.VERSION_CODE)
                    && status.getBoolean("system.active", false);
            boolean battery = HookHealth.isCurrent(status.getInt("battery.boot", -2),
                    status.getInt("battery.version", -1), boot, BuildConfig.VERSION_CODE);
            boolean needSleep = true, needDoze = true;
            try {
                ConfigSnapshot config =
                        ConfigCodec.fromJson(ConfigFile.read(getContext()));
                needSleep = config.options.get("deepSleepGoogleWhitelist");
                needDoze = config.options.get("dozeGoogleWhitelist");
            } catch (Exception ignored) {}
            Bundle result = new Bundle();
            result.putBoolean("doze", system && status.getBoolean("system.doze", false));
            result.putBoolean("alarm", system && status.getBoolean("system.alarm", false));
            result.putBoolean("deepSleepAlarm", system && status.getBoolean("system.deepSleepAlarm", false));
            result.putString("oplusProtections", system ? status.getString("system.oplusProtections", "") : "");
            result.putBoolean("deepSleep", system && status.getBoolean("system.nightWhitelist", false));
            result.putBoolean("network", battery && status.getBoolean("battery.network", false));
            String missing = HookHealth.missing(
                    result.getString("oplusProtections", ""), needDoze, needSleep,
                    result.getBoolean("doze"), result.getBoolean("alarm"), result.getBoolean("deepSleepAlarm"),
                    result.getBoolean("deepSleep"), result.getBoolean("network"));
            String policyState = battery ? status.getString("battery.networkPolicyState", "pending") : "pending";
            boolean guarded = system && status.getBoolean("system.networkFirewallGuarded", false);
            result.putBoolean("networkFirewallGuarded", guarded);
            if ("record_restricted".equals(policyState) && guarded) policyState = "protected";
            result.putString("missingHooks", missing);
            result.putString("networkPolicyState", policyState);
            result.putString("networkPolicyDetail", battery ? status.getString("battery.networkPolicyDetail", "") : "Awaiting current boot report");
            result.putBoolean("hooksInstalled", system && battery && missing.isEmpty());
            result.putBoolean("networkPolicyVerified", "verified".equals(policyState));
            result.putBoolean("networkConnectionVerified", false);
            if ("protected".equals(policyState)) result.putString("networkPolicyDetail",
                    result.getString("networkPolicyDetail", "") + "; firewall guard armed, effective connection not verified");
            result.putBoolean("active", HookHealth.isHealthy(system, battery, missing, policyState));
            return result;
        }
        if ("activateFrozenApp".equals(method)) {
            if (Binder.getCallingUid() != Process.SYSTEM_UID || arg == null) throw new SecurityException("Invalid activation request");
            Bundle result = new Bundle();
            long deadline = extras == null ? 0 : extras.getLong("deadline", 0);
            long now = android.os.SystemClock.elapsedRealtime();
            if (!io.github.zopulus.ffc.util.ActivationDeadline.isValid(deadline, now)) return result;
            long identity = Binder.clearCallingIdentity();
            try {
                JSONObject config = ConfigFile.read(getContext());
                if (!config.optBoolean("includeIceBoxDisableApp", false)) return result;
                JSONArray allowed = config.getJSONArray("allowList");
                boolean permitted = false;
                for (int i = 0; i < allowed.length(); i++) if (arg.equals(allowed.getString(i))) permitted = true;
                if (!permitted) return result;
                IceboxUtils.activeAppBeforeDeadline(getContext(), arg, deadline);
                result.putBoolean("enabled", IceboxUtils.isAppEnabled(getContext(), arg));
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
