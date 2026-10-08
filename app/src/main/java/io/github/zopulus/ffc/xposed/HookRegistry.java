package io.github.zopulus.ffc.xposed;

import io.github.zopulus.ffc.util.ProtectionId;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

final class HookRegistry {
    private HookRegistry() {}
    private static final Set<ProtectionId> installed = ConcurrentHashMap.newKeySet();
    interface Action { void run() throws Throwable; }
    static void install(ProtectionId id, Action action) {
        try {
            action.run();
            installed.add(id);
        } catch (Throwable error) {
            XposedModule.printLog("hook error " + id.wireName + ": " + error);
        }
    }
    static boolean contains(ProtectionId id) { return installed.contains(id); }
    static String installedNames() {
        Set<String> names = new TreeSet<>();
        for (ProtectionId id : installed) names.add(id.wireName);
        return String.join(",", names);
    }
}
