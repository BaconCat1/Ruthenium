package org.bacon.ruthenium;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bacon.ruthenium.command.RegionCommand;
import org.bacon.ruthenium.region.RegionTickData;
import org.bacon.ruthenium.region.RegionizerConfig;
import org.bacon.ruthenium.region.ThreadedRegionizer;
import org.bacon.ruthenium.region.ThreadedRegionizer.ThreadedRegion;
import org.bacon.ruthenium.region.TickRegions;
import org.bacon.ruthenium.world.RegionizedServerWorld;
import org.bacon.ruthenium.world.TickRegionScheduler;

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

    private static volatile boolean REGION_DEBUG_LOGGING = false;

    @Override
    public void onInitialize() {
        LOGGER.info("Initializing Ruthenium regionizer defaults (section shift={}, merge radius={})",
            DEFAULT_CONFIG.getSectionChunkShift(), DEFAULT_CONFIG.getMergeRadius());

        // Register commands
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            RegionCommand.register(dispatcher);
        });

        ServerChunkEvents.CHUNK_LOAD.register((world, chunk) -> {
            final ThreadedRegionizer<RegionTickData, RegionTickData.RegionSectionData> regionizer = requireRegionizer(world);
            final ChunkPos pos = chunk.getPos();
            regionizer.addChunk(pos.x, pos.z);
            final ThreadedRegion<RegionTickData, RegionTickData.RegionSectionData> region = regionizer.getRegionForChunk(pos.x, pos.z);
            if (region != null) {
                region.getData().addChunk(pos.x, pos.z);
                LOGGER.debug("Registered chunk {} for region {} in world {}", pos, region.id, world.getRegistryKey().getValue());
            }
        });

        ServerChunkEvents.CHUNK_UNLOAD.register((world, chunk) -> {
            final ThreadedRegionizer<RegionTickData, RegionTickData.RegionSectionData> regionizer = requireRegionizer(world);
            final ChunkPos pos = chunk.getPos();
            final ThreadedRegion<RegionTickData, RegionTickData.RegionSectionData> region = regionizer.getRegionForChunk(pos.x, pos.z);
            if (region != null) {
                region.getData().removeChunk(pos.x, pos.z);
            }
            regionizer.removeChunk(pos.x, pos.z);
            LOGGER.debug("Unregistered chunk {} from world {}", pos, world.getRegistryKey().getValue());
        });
    }

    /**
     * Creates a new regionizer configured with Ruthenium defaults.
     *
     * @return a new regionizer instance
     */
    public static ThreadedRegionizer<RegionTickData, RegionTickData.RegionSectionData> createRegionizer(final ServerWorld world) {
        return new ThreadedRegionizer<>(DEFAULT_CONFIG, world, createDefaultRegionCallbacks());
    }

    /**
     * Exposes the module logger for mixins and helpers.
     *
     * @return the logger
     */
    public static Logger getLogger() {
        return LOGGER;
    }

    /**
     * Returns whether region lifecycle debug logging is enabled.
     */
    public static boolean isRegionDebugLoggingEnabled() {
        return REGION_DEBUG_LOGGING;
    }

    /**
     * Enables or disables region lifecycle debug logging.
     */
    public static void setRegionDebugLoggingEnabled(final boolean enabled) {
        REGION_DEBUG_LOGGING = enabled;
        LOGGER.info("Region debug logging {}", enabled ? "enabled" : "disabled");
    }

    private static ThreadedRegionizer<RegionTickData, RegionTickData.RegionSectionData> requireRegionizer(final ServerWorld world) {
        if (!(world instanceof RegionizedServerWorld regionized)) {
            throw new IllegalStateException("Server world " + world + " is missing Ruthenium region state mixin");
        }
        return regionized.ruthenium$getRegionizer();
    }

    private static TickRegions createDefaultRegionCallbacks() {
        return new TickRegions(TickRegionScheduler.getInstance());
    }
}
