package org.bacon.ruthenium.world.raid;

import net.minecraft.server.world.ServerWorld;

/**
 * Contract implemented by Ruthenium's raid mixins to allow region ownership checks on raid logic
 * without violating the Mixin package access rules.
 */
public interface RaidThreadSafe {

    /**
     * Verifies that the raid is owned by the thread currently executing the supplied world.
     *
     * @param world world to validate against
     * @return {@code true} when the raid should run on the provided world's region thread
     */
    boolean ruthenium$ownsRaid(ServerWorld world);
}
