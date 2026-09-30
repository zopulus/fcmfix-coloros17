package io.github.zopulus.ffc.xposed;

import android.service.notification.NotificationListenerService;

import java.lang.reflect.Method;
import io.github.zopulus.ffc.libxposed.XC_MethodHook;
import io.github.zopulus.ffc.libxposed.XposedBridge;
import io.github.zopulus.ffc.libxposed.XposedHelpers;

public class KeepNotification extends XposedModule{

    public KeepNotification(ClassLoader classLoader) {
        super(classLoader);
        try {
            this.startHook();
        } catch (Throwable e) {
            printLog("No Such Method com.android.server.notification.NotificationManagerService.cancelAllNotificationsInt");
        }
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
                    if(!isBootComplete){
                        return;
                    }
                    if (param.args.length <= Math.max(finalPkg_args_index, finalReason_args_index)
                            || !(param.args[finalPkg_args_index] instanceof String)
                            || !(param.args[finalReason_args_index] instanceof Integer)) {
                        printLog("Unsupported cancelAllNotificationsInt arguments");
                        return;
                    }
                    if(getBooleanConfig("disableAutoCleanNotification",false) && targetIsAllow((String) param.args[finalPkg_args_index])){
                        int reason = (int)param.args[finalReason_args_index];
                        if(reason == NotificationListenerService.REASON_PACKAGE_CHANGED){
                            param.setResult(null);
                        }
                        if(reason == 10020 || reason == 10021){ // cos15/oos15
                            param.setResult(null);
                        }
                    }
                }
            });
        }
        if (hooks == 0) throw new NoSuchMethodError();
    }
}
