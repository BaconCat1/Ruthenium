package org.bacon.ruthenium.world;

import net.minecraft.server.world.ServerWorld;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class RegionizedWorldDataTest {

    @Test
    void redstoneTimeMirrorsWorldTimeOnConstruction() {
        final long initialTime = 120L;
        final ServerWorld world = new ServerWorld(null, null, initialTime);
        final RegionizedWorldData data = world.ruthenium$getWorldRegionData();
        Assertions.assertEquals(initialTime, data.getRedstoneGameTime(),
            "Redstone timer should mirror world time on construction");
    }
}
