package io.github.zopulus.ffc.util;

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.ComponentInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves installed FCM-capable packages via PackageManager's intent-filter index
 * ({@code com.google.android.c2dm.intent.RECEIVE} and {@code com.google.firebase.MESSAGING_EVENT})
 * rather than hardcoded receiver class names, avoiding analytics-only false positives, obfuscated
 * receiver misses, and full-device {@code GET_RECEIVERS} Binder payloads.
 */
public final class FcmApps {
    public static final String ACTION_C2DM_RECEIVE = "com.google.android.c2dm.intent.RECEIVE";
    public static final String ACTION_FIREBASE_MESSAGING = "com.google.firebase.MESSAGING_EVENT";

    private static final int QUERY_FLAGS = PackageManager.MATCH_DISABLED_COMPONENTS
            | PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS
            | PackageManager.MATCH_UNINSTALLED_PACKAGES;

    private FcmApps() {}

    public static boolean isEligiblePackage(String packageName, int appFlags) {
        return (appFlags & ApplicationInfo.FLAG_INSTALLED) != 0
                && packageName != null
                && !packageName.isEmpty()
                && !"android".equals(packageName)
                && !"io.github.zopulus.ffc".equals(packageName);
    }

    @SuppressWarnings("deprecation")
    public static Map<String, ApplicationInfo> queryInstalledFcmApps(PackageManager pm) {
        LinkedHashMap<String, ApplicationInfo> apps = new LinkedHashMap<>();
        collectReceivers(pm, new Intent(ACTION_C2DM_RECEIVE), apps);
        collectServices(pm, new Intent(ACTION_FIREBASE_MESSAGING), apps);
        return apps;
    }

    @SuppressWarnings("deprecation")
    public static ApplicationInfo findInstalledFcmApp(PackageManager pm, String packageName) {
        if (packageName == null || packageName.isEmpty()
                || "android".equals(packageName)
                || "io.github.zopulus.ffc".equals(packageName)) {
            return null;
        }
        LinkedHashMap<String, ApplicationInfo> apps = new LinkedHashMap<>();
        collectReceivers(pm, new Intent(ACTION_C2DM_RECEIVE).setPackage(packageName), apps);
        ApplicationInfo found = apps.get(packageName);
        if (found != null) return found;
        collectServices(pm, new Intent(ACTION_FIREBASE_MESSAGING).setPackage(packageName), apps);
        return apps.get(packageName);
    }

    @SuppressWarnings("deprecation")
    private static void collectReceivers(PackageManager pm, Intent intent,
                                         Map<String, ApplicationInfo> out) {
        List<ResolveInfo> resolved = pm.queryBroadcastReceivers(intent, QUERY_FLAGS);
        if (resolved == null) return;
        for (ResolveInfo info : resolved) {
            addComponentApp(info == null ? null : info.activityInfo, out);
        }
    }

    @SuppressWarnings("deprecation")
    private static void collectServices(PackageManager pm, Intent intent,
                                        Map<String, ApplicationInfo> out) {
        List<ResolveInfo> resolved = pm.queryIntentServices(intent, QUERY_FLAGS);
        if (resolved == null) return;
        for (ResolveInfo info : resolved) {
            addComponentApp(info == null ? null : info.serviceInfo, out);
        }
    }

    private static void addComponentApp(ComponentInfo component, Map<String, ApplicationInfo> out) {
        if (component == null || component.applicationInfo == null) return;
        ApplicationInfo app = component.applicationInfo;
        if (isEligiblePackage(app.packageName, app.flags)) {
            out.putIfAbsent(app.packageName, app);
        }
    }
}
