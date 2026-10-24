package io.github.zopulus.ffc.util;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashSet;
import java.util.Set;
import java.util.LinkedHashSet;

public final class NewAppAutoAllow {
    private NewAppAutoAllow() {}
    public static boolean isNewInstall(String action, boolean replacing) {
        return "android.intent.action.PACKAGE_ADDED".equals(action) && !replacing;
    }

    /** Process new installs only; neither opening an app nor replacing its APK enrolls it. */
    public static boolean add(JSONObject json, String packageName, boolean fcm, boolean newInstall, String eventId) throws Exception {
        if (eventId == null || eventId.isEmpty()) throw new IllegalArgumentException("Missing installation event ID");
        ConfigSnapshot config = ConfigCodec.fromJson(json);
        if (packageName == null || packageName.isEmpty() || !fcm || !newInstall
                || !config.options.get(ConfigSchema.KEY_AUTO_ALLOW_NEW_APPS)
                || config.installEvents.contains(eventId)) return false;
        Set<String> allowed = new HashSet<>(config.allowList);
        allowed.add(packageName);
        json.put("allowList", new JSONArray(new java.util.TreeSet<>(allowed)));
        Set<String> events = new LinkedHashSet<>(config.installEvents);
        events.add(eventId);
        while (events.size() > 64) events.remove(events.iterator().next());
        json.put(ConfigSchema.KEY_INSTALL_EVENTS, new JSONArray(events));
        ConfigEdits.removeRetiredOptions(json);
        return true;
    }
}
