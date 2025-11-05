package org.bacon.ruthenium.command;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RegionCommandTest {

    @Test
    void formatDoubleAdjustsPrecisionForSmallValues() {
        assertEquals("0.00", RegionCommand.formatDouble(0.0D));
        assertEquals("1.235", RegionCommand.formatDouble(1.23456D));
        assertEquals("-4.568", RegionCommand.formatDouble(-4.5678D));
        assertEquals("0.1235", RegionCommand.formatDouble(0.123456D));
        assertEquals("0.000123", RegionCommand.formatDouble(0.000123456D));
        assertEquals("Infinity", RegionCommand.formatDouble(Double.POSITIVE_INFINITY));
    }
}
