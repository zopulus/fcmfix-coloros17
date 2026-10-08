package io.github.zopulus.ffc.util;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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
    @Test public void whitelistKeepsExistingEntriesAndDeduplicatesWholeUids() {
        List<Object> list = new ArrayList<>(Arrays.asList("10124:555", 10125, "0", null));
        GoogleProtection.addUid(list, 10124);
        GoogleProtection.addUid(list, 10124);
        GoogleProtection.addUid(list, 10125);
        GoogleProtection.addUid(list, 0);
        assertEquals(Arrays.asList("10124:555", 10125, "0", null, "10124"), list);
    }
}
