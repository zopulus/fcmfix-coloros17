package io.github.zopulus.ffc.xposed;

import android.annotation.SuppressLint;
import java.lang.reflect.Method;
import io.github.zopulus.ffc.util.ConfigSnapshot;
import io.github.zopulus.ffc.util.ConfigCodec;
import io.github.zopulus.ffc.util.HookConfig;
import org.json.JSONObject;
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
import android.os.UserManager;
import android.util.Log;

import io.github.zopulus.ffc.libxposed.XC_MethodHook;
import io.github.zopulus.ffc.libxposed.XposedBridge;
import io.github.zopulus.ffc.libxposed.XposedHelpers;

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
    private static final HookConfig hookConfig = new HookConfig();
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
        if (context != null) {
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

    private static volatile boolean contextHookInstalled = false;

    public static synchronized void ensureContextHook(final ClassLoader classLoader) {
        if (context != null || contextHookInstalled) {
            return;
        }
        contextHookInstalled = true;
        try {
            java.lang.reflect.Method method = XposedHelpers.findMethodExact(
                    XposedHelpers.findClass("android.content.ContextWrapper", classLoader),
                    "attachBaseContext", Context.class);
            final io.github.libxposed.api.XposedInterface.HookHandle[] handleRef =
                    new io.github.libxposed.api.XposedInterface.HookHandle[1];
            handleRef[0] = XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam methodHookParam) {
                    if (context == null && methodHookParam.thisObject instanceof Context) {
                        Context candidate = (Context) methodHookParam.thisObject;
                        Context appContext = candidate.getApplicationContext();
                        initSystemServerContext(appContext != null ? appContext : candidate);
                    }
                    io.github.libxposed.api.XposedInterface.HookHandle handle = handleRef[0];
                    if (context != null && handle != null) {
                        handleRef[0] = null;
                        try {
                            handle.unhook();
                        } catch (Throwable ignored) {
                        }
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

    protected static ConfigSnapshot currentConfig() { return config; }

    protected static boolean isConfigurationReady() { return config != null; }
    protected void onConfigLoaded(ConfigSnapshot previous, ConfigSnapshot current) throws Throwable { }
    protected void onServiceConnected() { }
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
        JSONObject remote = null;
        JSONObject local = null;
        Throwable failure = null;
        try {
            SharedPreferences preferences = XposedBridge.getRemotePreferences("config");
            ConfigSnapshot snapshot = preferences == null ? null : ConfigCodec.fromPreferences(preferences.getAll());
            if (snapshot != null) remote = ConfigCodec.toJson(snapshot);
        } catch (Throwable error) { failure = error; }
        try { local = loadConfigFromContentProvider(); }
        catch (Throwable error) { failure = error; }
        if (local == null && remote == null && failure != null) throw failure;
        config = hookConfig.resolve(local, remote);
    }

    private static JSONObject loadConfigFromContentProvider() throws Exception {
        if (context == null) {
            throw new IllegalStateException("context 不可用");
        }
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(
                    Uri.parse("content://" + SELF_PACKAGE_NAME + ".provider/config"),
                    null, ConfigSchema.HOOK_SNAPSHOT, null, null);
            if (cursor == null || cursor.getCount() == 0) {
                throw new IllegalStateException("provider 无数据");
            }
            cursor.moveToFirst();
            int keyColumn = cursor.getColumnIndexOrThrow("key");
            int valueColumn = cursor.getColumnIndexOrThrow("value");
            if (!ConfigSchema.HOOK_SNAPSHOT.equals(cursor.getString(keyColumn))) {
                throw new IllegalStateException("provider 快照格式无效");
            }
            JSONObject local = new JSONObject(cursor.getString(valueColumn));
            ConfigCodec.fromJson(local);
            return local;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    private static synchronized boolean initReceiver() {
        if (context == null) return false;
        if (!updateReceiverRegistered) {
            try {
                IntentFilter filter = new IntentFilter(SELF_PACKAGE_NAME + ".update.config");
                BroadcastReceiver receiver = new BroadcastReceiver() {
                    public void onReceive(Context receiverContext, Intent intent) {
                        if (intent != null && (SELF_PACKAGE_NAME + ".update.config").equals(intent.getAction())) {
                            onUpdateConfig();
                            if (intent.getBooleanExtra(ConfigSchema.EXTRA_SERVICE_CONNECTED, false)) {
                                for (XposedModule instance : instances) instance.onServiceConnected();
                            }
                        }
                    }
                };
                context.registerReceiver(receiver, filter,
                        SELF_PACKAGE_NAME + ".permission.CONFIG", null, Context.RECEIVER_EXPORTED);
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
        int cachedUid = ColorOs17Compat.gmsUid(context);
        if (cachedUid >= 10_000) return uid == cachedUid;
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

    private static volatile java.lang.reflect.Field sBroadcastRecordIntentField;
    private static volatile java.lang.reflect.Field sBroadcastRecordCallingUidField;

    private static Intent readBroadcastRecordIntent(Object record) throws IllegalAccessException {
        java.lang.reflect.Field field = sBroadcastRecordIntentField;
        if (field == null) {
            field = XposedHelpers.findFieldIfExists(record.getClass(), "intent");
            if (field == null) throw new NoSuchFieldError("BroadcastRecord#intent");
            sBroadcastRecordIntentField = field;
        }
        Object raw = field.get(record);
        return raw instanceof Intent ? (Intent) raw : null;
    }

    private static int readBroadcastRecordCallingUid(Object record) throws IllegalAccessException {
        java.lang.reflect.Field field = sBroadcastRecordCallingUidField;
        if (field == null) {
            field = XposedHelpers.findFieldIfExists(record.getClass(), "callingUid");
            if (field == null) throw new NoSuchFieldError("BroadcastRecord#callingUid");
            sBroadcastRecordCallingUidField = field;
        }
        return field.getInt(record);
    }

    protected boolean trustedDelivery(Intent intent, String target, XC_MethodHook.MethodHookParam param) {
        // Require a coherent, explicit destination. Do not select an arbitrary allowlisted string argument.
        if (!isBootComplete || intent == null || target == null || !target.equals(explicitTarget(intent))
                || !targetIsAllow(target)) return false;
        try {
            // A queued broadcast is attributed by BroadcastRecord, never by ambient Binder identity.
            for (Object arg : param.args) {
                if (arg == null || !"com.android.server.am.BroadcastRecord".equals(arg.getClass().getName())) continue;
                return readBroadcastRecordIntent(arg) == intent
                        && FcmTrust.RECEIVE.equals(intent.getAction())
                        && isGmsUid(readBroadcastRecordCallingUid(arg));
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
                    boolean gms = isGmsUid(callerUid);
                    boolean selfInWindow = false;
                    if (!gms && isCurrentUserAppUid(callerUid)
                            && OplusProxyFix.isInFcmDeliveryWindow(callerUid, target)) {
                        String[] packages = context.getPackageManager().getPackagesForUid(callerUid);
                        selfInWindow = packages != null && java.util.Arrays.asList(packages).contains(target);
                    }
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

    protected static boolean isBooleanType(Class<?> type) {
        return type == boolean.class || type == Boolean.class;
    }

    protected static int getTargetUidFromPackageName(String packageName) {
        if (packageName == null || context == null) return -1;
        try {
            return context.getPackageManager().getPackageUid(packageName, 0);
        } catch (android.content.pm.PackageManager.NameNotFoundException ignored) {
            return -1;
        }
    }

    protected static String describeMethod(Method method) {
        StringBuilder result = new StringBuilder(method.getDeclaringClass().getName())
                .append('#').append(method.getName()).append('(');
        Class<?>[] types = method.getParameterTypes();
        for (int i = 0; i < types.length; i++) {
            if (i > 0) result.append(',');
            result.append(types[i].getSimpleName());
        }
        return result.append("): ").append(method.getReturnType().getSimpleName()).toString();
    }
}
