package io.github.zopulus.ffc.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class NotificationSignatureTest {
    @Test public void colorOs16SignatureMatches() {
        assertEquals(7, NotificationSignature.reasonIndex("void", new String[]{"int", "int", "java.lang.String",
                "java.lang.String", "int", "int", "int", "int"}));
    }
    @Test public void oldAospSignatureMatches() {
        assertEquals(8, NotificationSignature.reasonIndex("void", new String[]{"int", "int", "java.lang.String",
                "java.lang.String", "int", "int", "boolean", "int", "int",
                "com.android.server.notification.ManagedServices$ManagedServiceInfo"}));
    }
    @Test public void changedSignatureFailsClosed() {
        assertEquals(-1, NotificationSignature.reasonIndex("void", new String[]{"int", "int", "java.lang.String",
                "java.lang.String", "boolean", "int", "int", "int"}));
        assertEquals(-1, NotificationSignature.reasonIndex("int", new String[]{"int", "int", "java.lang.String",
                "java.lang.String", "int", "int", "int", "int"}));
        assertEquals(-1, NotificationSignature.reasonIndex("void", new String[0]));
    }
}
