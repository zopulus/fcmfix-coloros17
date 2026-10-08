package io.github.zopulus.ffc.util;

import java.util.List;

/** Persisted names and migration defaults shared by UI, provider and hooked processes. */
public final class ConfigSchema {
    private ConfigSchema() {}
    public static final String KEY_DISABLE_AUTO_CLEAN_NOTIFICATION = "disableAutoCleanNotification";
    public static final String KEY_INCLUDE_ICEBOX_DISABLED_APP = "includeIceBoxDisableApp";
    public static final String KEY_DEEP_SLEEP_GOOGLE_WHITELIST = "deepSleepGoogleWhitelist";
    public static final String KEY_DOZE_GOOGLE_WHITELIST = "dozeGoogleWhitelist";
    public static final String KEY_ROOT_DEEP_SLEEP_NETWORK_WHITELIST = "rootDeepSleepNetworkWhitelist";
    public static final List<String> OPTIONS = List.of(KEY_DISABLE_AUTO_CLEAN_NOTIFICATION,
            KEY_INCLUDE_ICEBOX_DISABLED_APP, KEY_DEEP_SLEEP_GOOGLE_WHITELIST,
            KEY_DOZE_GOOGLE_WHITELIST, KEY_ROOT_DEEP_SLEEP_NETWORK_WHITELIST);
    public static boolean defaultValue(String key) {
        return KEY_DEEP_SLEEP_GOOGLE_WHITELIST.equals(key) || KEY_DOZE_GOOGLE_WHITELIST.equals(key);
    }
}
