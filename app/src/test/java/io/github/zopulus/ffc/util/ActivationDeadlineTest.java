package io.github.zopulus.ffc.util;
import org.junit.Test;
import static org.junit.Assert.*;
public class ActivationDeadlineTest {
    @Test public void expiredOrUnboundedRequestsAreRejected() {
        assertFalse(ActivationDeadline.isValid(1000,1000));
        assertFalse(ActivationDeadline.isValid(999,1000));
        assertFalse(ActivationDeadline.isValid(2501,1000));
        assertFalse(ActivationDeadline.isValid(Long.MAX_VALUE,1000));
        assertFalse(ActivationDeadline.isValid(0,0));
        assertTrue(ActivationDeadline.isValid(2500,1000));
    }
    @Test public void requestMayExpireDuringAuthorization() {
        assertTrue(ActivationDeadline.isValid(2500,1000));
        assertFalse(ActivationDeadline.isValid(2500,2500));
    }
}
