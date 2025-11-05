package org.bacon.ruthenium.mixin.accessor;

import net.minecraft.server.world.ServerWorld;

public interface RaidThreadSafe {

    boolean ruthenium$ownsRaid(ServerWorld world);
}
