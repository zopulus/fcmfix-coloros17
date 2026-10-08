package io.github.zopulus.ffc.xposed;

import android.content.Intent;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Handler;
import java.util.ArrayList;
import java.util.List;
import io.github.zopulus.ffc.util.GoogleProtection;
import io.github.zopulus.ffc.util.RetryingTask;
import java.lang.reflect.Constructor;
import android.content.pm.PackageManager;

import io.github.zopulus.ffc.libxposed.XC_MethodHook;
import io.github.zopulus.ffc.libxposed.XposedBridge;
import io.github.zopulus.ffc.libxposed.XposedHelpers;

import java.lang.reflect.Method;

/**
 * ColorOS Battery's GoogleRestrictionController applies POLICY_REJECT_ALL when its
 * Google connectivity probe fails. This intercepts every matching Google UID reject-all
 * write inside com.oplus.battery, not exclusively that controller's call site.
 * Calls made directly by Settings/TrafficMonitor are outside this hook's process.
 */
public class OplusBatteryNetworkFix extends XposedModule {
    private volatile Object googleController;
    private final RetryingTask verification =
            retryingTask(this::tryReleaseExistingRestriction, 1000, 3000);
    private static volatile String policyState = "pending", policyDetail = "Awaiting OEM policy verification";
    public static String networkPolicyState() { return policyState; }
    public static String networkPolicyDetail() { return policyDetail; }

    @Override protected void onConfigLoaded(io.github.zopulus.ffc.util.ConfigSnapshot previous,
            io.github.zopulus.ffc.util.ConfigSnapshot current) {
        if (current != null && (previous == null || "pending".equals(policyState) || "failed".equals(policyState)))
            requestRestrictionVerification();
    }

    private void requestRestrictionVerification() {
        // A config callback can arrive while the subclass is still being constructed.
        if (verification != null) verification.request();
    }

    private boolean tryReleaseExistingRestriction() {
        if (context == null) return false;
        java.util.List<String> failures = new ArrayList<>();
        int verified = 0, recordRestricted = 0;
        try {
            Context batteryContext = context;
            Object manager = ColorOs17Compat.networkManager(classLoader);
            if (manager == null) throw new IllegalStateException("OEM network manager not ready");
            for (String name : ColorOs17Compat.NETWORK_PACKAGES) {
                try {
                    int uid = batteryContext.getPackageManager().getPackageUid(name, 0);
                    Object policy = XposedHelpers.callMethod(manager, "getUidPolicy", uid);
                    if (Integer.valueOf(POLICY_REJECT_ALL).equals(policy))
                        XposedHelpers.callMethod(manager, "setUidPolicy", uid, POLICY_NONE);
                } catch (PackageManager.NameNotFoundException ignored) {
                } catch (Throwable error) { failures.add(name + ": " + error); }
            }
            // Recovery works even when the constructor was inlined or ran before injection.
            Object controller = googleController;
            if (controller == null) {
                try { controller = ColorOs17Compat.existingController(classLoader); }
                catch (Throwable error) { logOnce("Existing Google controller lookup unavailable: " + error); }
                if (controller != null) googleController = controller;
            }
            if (controller != null) {
                Object captured = controller;
                try {
                    Handler owner = ColorOs17Compat.controllerHandler(captured);
                    if (owner != null) owner.post(() -> {
                        try { ColorOs17Compat.clearRestrictionBroadcast(captured); }
                        catch (Throwable error) { logOnce("Google restriction broadcast reconciliation failed: " + error); }
                    });
                } catch (Throwable error) { logOnce("Google controller handler unavailable: " + error); }
            }
            // Verify the resulting OEM policy rather than treating a void write as success.
            for (String name : ColorOs17Compat.NETWORK_PACKAGES) {
                try {
                    int uid = batteryContext.getPackageManager().getPackageUid(name, 0);
                    Object actual = XposedHelpers.callMethod(manager, "getUidPolicy", uid);
                    if (Integer.valueOf(POLICY_NONE).equals(actual)) verified++;
                    else if (Integer.valueOf(POLICY_REJECT_ALL).equals(actual)) recordRestricted++;
                    else failures.add(name + ": policy=" + actual);
                } catch (PackageManager.NameNotFoundException ignored) {
                } catch (Throwable error) { failures.add(name + ": " + error); }
            }
            if (verified + recordRestricted == 0) failures.add("No Google UID queried");
            policyDetail = failures.isEmpty() ? "Verified " + verified + " Google UID policies"
                    : String.join("; ", failures);
            policyState = failures.isEmpty() ? (recordRestricted == 0 ? "verified" : "record_restricted") : "failed";
            if (recordRestricted > 0) policyDetail += "; " + recordRestricted
                    + " rejected policy records; effective firewall/connection not verified";
        } catch (Throwable error) {
            policyDetail = error.toString();
            policyState = "failed";
        } finally {
            printLog("Google OEM network verification: " + policyState + ": " + policyDetail, true);
            reportHookStatus();
        }
        return !"failed".equals(policyState);
    }

    private static volatile Boolean ignoresGmsUserSet;
    static boolean batteryIgnoresGmsUserSet() {
        Boolean cached = ignoresGmsUserSet;
        if (cached != null) return cached;
        if (context == null) return false;
        try {
            android.os.Bundle meta = context.getPackageManager().getApplicationInfo(
                    "com.oplus.battery", PackageManager.GET_META_DATA).metaData;
            Object value = meta == null ? null : meta.get("IgnoreGmsUserSet");
            boolean result = value != null && "true".equals(value.toString());
            ignoresGmsUserSet = result;
            return result;
        } catch (Throwable error) {
            logOnce("IgnoreGmsUserSet lookup failed: " + error);
            return false;
        }
    }

    private static volatile boolean deepSleepInstalled, policyInstalled;
    public static boolean hasDeepSleepHook() { return deepSleepInstalled; }
    public static boolean hasNetworkHooks() { return policyInstalled; }

    private static final String NETWORK_CONTROL_MANAGER =
            ColorOs17Compat.NETWORK_MANAGER;
    private static final String GOOGLE_RESTRICT_CHANGE = "oplus.intent.action.google_restrict_change";
    private static final String EXTRA_RESTRICT_ENABLE = "restrict_enable";
    private static final int POLICY_REJECT_ALL = 4;
    private static final int POLICY_NONE = 0;
    public OplusBatteryNetworkFix(ClassLoader classLoader) {
        super(classLoader);
        installDeepSleepWhitelistHook();
        try {
            installInitialRestrictionRelease(XposedHelpers.findClass(ColorOs17Compat.GOOGLE_CONTROLLER, classLoader));
        } catch (Throwable error) {
            printLog("Initial Google restriction release unavailable: " + error);
        }
        try {
            startHookGoogleNetworkPolicy();
        } catch (Throwable e) {
            printLog("hook error Oplus Battery GMS network policy: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        try {
            startHookGoogleRestrictBroadcast();
        } catch (Throwable e) {
            printLog("hook error Oplus Battery Google restrict broadcast: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        if (isConfigurationReady()) requestRestrictionVerification();
    }

    /**
     * The same failed Google probe also broadcasts google_restrict_change. On ColorOS 17
     * system_server then downgrades GMS wakeup alarms (OplusGoogleAlarmRestrict) and puts
     * Google packages in the RARE standby bucket, so the FCM heartbeat/reconnect stops in
     * Doze. Clear only the restrict_enable=true flag; list updates pass through unchanged.
     */
    private void startHookGoogleRestrictBroadcast() {
        Class<?> contextImpl = XposedHelpers.findClass("android.app.ContextImpl", classLoader);
        int hooks = 0;
        for (Method method : contextImpl.getDeclaredMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (!method.getName().startsWith("sendBroadcast")
                    || parameters.length == 0 || parameters[0] != Intent.class) {
                continue;
            }
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Intent intent = (Intent) param.args[0];
                    if (intent == null || !GOOGLE_RESTRICT_CHANGE.equals(intent.getAction())
                            || !intent.getBooleanExtra(EXTRA_RESTRICT_ENABLE, false)) {
                        return;
                    }
                    intent.putExtra(EXTRA_RESTRICT_ENABLE, false);
                    printLog("Oplus Battery Google restrict broadcast cleared", true);
                }
            });
            hooks++;
        }
        if (hooks == 0) throw new NoSuchMethodError("ContextImpl#sendBroadcast(Intent...)");
        printLog("Oplus Battery Google restrict broadcast hooks active: " + hooks);
    }

    private void startHookGoogleNetworkPolicy() {
        Class<?> managerClass = XposedHelpers.findClassIfExists(
                NETWORK_CONTROL_MANAGER, classLoader);
        if (managerClass == null) {
            throw new NoClassDefFoundError(NETWORK_CONTROL_MANAGER);
        }

        int hooks = 0;
        for (Method method : managerClass.getDeclaredMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (!"setUidPolicy".equals(method.getName())
                    || parameters.length != 2
                    || parameters[0] != int.class
                    || parameters[1] != int.class) {
                continue;
            }

            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    int uid = (Integer) param.args[0];
                    int policy = (Integer) param.args[1];
                    if (policy != POLICY_REJECT_ALL || !isGoogleNetworkUid(uid)) {
                        return;
                    }

                    // Let the original method clear any stale reject-all state in netd.
                    param.args[1] = POLICY_NONE;
                    printLog("Oplus Battery GMS network reject bypass: uid=" + uid, true);
                }
            });
            hooks++;
            printLog("Oplus Battery network hook active: " + method);
        }

        if (hooks == 0) {
            throw new NoSuchMethodError(NETWORK_CONTROL_MANAGER + "#setUidPolicy(int,int)");
        }
        policyInstalled = true;
    }

    private boolean isGoogleNetworkUid(int uid) {
        if (context == null) {
            printLog("Oplus Battery network hook skipped before context initialization");
            return false;
        }

        PackageManager packageManager = context.getPackageManager();
        for (String packageName : ColorOs17Compat.NETWORK_PACKAGES) {
            try {
                if (packageManager.getPackageUid(packageName, 0) == uid) {
                    return true;
                }
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }
        return false;
    }
    private void installDeepSleepWhitelistHook() {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.oplus.deepsleep.ControllerCenter",
                    classLoader,
                    "determineNetWorkWhiteUidList",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (param.hasThrowable() || !isConfigurationReady()
                                    || !getBooleanConfig("deepSleepGoogleWhitelist", true)) {
                                return;
                            }
                            try {
                                Object original = param.getResult();
                                if (!(original instanceof List)) {
                                    return;
                                }
                                Context batteryContext = (Context) XposedHelpers.getObjectField(
                                        param.thisObject, "mContext");
                                if (batteryContext == null) {
                                    printLog("睡眠待机优化豁免未添加：Battery Context 不可用");
                                    return;
                                }

                                List<Object> whitelist = new ArrayList<>((List<?>) original);
                                List<String> added = new ArrayList<>();
                                for (String name : ColorOs17Compat.WHITELIST_PACKAGES)
                                    addUidForPackage(batteryContext, whitelist, added, name);
                                if (getBooleanConfig("rootDeepSleepNetworkWhitelist", false)) {
                                    int previousSize = whitelist.size();
                                    GoogleProtection.addUid(whitelist, 0);
                                    if (whitelist.size() != previousSize) added.add("uid 0");
                                }

                                if (!added.isEmpty()) {
                                    param.setResult(whitelist);
                                    printLog("睡眠待机优化豁免追加：" + added);
                                }
                            } catch (Throwable e) {
                                printLog("睡眠待机优化豁免 Hook 失败：" + e.getMessage());
                            }
                        }
                    });
            deepSleepInstalled = true;
            printLog("Battery 睡眠待机优化豁免 Hook 已安装");
        } catch (Throwable e) {
            printLog("Battery 睡眠待机优化豁免 Hook 安装失败：" + e.getMessage());
        }
    }

    private void addUidForPackage(Context context, List<Object> whitelist,
                                  List<String> added, String packageName) {
        try {
            ApplicationInfo app = context.getPackageManager().getApplicationInfo(packageName, 0);
            String uid = String.valueOf(app.uid);
            int previousSize = whitelist.size();
            GoogleProtection.addUid(whitelist, app.uid);
            if (whitelist.size() != previousSize) added.add(packageName + "=" + uid);
        } catch (PackageManager.NameNotFoundException e) {
            printLog("未找到联网豁免目标：" + packageName);
        }
    }

    private void installInitialRestrictionRelease(Class<?> controllerClass) {
        int hookedConstructors = 0;
        for (Constructor<?> constructor : controllerClass.getDeclaredConstructors()) {
            try {
                constructor.setAccessible(true);
                XposedBridge.hookMethod(constructor, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            googleController = param.thisObject;
                            if (isConfigurationReady()) requestRestrictionVerification();
                        } catch (Throwable error) { printLog("Google controller capture unavailable: " + error); }
                    }
                });
                hookedConstructors++;
            } catch (Throwable e) {
                printLog("GoogleRestrictionController 构造函数 Hook 失败：" + e.getMessage());
            }
        }
        if (hookedConstructors > 0) {
            printLog("Google 网络控制 Hook 已安装，构造函数数=" + hookedConstructors);
        }
    }
}
