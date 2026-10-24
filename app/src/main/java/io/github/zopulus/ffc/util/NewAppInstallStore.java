package io.github.zopulus.ffc.util;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import org.json.JSONObject;

/** Only system server can report a protected package-install broadcast. */
public final class NewAppInstallStore {
    private NewAppInstallStore() {}
    public static Bundle handle(Context context, String target, Bundle extras) {
        if (Binder.getCallingUid() != Process.SYSTEM_UID)
            throw new SecurityException("Only system server may report installations");
        PackageManager pm = context.getPackageManager();
        if (target == null || target.isEmpty() || target.length() > 255)
            throw new IllegalArgumentException("Invalid target package");
        String eventId = extras == null ? null : extras.getString("eventId");
        if (eventId == null || eventId.length() != 36 || !java.util.UUID.fromString(eventId).toString().equals(eventId))
            throw new IllegalArgumentException("Invalid installation event ID");
        Bundle result = new Bundle();
        try {
            boolean changed = ConfigSaveQueue.call(() -> {
                synchronized (ConfigFile.class) {
                    JSONObject json;
                    try { json = ConfigFile.read(context); }
                    catch (java.io.FileNotFoundException missing) {
                        json = new JSONObject().put("allowList", new org.json.JSONArray()).put("revision", 0L);
                    }
                    boolean newInstall = !extras.getBoolean("replacing", true);
                    if (!newInstall || !json.optBoolean(ConfigSchema.KEY_AUTO_ALLOW_NEW_APPS, true)) return false;
                    int expectedUid = extras.getInt("uid", -1);
                    if (expectedUid >= 0 && pm.getPackageUid(target, 0) != expectedUid) return false;
                    boolean fcm = false;
                    ActivityInfo[] receivers = pm.getPackageInfo(target,
                            PackageManager.GET_RECEIVERS | PackageManager.MATCH_DISABLED_COMPONENTS).receivers;
                    if (receivers != null) for (ActivityInfo receiver : receivers)
                        if (FcmApps.isReceiverName(receiver.name)) { fcm = true; break; }
                    if (!NewAppAutoAllow.add(json, target, fcm, newInstall, eventId)) return false;
                    json.put("revision", ConfigEdits.nextRevision(json.optLong("revision", 0),
                            System.currentTimeMillis()));
                    json.remove("bootstrapDefaults");
                    ConfigFile.write(context, json);
                }
                return true;
            });
            result.putBoolean("acknowledged", true);
            if (changed) {
                try {
                    context.sendBroadcast(new Intent("io.github.zopulus.ffc.update.config"));
                    context.getContentResolver().notifyChange(
                            android.net.Uri.parse("content://io.github.zopulus.ffc.provider/config"), null);
                } catch (Exception error) {
                    android.util.Log.w("fcmfix", "Saved installation could not notify observers", error);
                }
            }
        } catch (Exception error) {
            result.putBoolean("acknowledged", false);
            android.util.Log.w("fcmfix", "New installation could not be saved", error);
        }
        return result;
    }
}
