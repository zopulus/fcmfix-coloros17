package io.github.zopulus.ffc.xposed;

import android.annotation.SuppressLint;
import java.lang.reflect.Method;
import io.github.zopulus.ffc.util.ConfigSnapshot;
import io.github.zopulus.ffc.util.ConfigSchema;
import io.github.zopulus.ffc.util.DiagnosticLogger;
import android.os.SystemClock;
import io.github.zopulus.ffc.util.FcmTrust;
import io.github.zopulus.ffc.util.OplusAttribution;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.UserManager;
import android.util.Log;

import io.github.zopulus.ffc.libxposed.XC_MethodHook;
import io.github.zopulus.ffc.libxposed.XposedBridge;
import io.github.zopulus.ffc.libxposed.XposedHelpers;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

public abstract class XposedModule {
    private static String selfPackageName = "UNKNOWN";
    /**
     * 模块应用自身的包名。改动包名时请同步修改这里与 app/build.gradle 的 applicationId。
     */
    public static final String SELF_PACKAGE_NAME = "io.github.zopulus.ffc";

    protected final ClassLoader classLoader;
    static final String TAG = "FcmFix";
    private static volatile ConfigSnapshot config;
    private static boolean reloadRequested;

    @SuppressLint("StaticFieldLeak")
    protected static volatile Context context = null;
    private static final java.util.concurrent.CopyOnWriteArrayList<XposedModule> instances = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static boolean updateReceiverRegistered;
    public static volatile boolean isBootComplete = false;
    private static Thread loadConfigThread = null;
    private static volatile boolean bootInitThreadStarted = false;
    private static volatile boolean bootInitInvoked = false;

    protected XposedModule(final ClassLoader classLoader) {
        this.classLoader = classLoader;
        instances.add(this);
        if (instances.size() == 1) {
            initContext(classLoader);
        } else if (context != null) {
            try {
                UserManager userManager = context.getSystemService(UserManager.class);
                if (userManager != null && userManager.isUserUnlocked()) {
                    onCanReadConfig();
                }
            } catch (Throwable e) {
                printLog(e.getMessage());
            }
        }
    }

    public static void setSelfPackageName(String packageName) {
        selfPackageName = packageName;
    }

    private static void initContext(final ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod("android.content.ContextWrapper", classLoader, "attachBaseContext", Context.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam methodHookParam) {
                    if (context == null) {
                        context = (Context) methodHookParam.thisObject;
                        startBootInitThread();
                    }
                }
            });
        } catch (Throwable e) {
            printLog("hook ContextWrapper.attachBaseContext 失败: " + e.getMessage());
        }
    }

    /**
     * system_server 专用：主动获取系统上下文。
     * <p>
     * 现代 Xposed API 的 onSystemServerStarting 在 SystemServer.startBootstrapServices 时回调，
     * 此时系统 Application 的 attachBaseContext 早已执行完毕，initContext 里的 hook
     * 在 system_server 中可能永远不会触发，导致 isBootComplete/config 永远无法初始化。
     * 因此这里直接通过 ActivityThread 反射拿到系统上下文。
     */
    public static void initSystemServerContext(Context systemContext) {
        if (context != null || systemContext == null) {
            return;
        }
        context = systemContext;
        startBootInitThread();
    }

    /**
     * 等待用户解锁后执行模块初始化。启动早期 AMS/UserManager 可能尚未注册，
     * 因此带重试；同时尽早注册 ACTION_USER_UNLOCKED 接收器作为兜底。
     */
    private static void startBootInitThread() {
        if (bootInitThreadStarted) {
            return;
        }
        bootInitThreadStarted = true;
        new Thread(() -> {
            boolean receiverRegistered = false;
            while (true) {
                if (bootInitInvoked) {
                    return;
                }
                try {
                    UserManager userManager = context.getSystemService(UserManager.class);
                    if (userManager != null && userManager.isUserUnlocked()) {
                        callAllOnCanReadConfig();
                        // Registration may fail while services are starting. Keep polling
                        // until initialization actually succeeds, even after unlock.
                        if (bootInitInvoked) return;
                    }
                } catch (Throwable ignored) {
                }
                if (!receiverRegistered) {
                    try {
                        IntentFilter userUnlockIntentFilter = new IntentFilter();
                        userUnlockIntentFilter.addAction(Intent.ACTION_USER_UNLOCKED);
                        context.registerReceiver(unlockBroadcastReceive, userUnlockIntentFilter);
                        receiverRegistered = true;
                    } catch (Throwable ignored) {
                    }
                }
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "fcmfix-boot-init").start();
    }

    private static synchronized void callAllOnCanReadConfig() {
        if (bootInitInvoked) {
            return;
        }
        if (!initReceiver()) return;
        bootInitInvoked = true;
        // 开机/解锁时立即加载一次配置，避免第一条 FCM 推送因配置未加载而被丢弃
        onUpdateConfig();
        for (XposedModule instance : instances) {
            try {
                instance.onCanReadConfig();
            } catch (Throwable e) {
                printLog(e.getMessage());
            }
        }
    }

    protected void onCanReadConfig() throws Throwable {
    }

    protected static boolean isConfigurationReady() { return config != null; }
    protected void onConfigLoaded(ConfigSnapshot previous, ConfigSnapshot current) throws Throwable { }
    private static void notifyConfigLoaded(ConfigSnapshot previous) {
        for (XposedModule instance : instances) {
            try { instance.onConfigLoaded(previous, config); }
            catch (Throwable error) { printLog("Config reconciliation failed: " + error); }
        }
    }

    protected static void printLog(String text) {
        printLog(text, false);
    }

    private static final DiagnosticLogger diagnosticLogger = new DiagnosticLogger(
            text -> XposedBridge.log("[fcmfix] " + text),
            error -> Log.w(TAG, "Diagnostic log failed; further logging warnings suppressed", error));

    protected static void printLog(String text, Boolean diagnostic) {
        diagnosticLogger.log("[" + getSelfPackageName() + "]" + text, Boolean.TRUE.equals(diagnostic),
                SystemClock.elapsedRealtime());
    }

    protected void checkUserDeviceUnlockAndUpdateConfig() {
        if (context == null) {
            return;
        }
        try {
            UserManager userManager = context.getSystemService(UserManager.class);
            if (userManager != null && userManager.isUserUnlocked()) {
                onUpdateConfig();
            }
        } catch (Throwable e) {
            printLog("更新配置文件失败: " + e.getMessage());
        }
    }

    private static final BroadcastReceiver unlockBroadcastReceive = new BroadcastReceiver() {
        public void onReceive(Context _context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_USER_UNLOCKED.equals(action)) {
                try {
                    context.unregisterReceiver(unlockBroadcastReceive);
                } catch (Throwable ignored) {
                }
                callAllOnCanReadConfig();
            }
        }
    };

    protected static boolean isDeliveryTargetAllowed(String packageName) {
        ConfigSnapshot snapshot = config;
        return snapshot != null && packageName != null && snapshot.allowList.contains(packageName);
    }

    protected boolean targetIsAllow(String packageName) {
        ConfigSnapshot snapshot = config;
        if (snapshot == null) checkUserDeviceUnlockAndUpdateConfig();
        return packageName != null && (SELF_PACKAGE_NAME.equals(packageName)
                || snapshot != null && snapshot.allowList.contains(packageName));
    }

    protected boolean getBooleanConfig(String key, boolean defaultValue) {
        ConfigSnapshot snapshot = config;
        if (snapshot == null) {
            checkUserDeviceUnlockAndUpdateConfig();
            return defaultValue;
        }
        return snapshot.options.getOrDefault(key, defaultValue);
    }

    protected static synchronized void onUpdateConfig() {
        reloadRequested = true;
        if (loadConfigThread != null) return;
        loadConfigThread = new Thread(() -> {
            while (true) {
                synchronized (XposedModule.class) {
                    if (!reloadRequested) {
                        loadConfigThread = null;
                        return;
                    }
                    reloadRequested = false;
                }
                ConfigSnapshot previous = config;
                try {
                    reloadLatestConfig();
                    isBootComplete = true;
                    notifyConfigLoaded(previous);
                } catch (Throwable e) {
                    printLog("Config reload failed: " + e.getMessage());
                }
            }
        }, "FCMFix-config");
        loadConfigThread.start();
    }

    private static void reloadLatestConfig() throws Throwable {
        boolean loaded = false;
        Throwable failure = null;
        try { loadConfigFromRemotePreferences(); loaded = true; } catch (Throwable error) { failure = error; }
        try { loadConfigFromContentProvider(); loaded = true; } catch (Throwable error) { failure = error; }
        if (!loaded && failure != null) throw failure;
    }

    private static void loadConfigFromRemotePreferences() {
        SharedPreferences remotePreferences = XposedBridge.getRemotePreferences("config");
        if (remotePreferences == null) {
            throw new IllegalStateException("remotePreferences 不可用");
        }
        java.util.Map<String, ?> values = remotePreferences.getAll();
        if (!Boolean.TRUE.equals(values.get("init"))) throw new IllegalStateException("Remote config not initialized");
        ConfigSnapshot snapshot = new ConfigSnapshot(values);
        if (config == null || snapshot.revision > config.revision) config = snapshot;
        printLog("[RemotePreferences] allowList size: " + snapshot.allowList.size());
    }

    @SuppressLint("Range")
    private static void loadConfigFromContentProvider() throws Throwable {
        if (context == null) {
            throw new IllegalStateException("context 不可用");
        }
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(
                    Uri.parse("content://" + SELF_PACKAGE_NAME + ".provider/config"),
                    null, "all", null, null);
            if (cursor == null || cursor.getCount() == 0) {
                throw new IllegalStateException("provider 无数据");
            }
            Set<String> allowListTmp = new HashSet<>();
            boolean init = false;
            long revision = 0;
            HashMap<String, Object> values = new HashMap<>();
            cursor.moveToFirst();
            do {
                String key = cursor.getString(cursor.getColumnIndex("key"));
                String value = cursor.getString(cursor.getColumnIndex("value"));
                if ("allowList".equals(key)) allowListTmp.add(value);
                else if ("revision".equals(key)) revision = Long.parseLong(value);
                else if ("init".equals(key)) init = "1".equals(value);
                else if (ConfigSchema.OPTIONS.contains(key))
                    values.put(key, "1".equals(value));
            } while (cursor.moveToNext());
            if (!init) throw new IllegalStateException("provider 未初始化");
            values.put("allowList", allowListTmp);
            values.put("revision", revision);
            ConfigSnapshot snapshot = new ConfigSnapshot(values);
            if (config == null || snapshot.revision >= config.revision) config = snapshot;
            if ("android".equals(getSelfPackageName())) {
                printLog("[ContentProvider]onUpdateConfig allowList size: " + allowListTmp.size());
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private static synchronized boolean initReceiver() {
        if (context == null) return false;
        if (!updateReceiverRegistered) {
            try {
                IntentFilter filter = new IntentFilter(SELF_PACKAGE_NAME + ".update.config");
                BroadcastReceiver receiver = new BroadcastReceiver() {
                    public void onReceive(Context receiverContext, Intent intent) {
                        if (intent != null && (SELF_PACKAGE_NAME + ".update.config").equals(intent.getAction())) {
                            onUpdateConfig();
                        }
                    }
                };
                if (Build.VERSION.SDK_INT >= 34) context.registerReceiver(receiver, filter,
                        SELF_PACKAGE_NAME + ".permission.CONFIG", null, Context.RECEIVER_EXPORTED);
                else context.registerReceiver(receiver, filter, SELF_PACKAGE_NAME + ".permission.CONFIG", null);
                updateReceiverRegistered = true;
            } catch (Throwable error) { printLog("Config receiver registration will retry: " + error); }
        }
        return updateReceiverRegistered;
    }

    private static final Set<String> warnings = java.util.concurrent.ConcurrentHashMap.newKeySet();
    protected static void logOnce(String message) {
        if (warnings.size() < 64 && warnings.add(message)) printLog(message);
    }

    protected boolean isFCMAction(String action) {
        return FcmTrust.isAction(action);
    }

    protected static boolean isGmsUid(int uid) {
        if (!isCurrentUserAppUid(uid) || context == null) return false;
        try {
            return isGmsUid(uid, context.getPackageManager().getPackagesForUid(uid));
        } catch (RuntimeException error) {
            printLog("Cannot verify GMS UID: " + error.getMessage());
            return false;
        }
    }

    private static boolean isCurrentUserAppUid(int uid) {
        // Never attribute a work-profile sender to the personal-profile target.
        return uid >= 10000 && android.os.UserHandle.getUserHandleForUid(uid)
                .equals(android.os.Process.myUserHandle());
    }

    private static boolean isGmsUid(int uid, String[] packages) {
        return isCurrentUserAppUid(uid) && FcmTrust.isGmsSender(uid, packages);
    }

    private static final java.util.concurrent.ConcurrentHashMap<Method, Integer> ATTRIBUTION_INDICES =
            new java.util.concurrent.ConcurrentHashMap<>();

    protected static String explicitTarget(Intent intent) {
        if (intent == null) return null;
        return FcmTrust.target(intent.getPackage(), intent.getComponent() == null
                ? null : intent.getComponent().getPackageName());
    }

    protected static int attributionIndex(Method method) {
        return ATTRIBUTION_INDICES.computeIfAbsent(method, candidate -> {
            String[] types = java.util.Arrays.stream(candidate.getParameterTypes())
                    .map(Class::getName).toArray(String[]::new);
            return OplusAttribution.callerIndex(candidate.getDeclaringClass().getName(),
                    candidate.getName(), candidate.getReturnType().getName(), types);
        });
    }

    protected boolean trustedDelivery(Intent intent, String target, XC_MethodHook.MethodHookParam param) {
        // Require a coherent, explicit destination. Do not select an arbitrary allowlisted string argument.
        if (!isBootComplete || intent == null || target == null || !target.equals(explicitTarget(intent))
                || !targetIsAllow(target)) return false;
        try {
            // A queued broadcast is attributed by BroadcastRecord, never by ambient Binder identity.
            for (Object arg : param.args) {
                if (arg == null || !"com.android.server.am.BroadcastRecord".equals(arg.getClass().getName())) continue;
                return XposedHelpers.getObjectField(arg, "intent") == intent
                        && FcmTrust.RECEIVE.equals(intent.getAction())
                        && isGmsUid((Integer) XposedHelpers.getObjectField(arg, "callingUid"));
            }
            if (param.method instanceof Method) {
                Method method = (Method) param.method;
                int index = attributionIndex(method);
                if (index >= 0) {
                    int callerUid = (Integer) param.args[index];
                    String methodName = method.getName();
                    // Validate the actual callee too, not only the Intent destination.
                    if ("isAppClassifyRestricted".equals(methodName)) {
                        if (!target.equals(param.args[1])) return false;
                    } else {
                        int serviceIndex = "isAllowStartFromBindService".equals(methodName) ? 3 : 4;
                        Object info = XposedHelpers.getObjectField(param.args[serviceIndex], "appInfo");
                        if (!(info instanceof android.content.pm.ApplicationInfo)
                                || !target.equals(((android.content.pm.ApplicationInfo) info).packageName)) return false;
                    }
                    // Reuse one fresh package query only within this validation.
                    String[] packages = context.getPackageManager().getPackagesForUid(callerUid);
                    boolean gms = isGmsUid(callerUid, packages);
                    boolean selfInWindow = !gms && packages != null && java.util.Arrays.asList(packages).contains(target)
                            && OplusProxyFix.isInFcmDeliveryWindow(callerUid);
                    boolean gcmBind = "isAllowStartFromBindService".equals(methodName)
                            && "bsgcm".equals(param.args[5]);
                    boolean trusted = FcmTrust.allowsService(intent.getAction(), gms, selfInWindow, gcmBind);
                    if (trusted && gms) OplusProxyFix.beginFcmDeliveryWindow(target);
                    if (!trusted) logOnce("FCM rejected: untrusted service sender uid=" + callerUid
                            + ", target=" + target + ", method=" + methodName);
                    return trusted;
                }
            }
            // Only the synchronous broadcast path may inherit the verified entry context.
            return FcmTrust.matches(intent.getAction(), target);
        } catch (Throwable error) {
            logOnce("Unsupported delivery attribution: " + param.method + ": " + error);
            return false;
        }
    }

    protected boolean isFCMIntent(Intent intent) {
        String action = intent.getAction();
        return isFCMAction(action);
    }

    protected static String getSelfPackageName() {
        return selfPackageName;
    }
}
