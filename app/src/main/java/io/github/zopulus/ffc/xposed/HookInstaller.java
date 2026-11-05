package io.github.zopulus.ffc.xposed;

/** Keep each independent protection install isolated and log failures by name. */
final class HookInstaller {
    private HookInstaller() {}

    interface Action { void run() throws Throwable; }

    static void install(String name, Action action) {
        try {
            action.run();
        } catch (Throwable error) {
            XposedModule.printLog("hook error " + name + ": " + error);
        }
    }
}
