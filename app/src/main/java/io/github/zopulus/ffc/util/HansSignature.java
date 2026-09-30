package io.github.zopulus.ffc.util;

import java.util.Arrays;

/** Hans cgroup entry points verified against PLK110 ColorOS 16 and ColorOS 17 17.0.0.102. */
public final class HansSignature {
    private HansSignature() {}

    /**
     * ColorOS 16: FastFreezeEnter(int uid). ColorOS 17: fastFreezeEnter(int uid, String pkgName).
     * Both take the target UID first; anything else fails closed.
     */
    public static boolean isFastFreezeEnter(String name, String returnType, String[] types) {
        if (!"void".equals(returnType)) return false;
        if ("FastFreezeEnter".equals(name)) return Arrays.equals(types, new String[]{"int"});
        if ("fastFreezeEnter".equals(name)) return Arrays.equals(types, new String[]{"int", "java.lang.String"});
        return false;
    }
}
