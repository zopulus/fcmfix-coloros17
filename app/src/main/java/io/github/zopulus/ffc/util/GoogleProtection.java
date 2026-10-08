package io.github.zopulus.ffc.util;

import java.util.List;

public final class GoogleProtection {
    private GoogleProtection() {}
    public static boolean skipDeepSleepForceStop(String pkg, String reason, int strategy,
                                                String action, String subAction, String policy, String caller) {
        return "com.google.android.gms".equals(pkg) && "DeepSleepLogicDisNetRestore".equals(reason)
                && strategy == 2 && "SCENE_COMMON_REQUEST".equals(action)
                && "CommonExternalClean".equals(subAction) && "clean".equals(policy)
                && "com.oplus.battery".equals(caller);
    }
    public static void addUid(List<Object> whitelist, int uid) {
        String entry = String.valueOf(uid);
        for (Object item : whitelist) if (item != null && entry.equals(item.toString())) return;
        whitelist.add(entry);
    }
}
