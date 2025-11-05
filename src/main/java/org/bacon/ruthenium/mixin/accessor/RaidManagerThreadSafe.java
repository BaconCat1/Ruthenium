package org.bacon.ruthenium.mixin.accessor;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.village.raid.Raid;

public interface RaidManagerThreadSafe {

    void ruthenium$globalTick();

    Raid ruthenium$getRaidFor(ServerWorld world, BlockPos pos, int searchDistance);
}
