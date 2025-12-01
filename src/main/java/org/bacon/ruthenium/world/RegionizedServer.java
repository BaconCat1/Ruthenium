package org.bacon.ruthenium.world;

import java.util.Objects;
import net.minecraft.server.world.ServerWorld;
import org.bacon.ruthenium.region.RegionTickData;
import org.bacon.ruthenium.region.ThreadedRegionizer;
import org.bacon.ruthenium.region.ThreadedRegionizer.ThreadedRegion;

/**
 * Thread ownership helpers that mirror Folia's RegionizedServer utilities. The
 * methods expose the region scheduler's current context so callers can assert
 * that dangerous world mutations only run on the owning region thread.
 */
public final class RegionizedServer {

    private RegionizedServer() {}

    /**
     * Returns whether the current thread is actively ticking a region.
     */
    public static boolean isOnRegionThread() {
        return TickRegionScheduler.getCurrentRegion() != null;
    }

    /**
     * Ensures the caller is running on a region tick thread.
     *
     * @param action description included in the thrown exception when the check fails
     */
    public static void ensureOnRegionThread(final String action) {
        if (!isOnRegionThread()) {
            throw new IllegalStateException("Action '" + action + "' must execute on a region thread");
        }
    }

    /**
     * Retrieves the region currently being ticked by this thread.
     */
    public static ThreadedRegion<RegionTickData, RegionTickData.RegionSectionData> getCurrentRegion() {
        return TickRegionScheduler.getCurrentRegion();
    }

    /**
     * Retrieves the world currently being ticked by this region thread.
     */
    public static ServerWorld getCurrentWorld() {
        return TickRegionScheduler.getCurrentWorld();
    }

    /**
     * Returns the world data snapshot exposed to the running region thread.
     */
    public static RegionizedWorldData getCurrentWorldData() {
        return TickRegionScheduler.getCurrentWorldData();
    }

    /**
     * Returns the region tick counter for the currently executing region.
     */
    public static long getCurrentRegionTickCount() {
        final TickRegionScheduler.RegionScheduleHandle handle = TickRegionScheduler.getCurrentHandle();
        return handle == null ? -1L : handle.getData().getCurrentTick();
    }

    /**
     * Determines whether the supplied chunk is owned by the currently executing region.
     *
     * @param world  regionized world
     * @param chunkX chunk X coordinate
     * @param chunkZ chunk Z coordinate
     * @return {@code true} when the chunk belongs to the active region
     */
    public static boolean isOwnedByCurrentRegion(final RegionizedServerWorld world,
                                                 final int chunkX,
                                                 final int chunkZ) {
        Objects.requireNonNull(world, "world");
        final ThreadedRegionizer<RegionTickData, RegionTickData.RegionSectionData> regionizer =
            world.ruthenium$getRegionizer();
        final ThreadedRegion<RegionTickData, RegionTickData.RegionSectionData> region =
            regionizer.getRegionForChunk(chunkX, chunkZ);
        return region != null && region == getCurrentRegion();
    }

    /**
     * Ensures the supplied chunk operation runs on the owning region thread.
     *
     * @param world  regionized world owning the chunk
     * @param chunkX chunk X coordinate
     * @param chunkZ chunk Z coordinate
     * @param action textual description used when throwing
     */
    public static void ensureOwnedByCurrentRegion(final RegionizedServerWorld world,
                                                  final int chunkX,
                                                  final int chunkZ,
                                                  final String action) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(action, "action");
        if (!isOwnedByCurrentRegion(world, chunkX, chunkZ)) {
            final ServerWorld currentWorld = getCurrentWorld();
            final String currentWorldId = currentWorld == null ? "<none>" : currentWorld.getRegistryKey().getValue().toString();
            throw new IllegalStateException("Action '" + action + "' for chunk (" + chunkX + ", " + chunkZ
                + ") must execute on its owning region thread (currentWorld=" + currentWorldId + ")");
        }
    }
}
