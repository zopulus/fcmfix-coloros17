package io.github.zopulus.ffc.util;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

/** Apply user deltas to the latest saved config, retaining concurrent automatic additions. */
public final class ConfigEdits {
    private ConfigEdits() {}
    public static JSONObject merge(JSONObject current, JSONObject previous, JSONObject requested) throws Exception {
        ConfigSnapshot latest = ConfigCodec.fromJson(current);
        ConfigSnapshot before = ConfigCodec.fromJson(previous);
        ConfigSnapshot after = ConfigCodec.fromJson(requested);
        JSONObject result = new JSONObject(current.toString());
        Set<String> allowed = new HashSet<>(latest.allowList);
        for (String name : before.allowList) {
            if (!after.allowList.contains(name)) allowed.remove(name);
        }
        for (String name : after.allowList) {
            if (!before.allowList.contains(name)) allowed.add(name);
        }
        result.put("allowList", new JSONArray(new TreeSet<>(allowed)));
        removeRetiredOptions(result);
        for (String key : ConfigSchema.OPTIONS) {
            if (!before.options.get(key).equals(after.options.get(key))) result.put(key, after.options.get(key));
        }
        result.remove("bootstrapDefaults");
        return result;
    }
    static void removeRetiredOptions(JSONObject json) {
        json.remove("autoAllowExcluded");
        json.remove("autoAllowOnNotificationGrant");
        json.remove("notificationEnableEvents");
    }
    public static long nextRevision(long previous, long now) {
        return Math.max(Math.addExact(previous, 1), now);
    }
}
