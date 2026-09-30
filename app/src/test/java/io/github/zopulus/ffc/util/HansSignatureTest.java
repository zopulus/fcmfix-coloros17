package io.github.zopulus.ffc.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class HansSignatureTest {
    @Test public void colorOs16AndColorOs17FastFreezeEntriesMatch() {
        assertTrue(HansSignature.isFastFreezeEnter("FastFreezeEnter", "void", new String[]{"int"}));
        assertTrue(HansSignature.isFastFreezeEnter("fastFreezeEnter", "void", new String[]{"int", "java.lang.String"}));
    }
    @Test public void mismatchedNameShapeOrReturnFailsClosed() {
        assertFalse(HansSignature.isFastFreezeEnter("fastFreezeEnter", "void", new String[]{"int"}));
        assertFalse(HansSignature.isFastFreezeEnter("FastFreezeEnter", "void", new String[]{"int", "java.lang.String"}));
        assertFalse(HansSignature.isFastFreezeEnter("fastFreezeEnter", "boolean", new String[]{"int", "java.lang.String"}));
        assertFalse(HansSignature.isFastFreezeEnter("fastFreezeEnter", "void", new String[]{"java.lang.String", "int"}));
        assertFalse(HansSignature.isFastFreezeEnter("fastFreezeExit", "void", new String[]{"int", "java.lang.String"}));
    }
}
