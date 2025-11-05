package org.bacon.ruthenium.world.raid;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.village.raid.Raid;

/**
 * Thread-safe extensions for {@link net.minecraft.village.raid.RaidManager} exposed by Ruthenium's
 * mixins. Keeping this interface outside of the mixin package avoids the class loading guards
 * enforced by the Mixin framework when it processes {@code org.bacon.ruthenium.mixin.*} classes.
 */
public interface RaidManagerThreadSafe {

    void ruthenium$globalTick();

    Raid ruthenium$getRaidFor(ServerWorld world, BlockPos pos, int searchDistance);
}
