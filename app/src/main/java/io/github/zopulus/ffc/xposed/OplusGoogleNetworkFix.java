package io.github.zopulus.ffc.xposed;

import android.content.Intent;
import android.content.pm.PackageManager;
import io.github.zopulus.ffc.libxposed.XC_MethodHook;
import io.github.zopulus.ffc.libxposed.XposedBridge;
import io.github.zopulus.ffc.libxposed.XposedHelpers;
import io.github.zopulus.ffc.util.ProtectionId;
import java.lang.reflect.Method;

/** System boundaries for Google networking and sleep restoration. */
public final class OplusGoogleNetworkFix extends XposedModule {
    private static final String OPLUS_APP_NET_CONTROL_SERVICE = "com.android.server.nwpower.OAppNetControlService";
    private static final String GOOGLE_GMS_PACKAGE = "com.google.android.gms";
    private static final String OPLUS_NETWORK_MANAGEMENT_SERVICE =
            "com.android.server.net.OplusNetworkManagementService";
    private static final int FIREWALL_RULE_REJECT = 2;
    private static final String GOOGLE_RESTRICT_CHANGE = "oplus.intent.action.google_restrict_change";
    private static final String OSENSE_RES_MANAGER_SERVICE =
            ColorOs17Compat.OSENSE_SERVICE;

    public OplusGoogleNetworkFix(ClassLoader loader) {
        super(loader);
        HookRegistry.install(ProtectionId.NIGHT_WHITELIST, this::startHookNightNetworkWhitelist);
        HookRegistry.install(ProtectionId.GOOGLE_FIREWALL, this::startHookGoogleNetworkFirewall);
        HookRegistry.install(ProtectionId.GOOGLE_RESTRICT_BROADCAST, this::startHookGoogleRestrictBroadcast);
        HookRegistry.install(ProtectionId.GMS_FORCE_STOP, this::startHookDeepSleepGmsForceStop);
    }

    /**
     * When the screen turns on after a battery deep-sleep "logical" network cut, Battery asks
     * Osense to clean GMS ("DeepSleepLogicDisNetRestore", strategy 2 = force-stop) so that it
     * reconnects. A force-stop also cancels every GMS alarm and leaves it stopped until a
     * client binds it. GMS now stays on the deep-sleep network whitelist and gets
     * GCM_RECONNECT after the restore, so skip only this force-stop. The Osense Binder
     * implementation is an anonymous class of OsenseResManagerService.
     */
    private void startHookDeepSleepGmsForceStop() {
        int hooks = 0;
        for (int i = 1; i <= ColorOs17Compat.OSENSE_ANONYMOUS_CLASS_LIMIT && hooks == 0; i++) {
            Class<?> clazz = XposedHelpers.findClassIfExists(OSENSE_RES_MANAGER_SERVICE + "$" + i, classLoader);
            if (clazz == null) continue;
            for (Method method : clazz.getDeclaredMethods()) {
                Class<?>[] types = method.getParameterTypes();
                if (!"requestSceneActionSync".equals(method.getName()) || types.length != 1
                        || types[0] != android.os.Bundle.class || !isBooleanType(method.getReturnType())) {
                    continue;
                }
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!(param.args[0] instanceof android.os.Bundle)) return;
                        android.os.Bundle bundle = (android.os.Bundle) param.args[0];
                        if (!io.github.zopulus.ffc.util.GoogleProtection.skipDeepSleepForceStop(
                                bundle.getString("pkgName"), bundle.getString("reason"), bundle.getInt("strategy", -1),
                                bundle.getString("action"), bundle.getString("subAction"), bundle.getString("policy"),
                                bundle.getString("caller_package"))) {
                            return;
                        }
                        param.setResult(Boolean.FALSE);
                        printLog("Oplus deep-sleep GMS force-stop skipped", true);
                    }
                });
                hooks++;
                printLog("Oplus deep-sleep GMS force-stop hook active: " + describeMethod(method));
            }
        }
        if (hooks == 0) throw new NoSuchMethodError("OsenseResManagerService$*#requestSceneActionSync(Bundle)");
    }

    /**
     * Battery broadcasts google_restrict_change(restrict_enable=true) when its Google probe
     * fails. Three system_server receivers act on it: OplusGoogleRestrictionHelper (GMS wakeup
     * alarms downgraded), AppStandbyControllerExtImpl (RARE bucket) and
     * OplusNetworkPolicyManagerServiceEx. The battery-scope hook clears the flag at the sender;
     * this clears it at the Binder entry so a missing battery scope, or an inlined getter such
     * as isGoogleRestrct(), cannot leave GMS restricted. List updates still go through.
     */
    private void startHookGoogleRestrictBroadcast() {
        String[] classes = new String[]{
                "com.android.server.am.ActivityManagerService",
                "com.android.server.am.BroadcastController"
        };
        int hooks = 0;
        for (String className : classes) {
            Class<?> clazz = XposedHelpers.findClassIfExists(className, classLoader);
            if (clazz == null) continue;
            for (Method method : clazz.getDeclaredMethods()) {
                if (!"broadcastIntentWithFeature".equals(method.getName())) continue;
                Class<?>[] types = method.getParameterTypes();
                int intentIndex = -1;
                for (int i = 0; i < types.length; i++) {
                    if (types[i] == Intent.class) {
                        intentIndex = i;
                        break;
                    }
                }
                if (intentIndex < 0) continue;
                final int index = intentIndex;
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Object arg = param.args[index];
                        if (!(arg instanceof Intent)) return;
                        Intent intent = (Intent) arg;
                        if (!GOOGLE_RESTRICT_CHANGE.equals(intent.getAction())
                                || !intent.getBooleanExtra("restrict_enable", false)) {
                            return;
                        }
                        intent.putExtra("restrict_enable", false);
                        printLog("Oplus Google restrict broadcast cleared in system_server", true);
                    }
                });
                hooks++;
                printLog("Oplus Google restrict broadcast hook active: " + describeMethod(method));
            }
            // AMS delegates to BroadcastController; one entry hook is enough.
            if (hooks > 0) break;
        }
        if (hooks == 0) throw new NoSuchMethodError("broadcastIntentWithFeature(Intent)");
    }

    /**
     * Battery's GoogleRestrictionController probes Google 5 s after BOOT_COMPLETED (i.e. right
     * after the first unlock) and, if the probe fails, sets the Google core UIDs to
     * POLICY_REJECT_ALL. OplusExSystemService's networking_control service turns that into
     * setFirewallUidRuleForNetworkType(type, uid, 2) here; GMS then sits disconnected with
     * ERR_CLOSE_BY_USER_UNLOCKED. The battery-scope hook rewrites the policy at its source,
     * but it depends on the battery scope and on the call not being inlined, so drop the
     * reject rule at this system_server boundary as well. Clears (rule 1) still pass.
     * This boundary cannot tell the battery from Traffic Monitor, so it only acts while
     * the Google toggles are hidden (IgnoreGmsUserSet); without that declaration, manual
     * Wi-Fi/mobile rules are left alone.
     */
    private void startHookGoogleNetworkFirewall() {
        Class<?> serviceClass = XposedHelpers.findClassIfExists(OPLUS_NETWORK_MANAGEMENT_SERVICE, classLoader);
        if (serviceClass == null) throw new NoClassDefFoundError(OPLUS_NETWORK_MANAGEMENT_SERVICE);

        int hooks = 0;
        for (Method method : serviceClass.getDeclaredMethods()) {
            Class<?>[] types = method.getParameterTypes();
            if (!"setFirewallUidRuleForNetworkType".equals(method.getName()) || types.length != 3
                    || types[0] != int.class || types[1] != int.class || types[2] != int.class) {
                continue;
            }
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    int uid = (Integer) param.args[1];
                    if ((Integer) param.args[2] != FIREWALL_RULE_REJECT || !isGoogleNetworkUid(uid)
                            || !OplusBatteryNetworkFix.batteryIgnoresGmsUserSet()) return;
                    // Replace a rejection with allow so an earlier kernel rule is cleared too.
                    param.args[2] = 1;
                    printLog("Oplus Google network reject replaced with allow: uid=" + uid
                            + ", type=" + param.args[0], true);
                }
            });
            hooks++;
            printLog("Oplus Google network firewall hook active: " + describeMethod(method));
        }
        if (hooks == 0) throw new NoSuchMethodError("OplusNetworkManagementService#setFirewallUidRuleForNetworkType");
    }

    private static boolean isGoogleNetworkUid(int uid) {
        if (context == null) return false;
        int appId = uid % 100_000;
        PackageManager packageManager = context.getPackageManager();
        for (String packageName : ColorOs17Compat.NETWORK_PACKAGES) {
            try {
                if (packageManager.getPackageUid(packageName, 0) % 100_000 == appId) return true;
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }
        return false;
    }

    /**
     * Battery deep sleep cuts the network with OAppNetControlService.networkDisableWhiteList
     * (enable != 1 starts, enable == 1 restores). Field logs showed the battery-side list
     * reaching the service without the GMS UID, so GMS lost its socket at night
     * (ERR_IO_RST_HB) and never retried after the restore. Add the GMS UID at the service
     * boundary, then ask GMS to reconnect once the network is back.
     */
    private void startHookNightNetworkWhitelist() {
        Class<?> serviceClass = XposedHelpers.findClassIfExists(OPLUS_APP_NET_CONTROL_SERVICE, classLoader);
        if (serviceClass == null) throw new NoClassDefFoundError(OPLUS_APP_NET_CONTROL_SERVICE);

        int hooks = 0;
        for (Method method : serviceClass.getDeclaredMethods()) {
            Class<?>[] types = method.getParameterTypes();
            if (!"networkDisableWhiteList".equals(method.getName()) || types.length != 2
                    || !java.util.List.class.isAssignableFrom(types[0]) || types[1] != int.class) {
                continue;
            }
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if ((Integer) param.args[1] == 1 || !(param.args[0] instanceof java.util.List)
                            || !isConfigurationReady() || !getBooleanConfig("deepSleepGoogleWhitelist", true)) return;
                    java.util.List<Object> whitelist = new java.util.ArrayList<>((java.util.List<?>) param.args[0]);
                    for (String name : ColorOs17Compat.WHITELIST_PACKAGES) {
                        int uid = getTargetUidFromPackageName(name);
                        if (uid >= 0) io.github.zopulus.ffc.util.GoogleProtection.addUid(whitelist, uid);
                    }
                    if (getBooleanConfig("rootDeepSleepNetworkWhitelist", false))
                        io.github.zopulus.ffc.util.GoogleProtection.addUid(whitelist, 0);
                    param.args[0] = whitelist;
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.hasThrowable() || !(param.getResult() instanceof Integer)
                            || (Integer) param.getResult() != 0 || context == null) {
                        return;
                    }
                    // Give netd a moment to drop the whitelist chain before GMS reconnects.
                    android.os.Handler handler = reconnectHandler();
                    handler.removeCallbacks(RECONNECT);
                    if ((Integer) param.args[1] == 1) handler.postDelayed(RECONNECT, 3000);
                }
            });
            hooks++;
            printLog("Oplus night network whitelist hook active: " + describeMethod(method));
        }
        if (hooks == 0) throw new NoSuchMethodError("OAppNetControlService#networkDisableWhiteList");
    }

    /**
     * Same broadcast as FCM Diagnostics' RECONNECT; GMS reconnects if its MCS link is down.
     * Runs in system_server: lint reads the module manifest, so the host permission is
     * checked at runtime here instead of being requested by the module APK.
     */
    @android.annotation.SuppressLint("MissingPermission")
    private static void requestGmsReconnect() {
        try {
            if (context == null) return;
            if (context.checkSelfPermission("android.permission.INTERACT_ACROSS_USERS")
                    != PackageManager.PERMISSION_GRANTED) {
                printLog("GCM_RECONNECT skipped: host cannot send a user-qualified broadcast");
                return;
            }
            Intent reconnect = new Intent("com.google.android.intent.action.GCM_RECONNECT");
            reconnect.setPackage(GOOGLE_GMS_PACKAGE);
            context.sendBroadcastAsUser(reconnect, android.os.Process.myUserHandle());
            printLog("Oplus night network restored: GCM_RECONNECT sent", true);
        } catch (Throwable e) {
            printLog("GCM_RECONNECT after night network restore failed: " + e);
        }
    }

    private static final Runnable RECONNECT = OplusGoogleNetworkFix::requestGmsReconnect;
    private static android.os.Handler sReconnectHandler;
    private static synchronized android.os.Handler reconnectHandler() {
        if (sReconnectHandler == null) sReconnectHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        return sReconnectHandler;
    }

    public static boolean hasNetworkFirewallGuard() {
        return HookRegistry.contains(ProtectionId.GOOGLE_FIREWALL)
                && OplusBatteryNetworkFix.batteryIgnoresGmsUserSet();
    }
    public static boolean hasNightWhitelistHook() {
        return HookRegistry.contains(ProtectionId.NIGHT_WHITELIST);
    }

    private static boolean isBooleanType(Class<?> type) { return type == boolean.class || type == Boolean.class; }
    private static String describeMethod(Method method) { return method.toGenericString(); }
    private static int getTargetUidFromPackageName(String name) {
        if (context == null) return -1;
        try { return context.getPackageManager().getPackageUid(name, 0); }
        catch (PackageManager.NameNotFoundException ignored) { return -1; }
    }
}
