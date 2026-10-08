package io.github.zopulus.ffc;

import android.content.Context;

import io.github.zopulus.ffc.libxposed.XposedBridge;
import io.github.zopulus.ffc.libxposed.XposedHelpers;
import io.github.zopulus.ffc.xposed.AutoStartFix;
import io.github.zopulus.ffc.xposed.BroadcastFix;
import io.github.zopulus.ffc.xposed.KeepNotification;
import io.github.zopulus.ffc.xposed.OplusProxyFix;
import io.github.zopulus.ffc.xposed.OplusGoogleNetworkFix;
import io.github.zopulus.ffc.xposed.OplusDeviceIdleFix;
import io.github.zopulus.ffc.xposed.OplusBatteryNetworkFix;
import io.github.zopulus.ffc.xposed.XposedModule;

import io.github.libxposed.api.XposedModuleInterface;

public class XposedMain extends io.github.libxposed.api.XposedModule {
    private boolean batteryHooksInitialized;

    @Override
    public void onSystemServerStarting(SystemServerStartingParam param) {
        XposedBridge.init(this);
        XposedModule.setSelfPackageName("android");

        ClassLoader classLoader = param.getClassLoader();
        safeInit(() -> new BroadcastFix(classLoader), "BroadcastFix");
        safeInit(() -> new AutoStartFix(classLoader), "AutoStartFix");
        safeInit(() -> new KeepNotification(classLoader), "KeepNotification");
        safeInit(() -> new OplusProxyFix(classLoader), "OplusProxyFix");
        safeInit(() -> new OplusGoogleNetworkFix(classLoader), "OplusGoogleNetworkFix");
        safeInit(() -> new OplusDeviceIdleFix(classLoader), "OplusDeviceIdleFix");
        // system_server 中 attachBaseContext 的 hook 安装过晚，主动获取系统上下文
        initSystemServerContext(classLoader);
    }

    @Override
    public synchronized void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        XposedBridge.init(this);

        // Battery background services run in the shared Athena process on ColorOS17.
        // Battery may be a secondary package; its own class loader is still required.
        if ("com.oplus.battery".equals(param.getPackageName()) && !batteryHooksInitialized) {
            batteryHooksInitialized = true;
            XposedModule.setSelfPackageName("com.oplus.battery");
            safeInit(() -> new OplusBatteryNetworkFix(param.getClassLoader()),
                    "OplusBatteryNetworkFix");
        }
    }

    private interface InitTask {
        void run();
    }

    /**
     * 单个 hook 模块初始化失败时不影响其余 hook 与系统上下文初始化。
     */
    private void safeInit(InitTask task, String name) {
        try {
            XposedBridge.log("[fcmfix] start hook " + name);
            task.run();
        } catch (Throwable e) {
            XposedBridge.log("[fcmfix] " + name + " 初始化失败: " + e.getMessage());
        }
    }

    private void initSystemServerContext(ClassLoader classLoader) {
        try {
            Class<?> activityThreadClass = XposedHelpers.findClass("android.app.ActivityThread", classLoader);
            Object activityThread = XposedHelpers.callStaticMethod(activityThreadClass, "currentActivityThread");
            Object systemContext = XposedHelpers.callMethod(activityThread, "getSystemContext");
            if (systemContext instanceof Context) {
                XposedModule.initSystemServerContext((Context) systemContext);
                XposedBridge.log("[fcmfix] 系统上下文初始化成功");
            } else {
                XposedBridge.log("[fcmfix] 系统上下文获取失败: getSystemContext 返回 null");
            }
        } catch (Throwable e) {
            XposedBridge.log("[fcmfix] 系统上下文初始化失败: " + e.getMessage());
        }
    }
}
