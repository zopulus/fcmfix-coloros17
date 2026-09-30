package io.github.zopulus.ffc.libxposed;

import java.util.function.Consumer;

/** Shared production/test dispatcher. Hook failures must not replace framework failures. */
final class HookInvocation {
    interface Original { Object call(Object[] args) throws Throwable; }

    static Object invoke(XC_MethodHook.MethodHookParam param, XC_MethodHook callback,
                         Original original, Consumer<Throwable> log) throws Throwable {
        try {
            boolean beforeSucceeded = false;
            Object[] originalArgs = param.args.clone();
            try {
                callback.beforeHookedMethod(param);
                beforeSucceeded = true;
            } catch (Throwable error) {
                log.accept(error);
                cleanup(param, log);
                param.args = originalArgs;
                param.setResult(null);
                param.resetReturnEarly();
            }
            if (!param.isReturnEarly()) {
                try {
                    param.setResult(original.call(param.args));
                } catch (Throwable error) {
                    param.setThrowable(error);
                }
                param.resetReturnEarly();
            }
            if (beforeSucceeded) {
                Object result = param.getResult();
                Throwable failure = param.getThrowable();
                try {
                    callback.afterHookedMethod(param);
                } catch (Throwable error) {
                    log.accept(error);
                    if (failure == null) param.setResult(result);
                    else param.setThrowable(failure);
                }
            }
            return param.getResultOrThrowable();
        } finally {
            cleanup(param, log);
        }
    }

    private static void cleanup(XC_MethodHook.MethodHookParam param, Consumer<Throwable> log) {
        Runnable action = param.finallyAction;
        param.finallyAction = null;
        if (action != null) {
            try { action.run(); } catch (Throwable error) { log.accept(error); }
        }
    }
}
