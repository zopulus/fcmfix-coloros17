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

import java.util.Objects;

public class IceboxUtils extends BroadcastReceiver {
    private static final java.util.concurrent.ThreadPoolExecutor ACTIVATOR =
            new java.util.concurrent.ThreadPoolExecutor(2, 2, 30,
                    java.util.concurrent.TimeUnit.SECONDS,
                    new java.util.concurrent.ArrayBlockingQueue<>(2),
                    new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());

    /** System entry waits briefly while the module provider uses its own Ice Box grant. */
    public static boolean activateBeforeDelivery(Context context, String target) {
        long deadline = SystemClock.elapsedRealtime() + 1500;
        return ActivationGate.await(ACTIVATOR, () -> {
            if (SystemClock.elapsedRealtime() >= deadline) return false;
            Bundle request = new Bundle();
            request.putLong("deadline", deadline);
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
    private static boolean isIceBoxWorking = false;
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
            assert bundle != null;
            return !Objects.equals(bundle.getString("work_mode", null), "MODE_NOT_AVAILABLE");
        } catch (Throwable e) {
            Log.e(TAG, "[icebox] queryWorkMode: " + e.getMessage());
            return false;
        }
    }

    public static boolean isAppEnabled(Context context, String packageName) {
        try {
            ApplicationInfo applicationInfo = context.getPackageManager().getApplicationInfo(packageName, PackageManager.MATCH_UNINSTALLED_PACKAGES | PackageManager.MATCH_DISABLED_COMPONENTS);
            return applicationInfo.enabled;
        } catch (Throwable e) {
            Log.e(TAG, "[icebox] " + packageName + " " + e.getMessage());
        }
        return true;
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

    public static void activeApp(Context context, String pkg) {
        activeAppBeforeDeadline(context, pkg, Long.MAX_VALUE);
    }

    public static void activeAppBeforeDeadline(Context context, String pkg, long deadline) {
        try {
            if (SystemClock.elapsedRealtime() >= deadline) return;
            if (!isIceBoxWorking) {
                if (ContextCompat.checkSelfPermission(context, SDK_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
                    Log.e(TAG, "[icebox] need permission " + pkg);
                    return;
                }
                if (!queryWorkMode(context)) {
                    Log.e(TAG, "[icebox] is not working...");
                    return;
                }
                isIceBoxWorking = true;
            }
            if (!isAppEnabled(context, pkg)) {
                if (SystemClock.elapsedRealtime() >= deadline) return;
                enableApp(context, true, pkg);
                Log.i(TAG, "[icebox] successfully enable " + pkg);
            } else {
                Log.e(TAG, "[icebox] has been enabled " + pkg);
            }
        } catch (Throwable e) {
            Log.e(TAG, "[icebox] " + pkg + " " + e.getMessage());
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
    }
}
