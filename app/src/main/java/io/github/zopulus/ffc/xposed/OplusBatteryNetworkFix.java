package io.github.zopulus.ffc.xposed;

import android.content.Intent;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Handler;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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
    private volatile Handler googleHandler;
    private volatile Boolean lastNetworkEnabled;

    @Override protected void onConfigLoaded(io.github.zopulus.ffc.util.ConfigSnapshot previous,
            io.github.zopulus.ffc.util.ConfigSnapshot current) {
        if (current == null) return;
        boolean enabled = current.options.getOrDefault("disableGoogleNetworkControl", true);
        boolean changed = lastNetworkEnabled == null || lastNetworkEnabled != enabled;
        lastNetworkEnabled = enabled;
        if (enabled && changed) releaseExistingRestriction();
    }

    private void releaseExistingRestriction() {
        Object controller = googleController;
        Handler handler = googleHandler;
        if (controller == null || handler == null || !isConfigurationReady()) return;
        handler.post(() -> {
            if (!isConfigurationReady() || !getBooleanConfig("disableGoogleNetworkControl", true)) return;
            try {
                XposedHelpers.callMethod(controller, "K", false, true, 0);
                printLog("Google existing network policy release requested", true);
            } catch (Throwable error) { printLog("Google policy reconciliation unavailable: " + error); }
        });
    }

    private static volatile boolean deepSleepInstalled, policyInstalled, broadcastInstalled;
    public static boolean hasDeepSleepHook() { return deepSleepInstalled; }
    public static boolean hasNetworkHooks() { return policyInstalled && broadcastInstalled; }

    private static final String NETWORK_CONTROL_MANAGER =
            "android.net.OplusNetworkingControlManager";
    private static final String GOOGLE_RESTRICT_CHANGE = "oplus.intent.action.google_restrict_change";
    private static final String EXTRA_RESTRICT_ENABLE = "restrict_enable";
    private static final int POLICY_REJECT_ALL = 4;
    private static final int POLICY_NONE = 0;
    private static final String[] GOOGLE_NETWORK_PACKAGES = new String[]{
            "com.google.android.gms",
            "com.google.android.gsf",
            "com.android.vending",
            "com.google.android.configupdater"
    };

    public OplusBatteryNetworkFix(ClassLoader classLoader) {
        super(classLoader);
        installDeepSleepWhitelistHook();
        try { installInitialRestrictionRelease(XposedHelpers.findClass("com.oplus.battery.restrictdynamicfeature.google.GoogleRestrictionController", classLoader)); } catch (Throwable e) { printLog("Initial Google restriction release unavailable: " + e); }
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
                    if (!isConfigurationReady() || !getBooleanConfig("disableGoogleNetworkControl", true) || intent == null || !GOOGLE_RESTRICT_CHANGE.equals(intent.getAction())
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
        broadcastInstalled = true;
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
                    if (!isConfigurationReady() || !getBooleanConfig("disableGoogleNetworkControl", true) || policy != POLICY_REJECT_ALL || !isGoogleNetworkUid(uid)) {
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
        for (String packageName : GOOGLE_NETWORK_PACKAGES) {
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
                                addUidForPackage(batteryContext, whitelist, added,
                                        "com.google.android.gms");
                                addUidForPackage(batteryContext, whitelist, added,
                                        "com.google.android.gsf");
                                if (getBooleanConfig("rootDeepSleepNetworkWhitelist", false)
                                        && !whitelist.contains("0")) {
                                    whitelist.add("0");
                                    added.add("uid 0");
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
            if (!whitelist.contains(uid)) {
                whitelist.add(uid);
                added.add(packageName + "=" + uid);
            }
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
                            googleHandler = (Handler) XposedHelpers.getObjectField(param.thisObject, "e");
                            if (isConfigurationReady() && getBooleanConfig("disableGoogleNetworkControl", true)) releaseExistingRestriction();
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
