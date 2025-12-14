package org.bacon.ruthenium.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class CollectionUtils {

    private CollectionUtils() {
    }

    public static <T> ArrayList<T> mutableCopy(final List<? extends T> source) {
        Objects.requireNonNull(source, "source");
        return new ArrayList<>(source);
    }
}

