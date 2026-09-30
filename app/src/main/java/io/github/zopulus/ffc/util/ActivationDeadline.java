package io.github.zopulus.ffc.util;

/** Absolute monotonic deadlines are checked again in the module process before activation. */
public final class ActivationDeadline {
    private ActivationDeadline() {}
    public static boolean isValid(long deadline, long now) {
        return now >= 0 && deadline > now && deadline - now <= 1500;
    }
}
