package net.minecraft.server.world;

import java.util.Collections;
import java.util.List;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.world.World;
import org.bacon.ruthenium.region.RegionTickData;
import org.bacon.ruthenium.region.ThreadedRegionizer;
import org.bacon.ruthenium.world.RegionizedServerWorld;
import org.bacon.ruthenium.world.RegionizedWorldData;

/**
 * Minimal ServerWorld stub for unit testing. Only the pieces referenced by TickRegionScheduler
 * fallback logic are implemented.
 */
public final class ServerWorld implements RegionizedServerWorld {

    private final RegistryKey<World> registryKey;
    private final ThreadedRegionizer<RegionTickData, RegionTickData.RegionSectionData> regionizer;
    private final RegionizedWorldData worldData;
    private long time;

    public ServerWorld(final RegistryKey<World> registryKey,
                       final ThreadedRegionizer<RegionTickData, RegionTickData.RegionSectionData> regionizer) {
        this(registryKey, regionizer, 0L);
    }

    public ServerWorld(final RegistryKey<World> registryKey,
                       final ThreadedRegionizer<RegionTickData, RegionTickData.RegionSectionData> regionizer,
                       final long time) {
        this.registryKey = registryKey;
        this.regionizer = regionizer;
        this.time = time;
        this.worldData = new RegionizedWorldData(this);
    }

    @Override
    public ThreadedRegionizer<RegionTickData, RegionTickData.RegionSectionData> ruthenium$getRegionizer() {
        return this.regionizer;
    }

    @Override
    public RegionizedWorldData ruthenium$getWorldRegionData() {
        return this.worldData;
    }

    public RegistryKey<World> getRegistryKey() {
        return this.registryKey;
    }

    public long getTime() {
        return this.time;
    }

    public void setTime(final long time) {
        this.time = time;
    }

    public List<ServerPlayerEntity> getPlayers() {
        return Collections.emptyList();
    }
}
