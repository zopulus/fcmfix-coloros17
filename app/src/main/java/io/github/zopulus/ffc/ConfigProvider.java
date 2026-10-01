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
            JSONObject json = ConfigFile.read(getContext());
            // Validate everything before publishing init=1 or any cursor rows.
            JSONArray packages = json.getJSONArray("allowList");
            String[] options = {"disableAutoCleanNotification", "includeIceBoxDisableApp", "deepSleepGoogleWhitelist", "rootDeepSleepNetworkWhitelist", "dozeGoogleWhitelist"};
            boolean[] values = new boolean[options.length];
            for (int i = 0; i < options.length; i++) values[i] = json.has(options[i])
                    ? json.getBoolean(options[i]) : i == 2;
            if (!json.has("dozeGoogleWhitelist")) values[4] = values[2];
            values[3] &= values[2];
            java.util.List<String> names = new java.util.ArrayList<>();
            for (int i = 0; i < packages.length(); i++) names.add(packages.getString(i));
            long revision = json.optLong("revision", 0);
            if (revision < 0) throw new IllegalArgumentException("Invalid revision");
            data.addRow(new Object[]{"init", "1"});
            data.addRow(new Object[]{"revision", Long.toString(revision)});
            for (int i = 0; i < options.length; i++) data.addRow(new Object[]{options[i], values[i] ? "1" : "0"});
            for (String name : names) data.addRow(new Object[]{"allowList", name});
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
            status.edit().putInt(prefix + ".boot", boot)
                    .putInt(prefix + ".version", extras.getInt("version"))
                    .putBoolean(prefix + ".active", extras.getBoolean("active"))
                    .putBoolean(prefix + ".doze", extras.getBoolean("doze"))
                    .putBoolean(prefix + ".alarm", extras.getBoolean("alarm"))
                    .putBoolean(prefix + ".deepSleepAlarm", extras.getBoolean("deepSleepAlarm"))
                    .putString(prefix + ".oplusProtections", extras.getString("oplusProtections", ""))
                    .putBoolean(prefix + ".deepSleep", extras.getBoolean("deepSleep"))
                    .putBoolean(prefix + ".network", extras.getBoolean("network"))
                    .putString(prefix + ".networkPolicyState", extras.getString("networkPolicyState", "pending"))
                    .putString(prefix + ".networkPolicyDetail", extras.getString("networkPolicyDetail", "")).commit();
            return Bundle.EMPTY;
        }
        if ("hookStatus".equals(method)) {
            boolean system = io.github.zopulus.ffc.util.HookHealth.isCurrent(status.getInt("system.boot", -2),
                    status.getInt("system.version", -1), boot, BuildConfig.VERSION_CODE)
                    && status.getBoolean("system.active", false);
            boolean battery = io.github.zopulus.ffc.util.HookHealth.isCurrent(status.getInt("battery.boot", -2),
                    status.getInt("battery.version", -1), boot, BuildConfig.VERSION_CODE);
            boolean needSleep = true, needDoze = true;
            try {
                JSONObject config = ConfigFile.read(getContext());
                needSleep = config.optBoolean("deepSleepGoogleWhitelist", true);
                needDoze = config.optBoolean("dozeGoogleWhitelist", needSleep);
            } catch (Exception ignored) {}
            Bundle result = new Bundle();
            result.putBoolean("doze", system && status.getBoolean("system.doze", false));
            result.putBoolean("alarm", system && status.getBoolean("system.alarm", false));
            result.putBoolean("deepSleepAlarm", system && status.getBoolean("system.deepSleepAlarm", false));
            result.putString("oplusProtections", system ? status.getString("system.oplusProtections", "") : "");
            result.putBoolean("deepSleep", battery && status.getBoolean("battery.deepSleep", false));
            result.putBoolean("network", battery && status.getBoolean("battery.network", false));
            String missing = io.github.zopulus.ffc.util.HookHealth.missing(
                    result.getString("oplusProtections", ""), needDoze, needSleep,
                    result.getBoolean("doze"), result.getBoolean("alarm"), result.getBoolean("deepSleepAlarm"),
                    result.getBoolean("deepSleep"), result.getBoolean("network"));
            String policyState = battery ? status.getString("battery.networkPolicyState", "pending") : "pending";
            result.putString("missingHooks", missing);
            result.putString("networkPolicyState", policyState);
            result.putString("networkPolicyDetail", battery ? status.getString("battery.networkPolicyDetail", "") : "Awaiting current boot report");
            result.putBoolean("hooksInstalled", system && battery && missing.isEmpty());
            result.putBoolean("networkPolicyVerified", "verified".equals(policyState));
            result.putBoolean("active", io.github.zopulus.ffc.util.HookHealth.isHealthy(system, battery, missing, policyState));
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
