package io.github.zopulus.ffc.libxposed;

import io.github.zopulus.ffc.util.FcmTrust;
import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class HookInvocationTest {
    @Test public void failedNestedValidationStaysMaskedUntilOriginalReturns() throws Throwable {
        Runnable outer = FcmTrust.enter("target", 10123);
        try {
            HookInvocation.invoke(param(), new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    p.addFinallyAction(FcmTrust.enter(null, -1));
                    // Same failure mask sequence as BroadcastFix's guarded entry.
                    p.addFinallyAction(FcmTrust.enter("target", 10123));
                    p.addFinallyAction(FcmTrust.enter(null, -1));
                }
            }, args -> {
                assertFalse(FcmTrust.matches(FcmTrust.RECEIVE, "target"));
                return null;
            }, error -> fail(error.toString()));
            assertTrue(FcmTrust.matches(FcmTrust.RECEIVE, "target"));
        } finally { outer.run(); }
        assertFalse(FcmTrust.matches(FcmTrust.RECEIVE, "target"));
    }
    private XC_MethodHook.MethodHookParam param() {
        XC_MethodHook.MethodHookParam value = new XC_MethodHook.MethodHookParam();
        value.args = new Object[]{7};
        return value;
    }

    @Test public void cleansTrustOnNormalAndEarlyReturn() throws Throwable {
        for (boolean early : new boolean[]{false, true}) {
            List<Throwable> errors = new ArrayList<>();
            Object result = HookInvocation.invoke(param(), new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    p.finallyAction = FcmTrust.enter("target", 10123);
                    if (early) p.setResult(9);
                }
            }, args -> {
                assertFalse(early);
                assertTrue(FcmTrust.matches(FcmTrust.RECEIVE, "target"));
                return 8;
            }, errors::add);
            assertEquals(early ? 9 : 8, result);
            assertTrue(errors.isEmpty());
            assertFalse(FcmTrust.matches(FcmTrust.RECEIVE, "target"));
        }
    }

    @Test public void failedBeforeClearsTrustAndRestoresOriginalCall() throws Throwable {
        List<Throwable> errors = new ArrayList<>();
        Object result = HookInvocation.invoke(param(), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                p.finallyAction = FcmTrust.enter("target", 10123);
                p.args[0] = 99;
                p.setResult(99);
                throw new IllegalStateException("before failed");
            }
            @Override protected void afterHookedMethod(MethodHookParam p) { fail("after must not run"); }
        }, args -> {
            assertFalse(FcmTrust.matches(FcmTrust.RECEIVE, "target"));
            return args[0];
        }, errors::add);
        assertEquals(7, result);
        assertEquals(1, errors.size());
    }

    @Test public void originalExceptionSurvivesFailedAfterAndCleansTrust() throws Throwable {
        List<Throwable> errors = new ArrayList<>();
        Exception original = new Exception("framework failure");
        try {
            HookInvocation.invoke(param(), new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    p.finallyAction = FcmTrust.enter("target", 10123);
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    p.setResult("wrong");
                    throw new IllegalStateException("after failed");
                }
            }, args -> { throw original; }, errors::add);
            fail("must propagate original");
        } catch (Exception actual) { assertSame(original, actual); }
        assertEquals(1, errors.size());
        assertFalse(FcmTrust.matches(FcmTrust.RECEIVE, "target"));
    }

    @Test public void nestedRegistrationsExecuteEachCallbackOnceAndRestoreScope() throws Throwable {
        List<String> order = new ArrayList<>();
        XC_MethodHook outer = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                order.add("outer-before"); p.finallyAction = FcmTrust.enter("target", 10123);
            }
            @Override protected void afterHookedMethod(MethodHookParam p) {
                assertTrue(FcmTrust.matches(FcmTrust.RECEIVE, "target")); order.add("outer-after");
            }
        };
        XC_MethodHook inner = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                order.add("inner-before"); p.finallyAction = FcmTrust.enter(null, -1);
            }
            @Override protected void afterHookedMethod(MethodHookParam p) { order.add("inner-after"); }
        };
        HookInvocation.invoke(param(), outer, args -> HookInvocation.invoke(param(), inner, nested -> {
            assertFalse(FcmTrust.matches(FcmTrust.RECEIVE, "target"));
            order.add("original"); return null;
        }, error -> fail(error.toString())), error -> fail(error.toString()));
        assertEquals(java.util.Arrays.asList("outer-before", "inner-before", "original", "inner-after", "outer-after"), order);
        assertFalse(FcmTrust.matches(FcmTrust.RECEIVE, "target"));
    }
}
