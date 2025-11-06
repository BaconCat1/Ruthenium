package org.bacon.ruthenium.debug;

import org.bacon.ruthenium.Ruthenium;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegionDebugTest {

    @BeforeEach
    void setUp() {
        RegionDebug.setAllQuietly(false);
    }

    @AfterEach
    void tearDown() {
        RegionDebug.setAllQuietly(false);
    }

    @Test
    void legacyToggleSynchronizesWithRegionDebugCategories() {
        assertFalse(Ruthenium.isRegionDebugLoggingEnabled(), "Region logging should start disabled");

        Ruthenium.setRegionDebugLoggingEnabled(true);
        assertTrue(Ruthenium.isRegionDebugLoggingEnabled(), "Legacy toggle should enable logging");
        assertTrue(RegionDebug.isEnabled(RegionDebug.LogCategory.LIFECYCLE));
        assertTrue(RegionDebug.isEnabled(RegionDebug.LogCategory.MOVEMENT));
        assertTrue(RegionDebug.isEnabled(RegionDebug.LogCategory.SCHEDULER));

        Ruthenium.setRegionDebugLoggingEnabled(false);
        assertFalse(Ruthenium.isRegionDebugLoggingEnabled(), "Legacy toggle should disable logging");
        assertFalse(RegionDebug.isEnabled(RegionDebug.LogCategory.LIFECYCLE));
        assertFalse(RegionDebug.isEnabled(RegionDebug.LogCategory.MOVEMENT));
        assertFalse(RegionDebug.isEnabled(RegionDebug.LogCategory.SCHEDULER));
    }
}
