package io.github.zopulus.ffc.xposed;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Bundle;
import io.github.zopulus.ffc.util.ConfigSnapshot;
import io.github.zopulus.ffc.util.NewAppAutoAllow;
import io.github.zopulus.ffc.util.RetryingTask;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** A system-server receiver remains available while the manager app is closed. No package scan. */
public final class NewAppInstallReceiver extends XposedModule {
    private boolean registered;
    private RetryingTask registration;
    private static final ScheduledThreadPoolExecutor EVENTS = new ScheduledThreadPoolExecutor(1, action -> {
        Thread thread = new Thread(action, "FCMFix-install");
        thread.setDaemon(true);
        return thread;
    });
    static {
        EVENTS.setKeepAliveTime(30, TimeUnit.SECONDS);
        EVENTS.allowCoreThreadTimeOut(true);
    }

    public NewAppInstallReceiver(ClassLoader loader) { super(loader); }

    @Override protected synchronized void onCanReadConfig() {
        if (registered) return;
        // This callback can run from the superclass constructor, so initialize lazily.
        if (registration == null) {
            registration = new RetryingTask(NewAppInstallReceiver::schedule,
                    this::registerListener, 1000, 5000, 15000);
        }
        registration.request();
    }

    @Override protected void onConfigLoaded(ConfigSnapshot previous, ConfigSnapshot current) {
        onCanReadConfig();
    }

    private synchronized boolean registerListener() {
        if (registered) return true;
        if (context == null) return false;
        IntentFilter filter = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
        filter.addDataScheme("package");
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context receiverContext, Intent intent) {
                if (intent == null || !NewAppAutoAllow.isNewInstall(intent.getAction(),
                        intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) || intent.getData() == null) return;
                String target = intent.getData().getSchemeSpecificPart();
                if (target == null || SELF_PACKAGE_NAME.equals(target)) return;
                Bundle extras = new Bundle();
                extras.putString("eventId", java.util.UUID.randomUUID().toString());
                extras.putBoolean("replacing", false);
                extras.putInt("uid", intent.getIntExtra(Intent.EXTRA_UID, -1));
                new RetryingTask(NewAppInstallReceiver::schedule,
                        () -> reportInstallation(receiverContext, target, extras), 200, 600, 1500).request();
            }
        };
        try {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
            registered = true;
            return true;
        } catch (RuntimeException unavailable) {
            printLog("Installation listener registration failed: " + unavailable);
            return false;
        }
    }

    private static RetryingTask.Ticket schedule(Runnable action, long delay) {
        java.util.concurrent.ScheduledFuture<?> future = EVENTS.schedule(action, delay, TimeUnit.MILLISECONDS);
        return () -> future.cancel(false);
    }

    private static boolean reportInstallation(Context context, String target, Bundle extras) {
        try {
            Bundle reply = context.getContentResolver().call(Uri.parse("content://" + SELF_PACKAGE_NAME + ".provider"),
                    "newAppInstalled", target, extras);
            return reply != null && reply.getBoolean("acknowledged", false);
        } catch (SecurityException rejected) {
            printLog("Installation reporter rejected");
            return true;
        } catch (Exception unavailable) {
            return false;
        }
    }
}
