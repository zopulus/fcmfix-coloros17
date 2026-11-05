package io.github.zopulus.ffc.util;

import java.lang.reflect.Method;

public final class XposedUtils {
    private XposedUtils() {}

    public static Method findMethod(Class<?> clazz, String methodName, int parameterCount) {
        Method method = null;
        for (Method m : clazz.getDeclaredMethods()) {
            if (m.getName().equals(methodName) && m.getParameterTypes().length == parameterCount) {
                method = m;
            }
        }
        return method;
    }
}
