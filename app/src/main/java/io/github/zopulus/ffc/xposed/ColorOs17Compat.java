package io.github.zopulus.ffc.xposed;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.SystemClock;
import io.github.zopulus.ffc.libxposed.XposedHelpers;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.List;

/** Device-audited ColorOS17 bindings; obfuscated Battery names stay in this adapter. */
final class ColorOs17Compat {
    private ColorOs17Compat() {}
    static final String GMS_PACKAGE = "com.google.android.gms";
    static final String GOOGLE_CONTROLLER = "com.oplus.battery.restrictdynamicfeature.google.GoogleRestrictionController";
    static final String NETWORK_MANAGER = "android.net.OplusNetworkingControlManager";
    static final String OSENSE_SERVICE = "com.android.server.oplus.osense.OsenseResManagerService";
    static final int OSENSE_ANONYMOUS_CLASS_LIMIT = 30;
    static final List<String> NETWORK_PACKAGES = List.of(GMS_PACKAGE, "com.google.android.gsf",
            "com.android.vending", "com.google.android.configupdater");
    static final List<String> WHITELIST_PACKAGES = List.of(GMS_PACKAGE, "com.google.android.gsf");

    private static final long PARTIAL_CACHE_TTL_MS = 60_000L;
    private static volatile int cachedGmsUid = -1;
    private static volatile int[] cachedNetworkAppIds;
    private static volatile boolean completeNetworkCache;
    private static volatile long lastPartialRefreshElapsed = Long.MIN_VALUE;

    static void invalidateUidCache() {
        cachedGmsUid = -1;
        cachedNetworkAppIds = null;
        completeNetworkCache = false;
        lastPartialRefreshElapsed = Long.MIN_VALUE;
    }

    static void onPackageChanged(String packageName) {
        if (packageName != null && NETWORK_PACKAGES.contains(packageName)) {
            invalidateUidCache();
        }
    }

    static int gmsUid(Context context) {
        int uid = cachedGmsUid;
        if (uid >= 10_000) return uid;
        if (context == null) return -1;
        try {
            int resolved = context.getPackageManager().getPackageUid(GMS_PACKAGE, 0);
            if (resolved >= 10_000) cachedGmsUid = resolved;
            return resolved;
        } catch (PackageManager.NameNotFoundException ignored) {
            return -1;
        }
    }

    static boolean isGoogleNetworkUid(Context context, int uid) {
        if (context == null || uid < 10_000) return false;
        int appId = uid % 100_000;
        if (appId < 10_000) return false;
        int[] appIds = resolveNetworkAppIds(context);
        for (int candidate : appIds) {
            if (candidate == appId) return true;
        }
        return false;
    }

    private static int[] resolveNetworkAppIds(Context context) {
        int[] current = cachedNetworkAppIds;
        if (current != null) {
            if (completeNetworkCache) return current;
            long now = SystemClock.elapsedRealtime();
            if (now - lastPartialRefreshElapsed < PARTIAL_CACHE_TTL_MS) return current;
        }
        PackageManager packageManager = context.getPackageManager();
        int[] buffer = new int[NETWORK_PACKAGES.size()];
        int count = 0;
        boolean gmsResolved = false;
        for (String packageName : NETWORK_PACKAGES) {
            try {
                int pkgUid = packageManager.getPackageUid(packageName, 0);
                int pkgAppId = pkgUid % 100_000;
                if (pkgAppId >= 10_000) {
                    buffer[count++] = pkgAppId;
                    if (GMS_PACKAGE.equals(packageName)) {
                        gmsResolved = true;
                        if (pkgUid >= 10_000) cachedGmsUid = pkgUid;
                    }
                }
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }
        int[] resolved = count == buffer.length ? buffer : java.util.Arrays.copyOf(buffer, count);
        if (gmsResolved) {
            cachedNetworkAppIds = resolved;
            completeNetworkCache = count == NETWORK_PACKAGES.size();
            if (!completeNetworkCache) lastPartialRefreshElapsed = SystemClock.elapsedRealtime();
        }
        return resolved;
    }

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
