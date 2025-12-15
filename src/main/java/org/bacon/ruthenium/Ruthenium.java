package org.bacon.ruthenium;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ObserverBlock;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bacon.ruthenium.command.RegionCommand;
import org.bacon.ruthenium.command.RutheniumDebugCommand;
import org.bacon.ruthenium.config.RutheniumConfig;
import org.bacon.ruthenium.config.RutheniumConfigManager;
import org.bacon.ruthenium.debug.RegionDebug;
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

    private static volatile RutheniumConfig CONFIG = RutheniumConfig.defaults().validated();

    @Override
    public void onInitialize() {
        CONFIG = reloadConfig();
        final RegionizerConfig regionizerDefaults = CONFIG.toRegionizerConfig();
        LOGGER.info("Loaded Ruthenium config ({})", CONFIG.describe());
        LOGGER.info("Regionizer defaults (section shift={}, merge radius={})",
            regionizerDefaults.getSectionChunkShift(), regionizerDefaults.getMergeRadius());

        // Register commands
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            RegionCommand.register(dispatcher);
            RutheniumDebugCommand.register(dispatcher);
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
            scheduleObserverResetTicks(world, chunk);
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
        final RegionizerConfig regionizerDefaults = CONFIG.toRegionizerConfig();
        return new ThreadedRegionizer<>(regionizerDefaults, world, createDefaultRegionCallbacks());
    }

    /**
     * Exposes the module logger for mixins and helpers.
     *
     * @return the logger
     */
    public static Logger getLogger() {
        return LOGGER;
    }

    public static RutheniumConfig getConfig() {
        return CONFIG;
    }

    public static RutheniumConfig reloadConfig() {
        final RutheniumConfig config = RutheniumConfigManager.reloadAndApply();
        CONFIG = config;
        TickRegionScheduler.applyConfigIfStarted(config);
        return config;
    }

    /**
     * Returns whether region lifecycle debug logging is enabled.
     */
    public static boolean isRegionDebugLoggingEnabled() {
        return RegionDebug.anyEnabled();
    }

    /**
     * Enables or disables region lifecycle debug logging.
     */
    public static void setRegionDebugLoggingEnabled(final boolean enabled) {
        RegionDebug.setAllQuietly(enabled);
        LOGGER.info("Region debug logging {}", enabled ? "enabled" : "disabled");
    }

    private static ThreadedRegionizer<RegionTickData, RegionTickData.RegionSectionData> requireRegionizer(final ServerWorld world) {
        if (!(world instanceof RegionizedServerWorld regionized)) {
            throw new IllegalStateException("Server world " + world + " is missing Ruthenium region state mixin");
        }
        return regionized.ruthenium$getRegionizer();
    }

    private static void scheduleObserverResetTicks(final ServerWorld world, final WorldChunk chunk) {
        final ChunkPos chunkPos = chunk.getPos();
        final int startX = chunkPos.getStartX();
        final int startZ = chunkPos.getStartZ();
        final int endX = chunkPos.getEndX();
        final int endZ = chunkPos.getEndZ();
        final int bottomY = world.getBottomY();
        final int topY = world.getTopYInclusive();
        final BlockPos.Mutable cursor = new BlockPos.Mutable();

        for (int y = bottomY; y <= topY; y++) {
            for (int z = startZ; z <= endZ; z++) {
                for (int x = startX; x <= endX; x++) {
                    cursor.set(x, y, z);
                    final BlockState state = chunk.getBlockState(cursor);
                    if (!state.isOf(Blocks.OBSERVER)) {
                        continue;
                    }
                    if (!state.contains(ObserverBlock.POWERED) || !state.get(ObserverBlock.POWERED)) {
                        continue;
                    }
                    world.scheduleBlockTick(cursor, state.getBlock(), 2);
                }
            }
        }
    }

    private static TickRegions createDefaultRegionCallbacks() {
        return new TickRegions(TickRegionScheduler.getInstance());
    }
}
