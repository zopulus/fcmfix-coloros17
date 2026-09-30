package io.github.zopulus.ffc.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class OplusAttributionTest {
    private static final String OWNER = "com.android.server.am.OplusAppStartupManager";
    private String[] bind() { return new String[]{"com.android.server.am.ProcessRecord", "java.lang.String",
            "int", "com.android.server.am.ServiceRecord", "android.content.Intent", "java.lang.String"}; }

    @Test public void verifiedBindSignatureUsesFrameworkCallingUid() {
        assertEquals(2, OplusAttribution.callerIndex(OWNER, "isAllowStartFromBindService", "boolean", bind()));
    }
    @Test public void unknownOwnerReturnTypeAndChangedOverloadFailClosed() {
        assertEquals(-1, OplusAttribution.callerIndex("evil", "isAllowStartFromBindService", "boolean", bind()));
        assertEquals(-1, OplusAttribution.callerIndex(OWNER, "isAllowStartFromBindService", "int", bind()));
        String[] changed = bind(); changed[1] = "int";
        assertEquals(-1, OplusAttribution.callerIndex(OWNER, "isAllowStartFromBindService", "boolean", changed));
        assertEquals(-1, OplusAttribution.callerIndex(OWNER, "isAllowStartFromBindService", "boolean", new String[0]));
    }
    @Test public void startAndClassifyUseVerifiedPositions() {
        assertEquals(2, OplusAttribution.callerIndex(OWNER, "isAllowStartFromStartService", "boolean",
                new String[]{"com.android.server.am.ProcessRecord", "int", "int", "java.lang.String",
                        "com.android.server.am.ServiceRecord", "android.content.Intent"}));
        assertEquals(3, OplusAttribution.callerIndex(OWNER + "$OplusStartupStrategy", "isAppClassifyRestricted", "boolean",
                new String[]{"java.lang.String", "java.lang.String", "java.lang.String", "int", "int", "android.content.Intent"}));
    }
}
