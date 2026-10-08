package io.github.zopulus.ffc.xposed;

import android.os.Handler;
import io.github.zopulus.ffc.libxposed.XposedHelpers;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.List;

/** Device-audited ColorOS17 bindings; obfuscated Battery names stay in this adapter. */
final class ColorOs17Compat {
    private ColorOs17Compat() {}
    static final String GOOGLE_CONTROLLER = "com.oplus.battery.restrictdynamicfeature.google.GoogleRestrictionController";
    static final String NETWORK_MANAGER = "android.net.OplusNetworkingControlManager";
    static final String OSENSE_SERVICE = "com.android.server.oplus.osense.OsenseResManagerService";
    static final int OSENSE_ANONYMOUS_CLASS_LIMIT = 30;
    static final List<String> NETWORK_PACKAGES = List.of("com.google.android.gms", "com.google.android.gsf",
            "com.android.vending", "com.google.android.configupdater");
    static final List<String> WHITELIST_PACKAGES = List.of("com.google.android.gms", "com.google.android.gsf");

    static Handler controllerHandler(Object controller) {
        return (Handler) XposedHelpers.getObjectField(controller, "e");
    }
    static Object existingController(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> type = XposedHelpers.findClass(GOOGLE_CONTROLLER, loader);
        for (Field field : type.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == type) {
                field.setAccessible(true);
                Object value = field.get(null);
                if (value != null) return value;
            }
        }
        return null;
    }
    static Object networkManager(ClassLoader loader) {
        return XposedHelpers.callStaticMethod(XposedHelpers.findClass(NETWORK_MANAGER, loader),
                "getOplusNetworkingControlManager");
    }
    static void clearRestrictionBroadcast(Object controller) {
        XposedHelpers.callMethod(controller, "K", false, true, 0);
    }
}
