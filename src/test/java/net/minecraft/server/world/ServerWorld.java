package net.minecraft.server.world;

import net.minecraft.registry.RegistryKey;
import net.minecraft.world.World;
import org.bacon.ruthenium.region.RegionTickData;
import org.bacon.ruthenium.region.ThreadedRegionizer;
import org.bacon.ruthenium.world.RegionizedServerWorld;

/**
 * Minimal ServerWorld stub for unit testing. Only the pieces referenced by TickRegionScheduler
 * fallback logic are implemented.
 */
public class ServerWorld implements RegionizedServerWorld {

    private final RegistryKey<World> registryKey;
    private final ThreadedRegionizer<RegionTickData, RegionTickData.RegionSectionData> regionizer;

    public ServerWorld(final RegistryKey<World> registryKey,
                       final ThreadedRegionizer<RegionTickData, RegionTickData.RegionSectionData> regionizer) {
        this.registryKey = registryKey;
        this.regionizer = regionizer;
    }

    @Override
    public ThreadedRegionizer<RegionTickData, RegionTickData.RegionSectionData> ruthenium$getRegionizer() {
        return this.regionizer;
    }

    public RegistryKey<World> getRegistryKey() {
        return this.registryKey;
    }
}
