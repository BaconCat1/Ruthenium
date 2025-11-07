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

    /**
     * Performs main-thread raid bookkeeping that the region scheduler still expects to run
     * synchronously on the orchestrator thread.
     */
    void ruthenium$globalTick();

    /**
     * Locates a raid that is close to the supplied position and owned by the provided world.
     *
     * @param world          world performing the lookup
     * @param pos            position near which to search
     * @param searchDistance maximum squared distance to consider when matching
     * @return raid instance or {@code null} if none satisfy the criteria
     */
    Raid ruthenium$getRaidFor(ServerWorld world, BlockPos pos, int searchDistance);
}
