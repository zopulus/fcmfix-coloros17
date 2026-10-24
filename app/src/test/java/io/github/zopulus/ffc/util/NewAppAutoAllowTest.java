package io.github.zopulus.ffc.util;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.util.Set;
import static org.junit.Assert.*;

public class NewAppAutoAllowTest {
    private JSONObject config(boolean enabled) throws Exception {
        return new JSONObject().put("allowList", new JSONArray()).put("autoAllowNewApps", enabled);
    }

    @Test public void legacyConfigDefaultsToEnabledRegardlessOfRemovedNotificationOption() throws Exception {
        for (boolean old : new boolean[]{false, true}) {
            ConfigSnapshot snapshot = ConfigCodec.fromJson(new JSONObject().put("allowList", new JSONArray())
                    .put("autoAllowOnNotificationGrant", old));
            assertTrue(snapshot.options.get(ConfigSchema.KEY_AUTO_ALLOW_NEW_APPS));
            assertFalse(snapshot.options.containsKey("autoAllowOnNotificationGrant"));
        }
        assertFalse(ConfigCodec.fromJson(config(false)).options.get(ConfigSchema.KEY_AUTO_ALLOW_NEW_APPS));
    }

    @Test public void onlyPackageAddedWithoutReplacementIsANewInstall() {
        assertTrue(NewAppAutoAllow.isNewInstall("android.intent.action.PACKAGE_ADDED", false));
        assertFalse(NewAppAutoAllow.isNewInstall("android.intent.action.PACKAGE_ADDED", true));
        assertFalse(NewAppAutoAllow.isNewInstall("android.intent.action.PACKAGE_REPLACED", false));
        assertFalse(NewAppAutoAllow.isNewInstall("android.intent.action.PACKAGE_CHANGED", false));
        assertFalse(NewAppAutoAllow.isNewInstall("android.intent.action.BOOT_COMPLETED", false));
        assertFalse(NewAppAutoAllow.isNewInstall(null, false));
    }

    @Test public void onlyEnabledNewFcmAppsAreAddedWithoutCheckingNotificationPermission() throws Exception {
        JSONObject json = config(true);
        assertFalse(NewAppAutoAllow.add(json, "target", false, true, "install-1"));
        assertFalse(NewAppAutoAllow.add(json, "target", true, false, "install-1"));
        assertFalse(NewAppAutoAllow.add(config(false), "target", true, true, "install-1"));
        assertTrue(NewAppAutoAllow.add(json, "target", true, true, "install-1"));
        assertEquals(Set.of("target"), ConfigCodec.fromJson(json).allowList);
    }

    @Test public void duplicateInstallCannotUndoLaterManualDisableOrAnUpdate() throws Exception {
        JSONObject json = config(true);
        assertTrue(NewAppAutoAllow.add(json, "target", true, true, "install-1"));
        JSONObject requested = new JSONObject(json.toString()).put("allowList", new JSONArray());
        JSONObject saved = ConfigEdits.merge(json, json, requested);
        JSONObject reloaded = new JSONObject(saved.toString());
        assertTrue(ConfigCodec.fromJson(reloaded).allowList.isEmpty());
        assertFalse(NewAppAutoAllow.add(reloaded, "target", true, true, "install-1"));
        assertFalse(NewAppAutoAllow.add(reloaded, "target", true, false, "update-1"));
        assertTrue(ConfigCodec.fromJson(reloaded).allowList.isEmpty());
        assertTrue(NewAppAutoAllow.add(reloaded, "target", true, true, "reinstall-1"));
    }

    @Test public void turningFeatureOnDoesNotEnrollExistingApps() throws Exception {
        JSONObject before = config(false);
        JSONObject after = new JSONObject(before.toString()).put("autoAllowNewApps", true);
        assertTrue(ConfigCodec.fromJson(ConfigEdits.merge(before, before, after)).allowList.isEmpty());
    }

    @Test public void receiptsAreBoundedAndSurviveReload() throws Exception {
        JSONObject json = config(true);
        for (int i = 0; i < 100; i++) assertTrue(NewAppAutoAllow.add(json, "target", true, true, "install-" + i));
        ConfigSnapshot snapshot = ConfigCodec.fromJson(new JSONObject(json.toString()));
        assertEquals(64, snapshot.installEvents.size());
        assertFalse(NewAppAutoAllow.add(json, "target", true, true, "install-99"));
    }

    @Test public void oldNotificationStateIsRemovedWithoutChangingExistingAllowlist() throws Exception {
        JSONObject before = config(true).put("allowList", new JSONArray("[\"manual\"]"))
                .put("autoAllowOnNotificationGrant", true).put("notificationEnableEvents", new JSONArray("[\"old\"]"));
        JSONObject saved = ConfigEdits.merge(before, before, before);
        assertFalse(saved.has("autoAllowOnNotificationGrant"));
        assertFalse(saved.has("notificationEnableEvents"));
        assertEquals(Set.of("manual"), ConfigCodec.fromJson(saved).allowList);
    }

    @Test public void fcmDetectionKeepsOriginalTwoReceiverNames() {
        assertTrue(FcmApps.isReceiverName("com.google.firebase.iid.FirebaseInstanceIdReceiver"));
        assertTrue(FcmApps.isReceiverName("com.google.android.gms.measurement.AppMeasurementReceiver"));
        assertFalse(FcmApps.isReceiverName("com.google.firebase.messaging.FirebaseMessagingService"));
        assertFalse(FcmApps.isReceiverName(null));
    }
}
