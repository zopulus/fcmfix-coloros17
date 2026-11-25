package io.github.zopulus.ffc.util;
import org.junit.Test;
import static org.junit.Assert.*;
public class GoogleProtectionTest {
    @Test public void onlyTheBatteryDeepSleepForceStopIsBlocked() {
        assertTrue(GoogleProtection.skipDeepSleepForceStop("com.google.android.gms", "DeepSleepLogicDisNetRestore", 2,
                "SCENE_COMMON_REQUEST", "CommonExternalClean", "clean", "com.oplus.battery"));
        assertFalse(GoogleProtection.skipDeepSleepForceStop("another.app", "DeepSleepLogicDisNetRestore", 2,
                "SCENE_COMMON_REQUEST", "CommonExternalClean", "clean", "com.oplus.battery"));
        assertFalse(GoogleProtection.skipDeepSleepForceStop("com.google.android.gms", "UserRequest", 2,
                "SCENE_COMMON_REQUEST", "CommonExternalClean", "clean", "com.oplus.battery"));
        assertFalse(GoogleProtection.skipDeepSleepForceStop("com.google.android.gms", "DeepSleepLogicDisNetRestore", 1,
                "SCENE_COMMON_REQUEST", "CommonExternalClean", "clean", "com.oplus.battery"));
        assertFalse(GoogleProtection.skipDeepSleepForceStop("com.google.android.gms", "DeepSleepLogicDisNetRestore", 2,
                "SCENE_COMMON_REQUEST", "CommonExternalClean", "clean", "another.app"));
    }
}
