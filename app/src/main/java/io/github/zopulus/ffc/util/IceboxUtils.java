package io.github.zopulus.ffc.util;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.RequiresPermission;
import androidx.core.content.ContextCompat;

public class IceboxUtils extends BroadcastReceiver {
    private static final java.util.concurrent.ThreadPoolExecutor ACTIVATOR =
            new java.util.concurrent.ThreadPoolExecutor(2, 2, 30,
                    java.util.concurrent.TimeUnit.SECONDS,
                    new java.util.concurrent.ArrayBlockingQueue<>(2),
                    new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());

    /** System entry waits briefly while the module provider uses its own Ice Box grant. */
    public static boolean activateBeforeDelivery(Context context, String target, long minimumRevision) {
        long deadline = SystemClock.elapsedRealtime() + 1500;
        return ActivationGate.await(ACTIVATOR, () -> {
            if (SystemClock.elapsedRealtime() >= deadline) return false;
            Bundle request = new Bundle();
            request.putLong("deadline", deadline);
            request.putLong("minimumRevision", minimumRevision);
            io.github.zopulus.ffc.xposed.RemoteConfigReport.attach(request);
            if (SystemClock.elapsedRealtime() >= deadline) return false;
            Bundle result = context.getContentResolver().call(
                    Uri.parse("content://io.github.zopulus.ffc.provider"), "activateFrozenApp", target, request);
            return result != null && result.getBoolean("enabled", false);
        }, 1500);
    }
    public final static int REQUEST_CODE = 0x2333;
    public final static String PACKAGE_NAME = "com.catchingnow.icebox";
    public final static String SDK_PERMISSION = PACKAGE_NAME + ".SDK";
    private static final Uri PERMISSION_URI = Uri.parse("content://" + PACKAGE_NAME + ".SDK");
    private static final Uri NO_PERMISSION_URI = Uri.parse("content://" + PACKAGE_NAME + ".STATE");
    private static final String TAG = "IceboxUtils";
    private static PendingIntent authorizedPendingIntent = null;

    public static PendingIntent queryPermission(Context context) {
        if (authorizedPendingIntent == null) {
            authorizedPendingIntent = PendingIntent.getBroadcast(context, REQUEST_CODE, new Intent(context, IceboxUtils.class), PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        }
        return authorizedPendingIntent;
    }

    private static boolean queryWorkMode(Context context) {
        try {
            Bundle extra = new Bundle();
            extra.putParcelable("authorize", queryPermission(context));
            Bundle bundle = context.getContentResolver().call(NO_PERMISSION_URI, "query_mode", null, extra);
            String mode = bundle == null ? null : bundle.getString("work_mode");
            return mode != null && !mode.isEmpty() && !"MODE_NOT_AVAILABLE".equals(mode);
        } catch (Throwable e) {
            Log.e(TAG, "[icebox] queryWorkMode: " + e.getMessage());
            return false;
        }
    }

    public enum AppState { ENABLED, DISABLED, UNKNOWN }

    public static AppState appState(Context context, String packageName) {
        try {
            ApplicationInfo applicationInfo = context.getPackageManager().getApplicationInfo(packageName, PackageManager.MATCH_UNINSTALLED_PACKAGES | PackageManager.MATCH_DISABLED_COMPONENTS);
            if ((applicationInfo.flags & ApplicationInfo.FLAG_INSTALLED) == 0) return AppState.UNKNOWN;
            return applicationInfo.enabled ? AppState.ENABLED : AppState.DISABLED;
        } catch (Throwable e) {
            Log.e(TAG, "[icebox] " + packageName + " " + e.getMessage());
        }
        return AppState.UNKNOWN;
    }

    @RequiresPermission(SDK_PERMISSION)
    public static void enableApp(Context context, boolean enable, String... packageNames) {
        int userHandle = Process.myUserHandle().hashCode();
        Bundle extra = new Bundle();
        extra.putParcelable("authorize", queryPermission(context));
        extra.putStringArray("package_names", packageNames);
        extra.putInt("user_handle", userHandle);
        extra.putBoolean("enable", enable);
        context.getContentResolver().call(PERMISSION_URI, "set_enable", null, extra);
    }

    public static boolean activeAppBeforeDeadline(Context context, String pkg, long deadline,
                                                  java.util.concurrent.Callable<Boolean> permitted) throws Exception {
        try {
            if (SystemClock.elapsedRealtime() >= deadline) return false;
            AppState state = appState(context, pkg);
            if (state == AppState.UNKNOWN) return false;
            if (state == AppState.DISABLED &&
                    (ContextCompat.checkSelfPermission(context, SDK_PERMISSION) != PackageManager.PERMISSION_GRANTED ||
                            !queryWorkMode(context))) return false;
            if (SystemClock.elapsedRealtime() >= deadline || !permitted.call()
                    || SystemClock.elapsedRealtime() >= deadline) return false;
            if (state == AppState.ENABLED) return true;
            enableApp(context, true, pkg);
            return appState(context, pkg) == AppState.ENABLED;
        } catch (RuntimeException error) {
            Log.w(TAG, "Ice Box activation failed for " + pkg, error);
            return false;
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
    }
}
