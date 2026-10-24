package io.github.zopulus.ffc.util;

/** Keep automatic enrollment identical to the existing app list detection. */
public final class FcmApps {
    private FcmApps() {}
    public static boolean isReceiverName(String name) {
        return "com.google.firebase.iid.FirebaseInstanceIdReceiver".equals(name)
                || "com.google.android.gms.measurement.AppMeasurementReceiver".equals(name);
    }
}
