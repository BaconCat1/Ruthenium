package org.bacon.ruthenium;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bacon.ruthenium.region.RegionTickData;
import org.bacon.ruthenium.region.RegionTickDataController;
import org.bacon.ruthenium.region.RegionizerConfig;
import org.bacon.ruthenium.region.ThreadedRegion;
import org.bacon.ruthenium.region.ThreadedRegionizer;
import org.bacon.ruthenium.world.RegionizedServerWorld;

/**
 * Fabric entrypoint that exposes the regionizer implementation for use by the rest of the mod.
 */
public final class Ruthenium implements ModInitializer {

    /** Identifier used by log messages. */
    public static final String MOD_ID = "ruthenium";
    private static final Logger LOGGER = LogManager.getLogger(MOD_ID);

    private static final RegionizerConfig DEFAULT_CONFIG =
        RegionizerConfig.builder()
            .emptySectionCreationRadius(2)
            .mergeRadius(2)
            .recalculationSectionCount(16)
            .maxDeadSectionPercent(0.20D)
            .sectionChunkShift(4)
            .build();

    private static final ThreadedRegionizer<RegionTickData> REGIONIZER =
        new ThreadedRegionizer<>(DEFAULT_CONFIG, new RegionTickDataController());

    @Override
    public void onInitialize() {
        LOGGER.info("Initializing Ruthenium regionizer (section shift={}, merge radius={})",
            REGIONIZER.getConfig().getSectionChunkShift(), REGIONIZER.getConfig().getMergeRadius());

        ServerChunkEvents.CHUNK_LOAD.register((world, chunk) -> {
            final ThreadedRegionizer<RegionTickData> regionizer = requireRegionizer(world);
            final ChunkPos pos = chunk.getPos();
            final ThreadedRegion<RegionTickData> region = regionizer.addChunk(pos.x, pos.z);
            region.getData().addChunk(pos.x, pos.z);
            LOGGER.debug("Registered chunk {} for region {} in world {}", pos, region.getId(), world.getRegistryKey().getValue());
        });

        ServerChunkEvents.CHUNK_UNLOAD.register((world, chunk) -> {
            final ThreadedRegionizer<RegionTickData> regionizer = requireRegionizer(world);
            final ChunkPos pos = chunk.getPos();
            final ThreadedRegion<RegionTickData> region = regionizer.getRegionForChunk(pos.x, pos.z);
            if (region != null) {
                region.getData().removeChunk(pos.x, pos.z);
            }
            regionizer.removeChunk(pos.x, pos.z);
            LOGGER.debug("Unregistered chunk {} from world {}", pos, world.getRegistryKey().getValue());
        });
    }

    /**
     * Provides access to the global regionizer instance.
     *
     * @return the regionizer
     */
    public static ThreadedRegionizer<RegionTickData> getRegionizer() {
        return REGIONIZER;
    }

    /**
     * Creates a new regionizer configured with Ruthenium defaults.
     *
     * @return a new regionizer instance
     */
    public static ThreadedRegionizer<RegionTickData> createRegionizer() {
        return new ThreadedRegionizer<>(DEFAULT_CONFIG, new RegionTickDataController());
    }

    /**
     * Exposes the module logger for mixins and helpers.
     *
     * @return the logger
     */
    public static Logger getLogger() {
        return LOGGER;
    }

    private static ThreadedRegionizer<RegionTickData> requireRegionizer(final ServerWorld world) {
        if (!(world instanceof RegionizedServerWorld regionized)) {
            throw new IllegalStateException("Server world " + world + " is missing Ruthenium region state mixin");
        }
        return regionized.ruthenium$getRegionizer();
    }
}
