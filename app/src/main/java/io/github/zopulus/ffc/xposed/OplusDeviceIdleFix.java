package io.github.zopulus.ffc.xposed;

import io.github.zopulus.ffc.libxposed.XC_MethodHook;
import io.github.zopulus.ffc.libxposed.XposedBridge;
import io.github.zopulus.ffc.libxposed.XposedHelpers;

import java.lang.reflect.Method;
import java.util.List;

/** Restores only the Google entries omitted by the ColorOS CN regional Doze list. */
public class OplusDeviceIdleFix extends XposedModule {
    private static volatile boolean dozeInstalled, alarmInstalled;
    public static boolean hasDozeHook() { return dozeInstalled; }
    public static boolean hasAlarmHook() { return alarmInstalled; }

    private static final String OPLUS_DEVICE_IDLE_HELPER =
            "com.android.server.OplusDeviceIdleHelper";
    private static final String OPLUS_GOOGLE_ALARM_RESTRICT =
            "com.android.server.alarm.OplusGoogleAlarmRestrict";
    private static final String[] GOOGLE_DOZE_PACKAGES = new String[]{
            "com.google.android.gms",
            "com.google.android.gsf"
    };

    public OplusDeviceIdleFix(ClassLoader classLoader) {
        super(classLoader);
        try {
            startHook();
        } catch (Throwable e) {
            printLog("hook error OplusDeviceIdleFix: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        try {
            startHookGoogleAlarmRestrict();
        } catch (Throwable e) {
            printLog("hook error Oplus Google alarm restrict: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * Always protect the original wakeup type at the alarm-specific downgrade path.
     * Alarm.wakeup retains the original request when ColorOS changes type 0/2 to 1/3.
     * Do not alter the shared Google restriction state or consult module configuration.
     */
    private void startHookGoogleAlarmRestrict() throws ReflectiveOperationException {
        Class<?> restrictClass = XposedHelpers.findClass(OPLUS_GOOGLE_ALARM_RESTRICT, classLoader);
        Class<?> alarmClass = XposedHelpers.findClass("com.android.server.alarm.Alarm", classLoader);
        final java.lang.reflect.Field type = alarmClass.getDeclaredField("type");
        final java.lang.reflect.Field wakeup = alarmClass.getDeclaredField("wakeup");
        final java.lang.reflect.Field operation = alarmClass.getDeclaredField("operation");
        final java.lang.reflect.Field listenerTag = alarmClass.getDeclaredField("listenerTag");
        final java.lang.reflect.Field statsTag = alarmClass.getDeclaredField("statsTag");
        for (java.lang.reflect.Field field : new java.lang.reflect.Field[]{
                type, wakeup, operation, listenerTag, statsTag}) field.setAccessible(true);
        final Method makeTag = XposedHelpers.findMethodExact(
                alarmClass, "makeTag", android.app.PendingIntent.class, String.class, int.class);
        XposedHelpers.findAndHookMethod(restrictClass, "updateGoogleAlarmTypeAndTag",
                alarmClass, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        Object alarm = param.args[0];
                        if (alarm != null && wakeup.getBoolean(alarm)) {
                            int currentType = type.getInt(alarm);
                            // Restore only an alarm originally requested as a wakeup alarm.
                            if (currentType == 1 || currentType == 3) {
                                int restoredType = currentType - 1;
                                Object restoredTag = makeTag.invoke(null, operation.get(alarm),
                                        listenerTag.get(alarm), restoredType);
                                type.setInt(alarm, restoredType);
                                statsTag.set(alarm, restoredTag);
                            }
                        }
                        param.setResult(null);
                    }
                });
        alarmInstalled = true;
        printLog("Oplus Google wakeup alarm protection active (always enabled)");
    }

    private void startHook() {
        Class<?> helperClass = XposedHelpers.findClassIfExists(
                OPLUS_DEVICE_IDLE_HELPER, classLoader);
        if (helperClass == null) {
            throw new NoClassDefFoundError(OPLUS_DEVICE_IDLE_HELPER);
        }

        int whitelistHooks = 0;
        int restrictSwitchHooks = 0;
        for (Method method : helperClass.getDeclaredMethods()) {
            if ("getNewWhiteList".equals(method.getName())) {
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!isConfigurationReady() || !getBooleanConfig("dozeGoogleWhitelist", true)) return;
                        List<String> whiteList = findListArgument(param.args);
                        if (whiteList == null && param.getResult() instanceof List) {
                            whiteList = (List<String>) param.getResult();
                        }
                        if (whiteList == null) return;

                        for (String packageName : GOOGLE_DOZE_PACKAGES) {
                            if (!whiteList.contains(packageName)) {
                                whiteList.add(packageName);
                                printLog("Oplus Doze whitelist restored: " + packageName, true);
                            }
                        }
                    }
                });
                whitelistHooks++;
                printLog("Oplus Doze whitelist hook active: " + describeMethod(method));
            } else if ("getGoogleRestrictSwitch".equals(method.getName())
                    && (method.getReturnType() == boolean.class
                    || method.getReturnType() == Boolean.class)) {
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (isConfigurationReady() && getBooleanConfig("disableGoogleNetworkControl", true)) param.setResult(false);
                    }
                });
                restrictSwitchHooks++;
                printLog("Oplus Doze Google restriction hook active: " + describeMethod(method));
            }
        }
        if (whitelistHooks == 0) throw new NoSuchMethodError("getNewWhiteList");
        dozeInstalled = true;
        if (restrictSwitchHooks == 0) {
            printLog("OplusDeviceIdleHelper#getGoogleRestrictSwitch not found");
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> findListArgument(Object[] args) {
        for (Object arg : args) {
            if (arg instanceof List) return (List<String>) arg;
        }
        return null;
    }

    private static String describeMethod(Method method) {
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
