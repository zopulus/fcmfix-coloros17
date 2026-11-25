package io.github.zopulus.ffc.util;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import io.github.zopulus.ffc.ModuleService;
import org.json.JSONObject;

/** Only system server can report a protected package-install broadcast. */
public final class NewAppInstallStore {
    private NewAppInstallStore() {}
    public static Bundle handle(Context context, String target, Bundle extras) {
        if (Binder.getCallingUid() != Process.SYSTEM_UID)
            throw new SecurityException("Only system server may report installations");
        if (target == null || target.isEmpty() || target.length() > 255)
            throw new IllegalArgumentException("Invalid target package");
        String eventId = extras == null ? null : extras.getString("eventId");
        if (eventId == null || eventId.length() != 36 || !java.util.UUID.fromString(eventId).toString().equals(eventId))
            throw new IllegalArgumentException("Invalid installation event ID");
        Bundle result = new Bundle();
        try {
            ConfigSaveQueue.saveAndNotify(() -> {
                if (extras.getBoolean("replacing", true)) return false;
                JSONObject json = ProviderConfig.latest(context, extras, false);
                if (!json.optBoolean(ConfigSchema.KEY_AUTO_ALLOW_NEW_APPS, true)) return false;

                ApplicationInfo appInfo = FcmApps.findInstalledFcmApp(context.getPackageManager(), target);
                if (appInfo == null) return false;
                int expectedUid = extras.getInt("uid", -1);
                if (expectedUid >= 0 && appInfo.uid != expectedUid) return false;

                JSONObject baseline = new JSONObject(json.toString());
                if (!NewAppAutoAllow.add(json, target, true, true, eventId)) return false;
                json.put("revision", ConfigEdits.nextRevision(json.optLong("revision", 0), System.currentTimeMillis()));
                json.remove("bootstrapDefaults");
                ConfigPersistence.recordOfflineBase(json, baseline);
                ConfigFile.write(context, json);
                return true;
            }, () -> {
                try {
                    context.sendBroadcast(new Intent(ConfigSchema.ACTION_UPDATE_CONFIG)
                            .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY));
                    context.getContentResolver().notifyChange(
                            android.net.Uri.parse("content://io.github.zopulus.ffc.provider/config"), null);
                    ModuleService.INSTANCE.scheduleBackgroundSync();
                } catch (Exception error) {
                    android.util.Log.w("fcmfix", "Saved installation could not notify observers", error);
                }
            });
            result.putBoolean("acknowledged", true);
        } catch (Exception error) {
            result.putBoolean("acknowledged", false);
            android.util.Log.w("fcmfix", "New installation could not be saved", error);
        }
        return result;
    }
}
