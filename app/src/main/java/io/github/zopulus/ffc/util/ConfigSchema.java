package io.github.zopulus.ffc.util;

import java.util.List;

/** Persisted names and migration defaults shared by UI, provider and hooked processes. */
public final class ConfigSchema {
    private ConfigSchema() {}
    public static final String HOOK_SNAPSHOT = "snapshot";
    public static final String ACTION_UPDATE_CONFIG = "io.github.zopulus.ffc.update.config";
    public static final String EXTRA_SERVICE_CONNECTED = "serviceConnected";
    public static final String KEY_DISABLE_AUTO_CLEAN_NOTIFICATION = "disableAutoCleanNotification";
    public static final String KEY_INCLUDE_ICEBOX_DISABLED_APP = "includeIceBoxDisableApp";
    public static final String KEY_ADAPTIVE_WIFI_HEARTBEAT = "adaptiveWifiHeartbeat";
    public static final String KEY_AUTO_ALLOW_NEW_APPS = "autoAllowNewApps";
    public static final String KEY_INSTALL_EVENTS = "installEvents";
    public static final List<String> RETIRED_KEYS = List.of("disableGoogleNetworkControl",
            "autoAllowExcluded", "autoAllowOnNotificationGrant", "notificationEnableEvents",
            "deepSleepGoogleWhitelist", "dozeGoogleWhitelist", "rootDeepSleepNetworkWhitelist");
    public static final List<String> OPTIONS = List.of(KEY_DISABLE_AUTO_CLEAN_NOTIFICATION,
            KEY_INCLUDE_ICEBOX_DISABLED_APP, KEY_ADAPTIVE_WIFI_HEARTBEAT, KEY_AUTO_ALLOW_NEW_APPS);
    public static boolean defaultValue(String key) {
        return KEY_ADAPTIVE_WIFI_HEARTBEAT.equals(key) || KEY_AUTO_ALLOW_NEW_APPS.equals(key);
    }
}
