package io.github.zopulus.ffc.xposed;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Bundle;
import io.github.zopulus.ffc.util.ConfigSnapshot;
import io.github.zopulus.ffc.util.PendingInstallReports;
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
    private static final PendingInstallReports reports = new PendingInstallReports(NewAppInstallReceiver::schedule);
    static {
        EVENTS.setRemoveOnCancelPolicy(true);
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

    @Override protected void onServiceConnected() {
        EVENTS.execute(reports::recovered);
    }

    private synchronized boolean registerListener() {
        if (registered) return true;
        if (context == null) return false;
        IntentFilter filter = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addDataScheme("package");
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context receiverContext, Intent intent) {
                if (intent == null || intent.getData() == null) return;
                boolean replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false);
                String target = intent.getData().getSchemeSpecificPart();
                if (target == null || SELF_PACKAGE_NAME.equals(target)) return;
                ColorOs17Compat.onPackageChanged(target);
                if (Intent.ACTION_PACKAGE_REMOVED.equals(intent.getAction())) {
                    if (!replacing) EVENTS.execute(() -> reports.removed(target));
                    return;
                }
                if (!NewAppAutoAllow.isNewInstall(intent.getAction(), replacing)) return;
                int uid = intent.getIntExtra(Intent.EXTRA_UID, -1);
                if (uid < 0) return;
                Bundle extras = new Bundle();
                extras.putString("eventId", java.util.UUID.randomUUID().toString());
                extras.putBoolean("replacing", false);
                extras.putInt("uid", uid);
                EVENTS.execute(() -> reports.installed(target, () -> reportInstallation(receiverContext, target, extras)));
            }
        };
        try {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
            registered = true;
            ColorOs17Compat.invalidateUidCache();
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
            // The manager may have been started solely by this call, before its service binds.
            Bundle report = new Bundle(extras);
            RemoteConfigReport.attach(report);
            Bundle reply = context.getContentResolver().call(Uri.parse("content://" + SELF_PACKAGE_NAME + ".provider"),
                    "newAppInstalled", target, report);
            return reply != null && reply.getBoolean("acknowledged", false);
        } catch (SecurityException rejected) {
            printLog("Installation reporter rejected");
            return true;
        } catch (Exception unavailable) {
            return false;
        }
    }
}
