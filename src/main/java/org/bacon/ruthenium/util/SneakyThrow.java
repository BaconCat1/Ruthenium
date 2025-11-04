package org.bacon.ruthenium.util;

/**
 * Utility for rethrowing checked exceptions without declaring them.
 */
public final class SneakyThrow {

    private SneakyThrow() {
    }

    public static RuntimeException sneaky(final Throwable throwable) {
        SneakyThrow.<RuntimeException>rethrow(throwable);
        return null; // unreachable
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void rethrow(final Throwable throwable) throws T {
        throw (T) throwable;
    }
}
