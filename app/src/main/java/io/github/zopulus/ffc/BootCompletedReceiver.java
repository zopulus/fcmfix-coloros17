package io.github.zopulus.ffc;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class BootCompletedReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        Log.i("fcmfix", "Boot completed, notify hooked processes to reload config");
        // 开机后通知已注入的 system_server 和电池进程重新加载配置文件
        try {
            context.sendBroadcast(new Intent("io.github.zopulus.ffc.update.config"));
        } catch (Throwable e) {
            Log.e("fcmfix", "send update config broadcast failed: " + e.getMessage());
        }
    }
}
