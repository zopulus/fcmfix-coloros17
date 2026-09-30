package io.github.zopulus.ffc.util;

import java.util.Arrays;

public final class NotificationSignature {
    private NotificationSignature() {}

    public static int reasonIndex(String returnType, String[] types) {
        if (!"void".equals(returnType)) return -1;
        // PLK110 services.jar / Android 15-17 (ColorOS 16 and 17.0.0.102).
        if (Arrays.equals(types, new String[]{"int", "int", "java.lang.String", "java.lang.String",
                "int", "int", "int", "int"})) return 7;
        // AOSP Android 13 (also used by older supported framework branches).
        if (Arrays.equals(types, new String[]{"int", "int", "java.lang.String", "java.lang.String",
                "int", "int", "boolean", "int", "int",
                "com.android.server.notification.ManagedServices$ManagedServiceInfo"})) return 8;
        return -1;
    }
}
