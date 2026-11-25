package io.github.zopulus.ffc.xposed;

import android.content.Intent;


import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import io.github.zopulus.ffc.libxposed.XC_MethodHook;
import io.github.zopulus.ffc.libxposed.XposedBridge;
import io.github.zopulus.ffc.libxposed.XposedHelpers;

import io.github.zopulus.ffc.util.IceboxUtils;
import io.github.zopulus.ffc.util.FcmTrust;

public class BroadcastFix extends XposedModule {
    public BroadcastFix(ClassLoader classLoader) {
        super(classLoader);
        HookInstaller.install("broadcast entry point", this::startHookBroadcastEntryPoints);
        HookInstaller.install("broadcastIntentLocked", this::startHookBroadcastIntentLocked);
        HookInstaller.install("broadcast deoptimize", this::deoptimizeBroadcastCallers);
    }

    /**
     * Android 16-17 / ColorOS 16-17 validates and may clone the incoming Intent
     * before entering broadcastIntentLocked. Hook the Binder-facing entry so
     * FLAG_INCLUDE_STOPPED_PACKAGES survives that copy.
     */
    protected void startHookBroadcastEntryPoints(){
        String[] candidateClasses = new String[]{
                "com.android.server.am.ActivityManagerService",
                "com.android.server.am.BroadcastController"
        };
        int hookCount = 0;

        for (String className : candidateClasses) {
            Class<?> clazz = XposedHelpers.findClassIfExists(className, classLoader);
            if (clazz == null) {
                continue;
            }
            for (Method method : clazz.getDeclaredMethods()) {
                if (!"broadcastIntentWithFeature".equals(method.getName())) {
                    continue;
                }
                int intentArgsIndex = findIntentParameterIndex(method);
                if (intentArgsIndex < 0) {
                    continue;
                }
                try {
                    createBroadcastIntentHooker(intentArgsIndex, method, "entry");
                    hookCount++;
                } catch (Throwable e) {
                    printLog("hook broadcast entry failed: " + describeMethod(method) + ": " + e.getMessage());
                }
            }
            // AMS delegates to BroadcastController; do not install a second entry there.
            if (hookCount > 0) break;
        }
        printLog("broadcast entry hooks active: " + hookCount);
    }

    protected void startHookBroadcastIntentLocked(){
        String[] candidateClasses = new String[]{
                "com.android.server.am.BroadcastController",
                "com.android.server.am.ActivityManagerService"
        };
        int hookCount = 0;

        for (String className : candidateClasses) {
            Class<?> clazz = XposedHelpers.findClassIfExists(className, classLoader);
            if (clazz == null) {
                printLog("broadcast hook class missing: " + className);
                continue;
            }

            for (Method method : clazz.getDeclaredMethods()) {
                // Android 15+ moved the body into broadcastIntentLockedTraced; on Android 17
                // (ColorOS 17) the thin broadcastIntentLocked wrapper may be inlined away.
                if (!"broadcastIntentLocked".equals(method.getName())
                        && !"broadcastIntentLockedTraced".equals(method.getName())) {
                    continue;
                }
                int intentArgsIndex = findIntentParameterIndex(method);
                if (intentArgsIndex < 0) {
                    printLog("skip broadcast candidate without Intent: " + describeMethod(method));
                    continue;
                }

                try {
                    createBroadcastIntentHooker(intentArgsIndex, method, "locked");
                    hookCount++;
                } catch (Throwable e) {
                    printLog("hook broadcast candidate failed: " + describeMethod(method) + ": " + e.getMessage());
                }
            }
        }

        if (hookCount == 0) {
            printLog("broadcastIntentLocked hook 位置查找失败，fcmfix将不会工作。");
        } else {
            printLog("broadcastIntentLocked hooks active: " + hookCount);
        }
    }

    /**
     * AOT-compiled services.jar may inline the short broadcast wrappers into their callers,
     * so hooks on those wrappers never run. Deoptimize only the framework-side broadcast
     * chain (not the Binder stub) to keep the extra interpreter cost confined to it.
     */
    protected void deoptimizeBroadcastCallers() {
        String[] classes = new String[]{
                "com.android.server.am.ActivityManagerService",
                "com.android.server.am.BroadcastController"
        };
        Set<String> callers = new HashSet<>(java.util.Arrays.asList(
                "broadcastIntentWithFeature",
                "broadcastIntentInPackage",
                "broadcastIntentLocked"));
        int count = 0;
        for (String className : classes) {
            Class<?> clazz = XposedHelpers.findClassIfExists(className, classLoader);
            if (clazz == null) continue;
            for (Method method : clazz.getDeclaredMethods()) {
                if (callers.contains(method.getName()) && XposedBridge.deoptimize(method)) count++;
            }
        }
        printLog("broadcast callers deoptimized: " + count);
    }

    private int findIntentParameterIndex(Method method) {
        Class<?>[] parameterTypes = method.getParameterTypes();
        for (int i = 0; i < parameterTypes.length; i++) {
            if (Intent.class.isAssignableFrom(parameterTypes[i])) {
                return i;
            }
        }
        return -1;
    }

    protected void createBroadcastIntentHooker(int intentIndex, Method method, String stage) {
        printLog("hook target [" + stage + "]: " + describeMethod(method));
        final boolean entry = "entry".equals(stage);
        XposedBridge.hookMethod(method, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                // Mask nested calls if an outer FcmTrust scope is active on this thread.
                if (entry) {
                    if (FcmTrust.hasActiveScope()) {
                        param.addFinallyAction(FcmTrust.enter(null, -1));
                    }
                } else if (!FcmTrust.hasActiveScope() || !isBootComplete) {
                    return;
                }
                try {
                    Intent intent = param.args[intentIndex] instanceof Intent ? (Intent) param.args[intentIndex] : null;
                    if (intent == null) return;
                    if (entry && OplusGoogleNetworkFix.clearGoogleRestrictBroadcastIfNeeded(intent)) {
                        return;
                    }
                    if (!isBootComplete || !FcmTrust.isDeliveryBroadcast(intent.getAction())) return;
                    String target = explicitTarget(intent);
                    if (target == null || !targetIsAllow(target)) return;
                    if (entry) {
                        int callerUid = android.os.Binder.getCallingUid();
                        if (!isGmsUid(callerUid)) {
                            logOnce("FCM rejected: untrusted sender uid=" + callerUid + ", target=" + target);
                            return;
                        }
                        param.addFinallyAction(FcmTrust.enter(target, callerUid));
                        printLog("FCM trusted sender: uid=" + callerUid + ", target=" + target, true);
                        int targetUid = OplusProxyFix.beginFcmDeliveryWindow(target);
                        boolean activationAttempted = false;
                        io.github.zopulus.ffc.util.ConfigSnapshot config = currentConfig();
                        if (config != null && config.permitsIceBox(target, 0)
                                && IceboxUtils.appState(context, target) != IceboxUtils.AppState.ENABLED) {
                            activationAttempted = true;
                            // Activation uses the module's SDK permission and finishes before dispatch.
                            // Wait is bounded; no original Binder call is retained or replayed.
                            if (!IceboxUtils.activateBeforeDelivery(context, target, config.revision)) {
                                logOnce("Ice Box activation failed or timed out: " + target);
                            }
                        }
                        // Activation may wait; refresh attribution after that wait.
                        if (activationAttempted) OplusProxyFix.unfreeze(target);
                        else OplusProxyFix.unfreeze(target, targetUid);
                    } else if (!FcmTrust.matches(intent.getAction(), target)) {
                        return;
                    }
                    intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
                    // Framework AppOps arguments are deliberately left untouched.
                } catch (Throwable error) {
                    // Keep the failure mask until the original invocation has finished.
                    param.addFinallyAction(FcmTrust.enter(null, -1));
                    logOnce("FCM broadcast hook failed: " + method + ": " + error);
                }
            }
        });
    }

}
