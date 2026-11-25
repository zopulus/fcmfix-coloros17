package io.github.zopulus.ffc.xposed;

import android.service.notification.NotificationListenerService;

import java.lang.reflect.Method;
import io.github.zopulus.ffc.libxposed.XC_MethodHook;
import io.github.zopulus.ffc.libxposed.XposedBridge;
import io.github.zopulus.ffc.libxposed.XposedHelpers;

public class KeepNotification extends XposedModule{

    public KeepNotification(ClassLoader classLoader) {
        super(classLoader);
        HookInstaller.install("NotificationManagerService.cancelAllNotificationsInt", this::startHook);
    }
    
    protected void startHook() throws NoSuchMethodError, XposedHelpers.ClassNotFoundError {
        Class<?> clazz = XposedHelpers.findClass("com.android.server.notification.NotificationManagerService",classLoader);
        int hooks = 0;
        for (Method targetMethod : clazz.getDeclaredMethods()) {
            if (!"cancelAllNotificationsInt".equals(targetMethod.getName())) continue;
            String[] types = java.util.Arrays.stream(targetMethod.getParameterTypes())
                    .map(Class::getName).toArray(String[]::new);
            int finalReason_args_index = io.github.zopulus.ffc.util.NotificationSignature.reasonIndex(
                    targetMethod.getReturnType().getName(), types);
            final int finalPkg_args_index = 2;
            if (finalReason_args_index < 0) {
                logOnce("Unsupported notification signature: " + targetMethod);
                continue;
            }
            hooks++;
            XposedBridge.hookMethod(targetMethod,new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!isBootComplete) return;
                    Object rawReason = param.args[finalReason_args_index];
                    Object rawPkg = param.args[finalPkg_args_index];
                    if (!(rawReason instanceof Integer) || !(rawPkg instanceof String)) return;
                    int reason = (Integer) rawReason;
                    if (reason != NotificationListenerService.REASON_PACKAGE_CHANGED
                            && reason != 10020 && reason != 10021) {
                        return;
                    }
                    if (getBooleanConfig("disableAutoCleanNotification", false)
                            && targetIsAllow((String) rawPkg)) {
                        param.setResult(null);
                    }
                }
            });
        }
        if (hooks == 0) throw new NoSuchMethodError();
    }
}
