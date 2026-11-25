package io.github.zopulus.ffc.libxposed;

import android.content.SharedPreferences;

import java.lang.reflect.Constructor;
import java.lang.reflect.Member;
import java.lang.reflect.Method;


import io.github.libxposed.api.XposedInterface;

public final class XposedBridge {

    private static final Object[] EMPTY_ARGS = new Object[0];
    private static XposedInterface xposedInterface;


    private XposedBridge() {
    }

    public static void init(XposedInterface xposed) {
        xposedInterface = xposed;
    }

    public static void log(String text) {
        android.util.Log.i("fcmfix", text);
    }

    public static SharedPreferences getRemotePreferences(String group) {
        ensureInit();
        return xposedInterface.getRemotePreferences(group);
    }

    public static XposedInterface.HookHandle hookMethod(Member member, XC_MethodHook callback) {
        ensureInit();
        if (!(member instanceof Method) && !(member instanceof Constructor<?>)) {
            throw new IllegalArgumentException("Only Method/Constructor can be hooked");
        }

        // Hoist per-registration closure; restore args lazily from Chain only if beforeHookedMethod throws.
        java.util.function.Consumer<Throwable> errorLogger =
                error -> log("Hook failed: " + member + ": " + error);
        return xposedInterface.hook((java.lang.reflect.Executable) member)
                .intercept(chain -> {
                    XC_MethodHook.MethodHookParam param = new XC_MethodHook.MethodHookParam();
                    param.method = chain.getExecutable();
                    param.thisObject = chain.getThisObject();
                    java.util.List<Object> chainArgs = chain.getArgs();
                    param.args = chainArgs.isEmpty() ? EMPTY_ARGS : chainArgs.toArray(EMPTY_ARGS);
                    return HookInvocation.invoke(param, callback, chain::proceed,
                            () -> chainArgs.isEmpty() ? EMPTY_ARGS : chainArgs.toArray(EMPTY_ARGS),
                            errorLogger);
                });
    }

    /**
     * Force a caller back to non-inlined execution so hooks on its short callees fire.
     * Returns false instead of throwing; a failed deoptimization only loses that layer.
     */
    public static boolean deoptimize(Member member) {
        ensureInit();
        if (!(member instanceof java.lang.reflect.Executable)) return false;
        try {
            return xposedInterface.deoptimize((java.lang.reflect.Executable) member);
        } catch (Throwable e) {
            log("deoptimize failed: " + member + ": " + e);
            return false;
        }
    }

    private static void ensureInit() {
        if (xposedInterface == null) {
            throw new IllegalStateException("XposedBridge not initialized");
        }
    }
}
