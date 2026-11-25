package io.github.zopulus.ffc.xposed;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.SystemClock;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashSet;

import io.github.zopulus.ffc.libxposed.XC_MethodHook;
import io.github.zopulus.ffc.libxposed.XposedBridge;
import io.github.zopulus.ffc.libxposed.XposedHelpers;
import io.github.zopulus.ffc.util.ConfigSchema;
import io.github.zopulus.ffc.util.ConfigSnapshot;
import io.github.zopulus.ffc.util.WifiHeartbeatPolicy;

/**
 * Tightens GMS's {@code GCM_HB_ALARM} delivery window on Wi-Fi sessions that exhibit repeated
 * silent NAT/proxy connection aging, without requiring GMS scope or SSID/location permissions.
 */
public class WifiHeartbeatFix extends XposedModule {
    private static final String POWER_MANAGER_SERVICE = "com.android.server.power.PowerManagerService";
    private static final String ALARM_MANAGER_SERVICE = "com.android.server.alarm.AlarmManagerService";
    private static final String ALARM_CLASS = "com.android.server.alarm.Alarm";

    private final WifiHeartbeatPolicy policy = new WifiHeartbeatPolicy();
    private final LinkedHashSet<Long> activeWifiHandles = new LinkedHashSet<>();
    private long primaryWifiHandle = 0L;
    private volatile boolean networkCallbackRegistered = false;

    public WifiHeartbeatFix(ClassLoader classLoader) {
        super(classLoader);
        HookInstaller.install("wifi-heartbeat wakelock monitor", this::startWakeLockHook);
        HookInstaller.install("wifi-heartbeat alarm clamp", this::startAlarmHook);
    }

    @Override
    protected void onCanReadConfig() {
        syncEnabledState();
        ensureNetworkCallbackRegistered();
    }

    @Override
    protected void onConfigLoaded(ConfigSnapshot previous, ConfigSnapshot current) {
        syncEnabledState();
        ensureNetworkCallbackRegistered();
    }

    private void syncEnabledState() {
        boolean enabled = getBooleanConfig(ConfigSchema.KEY_ADAPTIVE_WIFI_HEARTBEAT,
                ConfigSchema.defaultValue(ConfigSchema.KEY_ADAPTIVE_WIFI_HEARTBEAT));
        policy.setEnabled(enabled, SystemClock.elapsedRealtime());
    }

    private void ensureNetworkCallbackRegistered() {
        if (networkCallbackRegistered) return;
        Context sysContext = context;
        if (sysContext == null) return;
        synchronized (this) {
            if (networkCallbackRegistered) return;
            try {
                ConnectivityManager cm = sysContext.getSystemService(ConnectivityManager.class);
                if (cm == null) return;
                // Track underlying physical Wi-Fi networks (NET_CAPABILITY_NOT_VPN is included by
                // default in Builder), remaining stable across TUN/TProxy restarts.
                NetworkRequest request = new NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build();
                cm.registerNetworkCallback(request, new ConnectivityManager.NetworkCallback() {
                    @Override
                    public void onAvailable(Network network) {
                        updateWifiNetworks(network.getNetworkHandle(), true);
                    }

                    @Override
                    public void onLost(Network network) {
                        updateWifiNetworks(network.getNetworkHandle(), false);
                    }
                });
                networkCallbackRegistered = true;
                printLog("Adaptive Wi-Fi FCM heartbeat network monitor active");
            } catch (Throwable error) {
                // ConnectivityService may still be starting during early boot; retry on next event.
                logOnce("Wi-Fi network callback deferred: " + error.getMessage());
            }
        }
    }

    private void updateWifiNetworks(long handle, boolean available) {
        if (handle == 0L) return;
        long selected;
        synchronized (activeWifiHandles) {
            if (available) {
                activeWifiHandles.add(handle);
                if (primaryWifiHandle == 0L) {
                    primaryWifiHandle = handle;
                }
            } else {
                activeWifiHandles.remove(handle);
                if (primaryWifiHandle == handle) {
                    primaryWifiHandle = activeWifiHandles.isEmpty()
                            ? 0L : activeWifiHandles.iterator().next();
                }
            }
            selected = primaryWifiHandle;
        }
        policy.onWifiChanged(selected, SystemClock.elapsedRealtime());
    }

    private void startWakeLockHook() throws ReflectiveOperationException {
        Class<?> pmsClass = XposedHelpers.findClass(POWER_MANAGER_SERVICE, classLoader);
        int hooked = 0;
        for (Method method : pmsClass.getDeclaredMethods()) {
            if (!"acquireWakeLockInternal".equals(method.getName())) continue;
            Class<?>[] types = method.getParameterTypes();
            final int tagIdx = findNthParameter(types, String.class, 0);
            final int pkgIdx = findNthParameter(types, String.class, 1);
            if (tagIdx < 0 || pkgIdx < 0) continue;

            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Object rawTag = param.args[tagIdx];
                    if (!(rawTag instanceof String)) return;
                    String tag = (String) rawTag;
                    if (tag.length() < 8 || tag.charAt(0) != 'G') return;
                    if (!WifiHeartbeatPolicy.GMS_PACKAGE.equals(param.args[pkgIdx])) return;

                    if (!networkCallbackRegistered) ensureNetworkCallbackRegistered();
                    int newTier = policy.onGmsWakeLockAcquired(tag, SystemClock.elapsedRealtime());
                    if (newTier != WifiHeartbeatPolicy.TIER_NONE) {
                        printLog("Wi-Fi silent connection aging confirmed; tightened FCM heartbeat to tier "
                                + newTier);
                    }
                }
            });
            hooked++;
            printLog("Wi-Fi heartbeat wakelock hook active: " + describeMethod(method));
        }
        if (hooked == 0) {
            throw new NoSuchMethodError(POWER_MANAGER_SERVICE + "#acquireWakeLockInternal");
        }
    }

    private void startAlarmHook() throws ReflectiveOperationException {
        Class<?> amsClass = XposedHelpers.findClass(ALARM_MANAGER_SERVICE, classLoader);
        Class<?> alarmClass = XposedHelpers.findClass(ALARM_CLASS, classLoader);

        final Field packageNameField = alarmClass.getDeclaredField("packageName");
        final Field listenerTagField = alarmClass.getDeclaredField("listenerTag");
        final Field origWhenField = alarmClass.getDeclaredField("origWhen");
        final Field windowLengthField = alarmClass.getDeclaredField("windowLength");
        final Field whenElapsedField = alarmClass.getDeclaredField("mWhenElapsed");
        final Field maxWhenElapsedField = alarmClass.getDeclaredField("mMaxWhenElapsed");
        final Field policyWhenField = alarmClass.getDeclaredField("mPolicyWhenElapsed");
        final Field requesterIdxField = alarmClass.getDeclaredField("REQUESTER_POLICY_INDEX");
        for (Field f : new Field[]{
                packageNameField, listenerTagField, origWhenField, windowLengthField,
                whenElapsedField, maxWhenElapsedField, policyWhenField, requesterIdxField
        }) {
            f.setAccessible(true);
        }
        final int requesterIndex = requesterIdxField.getInt(null);

        Method setImplLocked = XposedHelpers.findMethodExact(amsClass, "setImplLocked", alarmClass);
        XposedBridge.hookMethod(setImplLocked, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Object alarm = param.args[0];
                if (alarm == null) return;
                try {
                    if (!WifiHeartbeatPolicy.TAG_HB_ALARM.equals(listenerTagField.get(alarm))) return;
                    if (!WifiHeartbeatPolicy.GMS_PACKAGE.equals(packageNameField.get(alarm))) return;

                    if (!networkCallbackRegistered) ensureNetworkCallbackRegistered();
                    long now = SystemClock.elapsedRealtime();
                    long requestedWhen = whenElapsedField.getLong(alarm);
                    long currentWindow = windowLengthField.getLong(alarm);
                    WifiHeartbeatPolicy.AlarmAdjustment adj =
                            policy.onHeartbeatAlarmScheduled(now, requestedWhen, currentWindow);
                    if (adj == null) return;

                    origWhenField.setLong(alarm, adj.whenElapsed);
                    Object policyWhenObj = policyWhenField.get(alarm);
                    if (policyWhenObj instanceof long[]) {
                        long[] policyWhen = (long[]) policyWhenObj;
                        if (requesterIndex >= 0 && requesterIndex < policyWhen.length) {
                            policyWhen[requesterIndex] = adj.whenElapsed;
                        }
                    }
                    windowLengthField.setLong(alarm, adj.windowLength);
                    whenElapsedField.setLong(alarm, adj.whenElapsed);
                    maxWhenElapsedField.setLong(alarm, adj.maxWhenElapsed);
                    printLog("Clamped Wi-Fi GCM_HB_ALARM to +" + ((adj.whenElapsed - now) / 1000L)
                            + "s (window " + (adj.windowLength / 1000L) + "s)", true);
                } catch (Throwable error) {
                    logOnce("Wi-Fi heartbeat alarm clamp failed: " + error);
                }
            }
        });
        printLog("Wi-Fi heartbeat alarm hook active: " + describeMethod(setImplLocked));
    }

    private static int findNthParameter(Class<?>[] types, Class<?> target, int ordinal) {
        int seen = 0;
        for (int i = 0; i < types.length; i++) {
            if (types[i] == target) {
                if (seen == ordinal) return i;
                seen++;
            }
        }
        return -1;
    }
}
