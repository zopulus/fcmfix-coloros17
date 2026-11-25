package io.github.zopulus.ffc.xposed;

import android.content.Intent;

import java.lang.reflect.Method;

import io.github.zopulus.ffc.libxposed.XC_MethodHook;
import io.github.zopulus.ffc.libxposed.XposedBridge;
import io.github.zopulus.ffc.libxposed.XposedHelpers;

public class AutoStartFix extends XposedModule {
    public AutoStartFix(ClassLoader classLoader){
        super(classLoader);
        HookInstaller.install("AutoStartFix", this::startHook);
    }

    protected void startHook(){
        Class<?> clazz = XposedHelpers.findClassIfExists("com.android.server.am.OplusAppStartupManager", classLoader);
        if (clazz == null) {
            printLog("No Such Method com.android.server.am.OplusAppStartupManager.shouldPreventSendReceiverReal");
            return;
        }
        int hooks = 0;
        for (Method method : clazz.getDeclaredMethods()) {
            if (!"shouldPreventSendReceiverReal".equals(method.getName())
                    || method.getParameterTypes().length != 4
                    || !isBooleanType(method.getReturnType())) {
                continue;
            }
            final java.lang.reflect.Field intentField =
                    XposedHelpers.findFieldIfExists(method.getParameterTypes()[0], "intent");
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam methodHookParam) {
                    if (!isBootComplete) return;
                    Object holder = methodHookParam.args[0];
                    if (holder == null) return;
                    Object rawIntent = null;
                    try {
                        rawIntent = intentField != null
                                ? intentField.get(holder)
                                : XposedHelpers.getObjectField(holder, "intent");
                    } catch (Throwable ignored) {
                    }
                    if (rawIntent instanceof Intent) {
                        Intent intent = (Intent) rawIntent;
                        if (trustedDelivery(intent, explicitTarget(intent), methodHookParam)) {
                            methodHookParam.setResult(false);
                        }
                    }
                }
            });
            hooks++;
        }
        if (hooks == 0) {
            printLog("No Such Method com.android.server.am.OplusAppStartupManager.shouldPreventSendReceiverReal");
        }
    }

}
