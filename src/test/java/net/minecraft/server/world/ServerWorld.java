package net.minecraft.server.world;

import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.world.World;
import net.minecraft.world.tick.TickManager;
import org.bacon.ruthenium.Ruthenium;
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
    private final RegionizedWorldData worldData;
    private final TickManager tickManager;
    private ThreadedRegionizer<RegionTickData, RegionTickData.RegionSectionData> regionizer;
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
        this.worldData = new StubRegionizedWorldData(this);
        this.tickManager = new TickManager();
    }

    public ServerWorld(final RegistryKey<World> registryKey) {
        this(registryKey, null, 0L);
    }

    @Override
    public ThreadedRegionizer<RegionTickData, RegionTickData.RegionSectionData> ruthenium$getRegionizer() {
        if (this.regionizer == null) {
            this.regionizer = Ruthenium.createRegionizer(this);
        }
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

    public TickManager getTickManager() {
        return this.tickManager;
    }

    private static final class StubRegionizedWorldData extends RegionizedWorldData {

        StubRegionizedWorldData(final ServerWorld world) {
            super(world);
        }

        @Override
        public void tickGlobalServices() {
            // no-op for tests; scheduler behavior under test does not require world services
        }

        @Override
        public void populateChunkState(final BooleanSupplier shouldKeepTicking) {
            // no-op for tests; prevents access to chunk manager infrastructure during scheduler tests
        }
    }
}
