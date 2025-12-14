package org.bacon.ruthenium.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CollectionUtilsTest {

    @Test
    void mutableCopyShouldReturnMutableList() {
        final ArrayList<Integer> copy = CollectionUtils.mutableCopy(List.of(1, 2));
        copy.add(3);
        assertEquals(List.of(1, 2, 3), copy);
    }

    @Test
    void mutableCopyShouldNotReturnSameInstance() {
        final ArrayList<Integer> input = new ArrayList<>(List.of(1));
        final ArrayList<Integer> copy = CollectionUtils.mutableCopy(input);
        assertNotSame(input, copy);
        assertEquals(input, copy);
    }
}

