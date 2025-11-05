package org.bacon.ruthenium.world.raid;

import net.minecraft.server.world.ServerWorld;

/**
 * Contract implemented by Ruthenium's raid mixins to allow region ownership checks on raid logic
 * without violating the Mixin package access rules.
 */
public interface RaidThreadSafe {

    boolean ruthenium$ownsRaid(ServerWorld world);
}
