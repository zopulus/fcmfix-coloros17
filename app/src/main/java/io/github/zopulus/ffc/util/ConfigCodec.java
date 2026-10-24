package io.github.zopulus.ffc.util;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Storage adapters always pass through the same validation and migration. */
public final class ConfigCodec {
    private ConfigCodec() {}
    public static ConfigSnapshot fromJson(JSONObject json) throws Exception {
        Map<String, Object> values = new HashMap<>();
        if (json.has("revision")) {
            Object revision = json.get("revision");
            // JSON floating-point values can truncate or saturate when converted to long.
            if (!(revision instanceof Integer || revision instanceof Long)
                    || ((Number) revision).longValue() < 0) {
                throw new IllegalArgumentException("Invalid revision");
            }
            values.put("revision", revision);
        }
        values.put("allowList", readStringSet(json.getJSONArray("allowList")));
        if (json.has(ConfigSchema.KEY_INSTALL_EVENTS)) {
            values.put(ConfigSchema.KEY_INSTALL_EVENTS,
                    readStringSet(json.getJSONArray(ConfigSchema.KEY_INSTALL_EVENTS)));
        }
        for (String key : ConfigSchema.OPTIONS) if (json.has(key)) values.put(key, json.get(key));
        return new ConfigSnapshot(values);
    }

    private static Set<String> readStringSet(JSONArray list) throws Exception {
        Set<String> strings = new LinkedHashSet<>();
        for (int i = 0; i < list.length(); i++) {
            Object name = list.get(i);
            if (!(name instanceof String)) throw new IllegalArgumentException("Invalid string list element");
            strings.add((String) name);
        }
        return strings;
    }
}
