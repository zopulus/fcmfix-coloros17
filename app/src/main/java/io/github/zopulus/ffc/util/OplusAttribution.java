package io.github.zopulus.ffc.util;

import java.util.Arrays;

/**
 * Exact signatures verified against the PLK110 ColorOS 16 framework and unchanged in ColorOS 17
 * 17.0.0.102(CN01). Unknown OTAs fail closed and log the unsupported signature.
 */
public final class OplusAttribution {
    private static final String MANAGER = "com.android.server.am.OplusAppStartupManager";
    private static final String PROCESS = "com.android.server.am.ProcessRecord";
    private static final String SERVICE = "com.android.server.am.ServiceRecord";
    private static final String INTENT = "android.content.Intent";
    private static final String STRING = "java.lang.String";

    private OplusAttribution() {}

    public static int callerIndex(String owner, String name, String result, String[] types) {
        if (!"boolean".equals(result)) return -1;
        if ((MANAGER + "$OplusStartupStrategy").equals(owner)
                && "isAppClassifyRestricted".equals(name)
                && Arrays.equals(types, new String[]{STRING, STRING, STRING, "int", "int", INTENT})) {
            return 3;
        }
        if (!MANAGER.equals(owner)) return -1;
        if ("isAllowStartFromBindService".equals(name)
                && Arrays.equals(types, new String[]{PROCESS, STRING, "int", SERVICE, INTENT, STRING})) {
            return 2;
        }
        if ("isAllowStartFromStartService".equals(name)
                && Arrays.equals(types, new String[]{PROCESS, "int", "int", STRING, SERVICE, INTENT})) {
            return 2;
        }
        return -1;
    }
}
