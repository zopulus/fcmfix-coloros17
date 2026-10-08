package io.github.zopulus.ffc.util;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Storage adapters always pass through the same validation and migration. */
public final class ConfigCodec {
    private ConfigCodec() {}
    public static ConfigSnapshot fromJson(JSONObject json) throws Exception {
        Map<String, Object> values = new HashMap<>();
        if (json.has("revision")) values.put("revision", json.get("revision"));
        Set<String> packages = new HashSet<>();
        JSONArray list = json.getJSONArray("allowList");
        for (int i = 0; i < list.length(); i++) {
            Object name = list.get(i);
            if (!(name instanceof String)) throw new IllegalArgumentException("Invalid package name");
            packages.add((String) name);
        }
        values.put("allowList", packages);
        for (String key : ConfigSchema.OPTIONS) if (json.has(key)) values.put(key, json.get(key));
        return new ConfigSnapshot(values);
    }
}
