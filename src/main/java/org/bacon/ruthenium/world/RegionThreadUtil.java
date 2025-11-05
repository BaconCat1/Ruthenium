package org.bacon.ruthenium.world;

import java.util.Objects;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import org.bacon.ruthenium.region.RegionTickData;
import org.bacon.ruthenium.region.ThreadedRegionizer;

/**
 * Utility helpers for determining whether the current thread owns specific
 * world positions when executing within the Ruthenium region scheduler.
 */
public final class RegionThreadUtil {

    private RegionThreadUtil() {
    }

    public static boolean isRegionThread() {
        return TickRegionScheduler.getCurrentRegion() != null;
    }

    public static boolean isRegionThreadFor(final ServerWorld world) {
        return world != null && TickRegionScheduler.getCurrentWorld() == world;
    }

    public static boolean ownsChunk(final ServerWorld world, final int chunkX, final int chunkZ) {
        return ownsChunk(world, chunkX, chunkZ, 0);
    }

    public static boolean ownsChunk(final ServerWorld world, final int chunkX, final int chunkZ, final int radius) {
        if (world == null) {
            return false;
        }
        final ThreadedRegionizer.ThreadedRegion<RegionTickData, RegionTickData.RegionSectionData> region = TickRegionScheduler.getCurrentRegion();
        if (region == null || TickRegionScheduler.getCurrentWorld() != world) {
            return false;
        }

        final RegionTickData data = region.getData();
        if (radius <= 0) {
            return data.containsChunk(chunkX, chunkZ);
        }

        for (int dx = -radius; dx <= radius; ++dx) {
            for (int dz = -radius; dz <= radius; ++dz) {
                if (data.containsChunk(chunkX + dx, chunkZ + dz)) {
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean ownsPosition(final ServerWorld world, final BlockPos pos) {
        return ownsPosition(world, pos, 0);
    }

    public static boolean ownsPosition(final ServerWorld world, final BlockPos pos, final int radius) {
        Objects.requireNonNull(pos, "pos");
    final int chunkX = pos.getX() >> 4;
    final int chunkZ = pos.getZ() >> 4;
    return ownsChunk(world, chunkX, chunkZ, radius);
    }

    public static boolean ownsPlayer(final ServerPlayerEntity player, final int radius) {
        if (player == null) {
            return false;
        }
    final ServerWorld world = TickRegionScheduler.getCurrentWorld();
    if (world == null) return false;
        final ChunkPos pos = player.getChunkPos();
        return ownsChunk(world, pos.x, pos.z, radius);
    }

    public static long getRedstoneTime(final World world) {
        if (world instanceof ServerWorld serverWorld && serverWorld instanceof RegionizedServerWorld regionized) {
            return regionized.ruthenium$getWorldRegionData().getRedstoneGameTime();
        }
        return world.getTime();
    }

    public static void scheduleOnChunk(final ServerWorld world, final int chunkX, final int chunkZ, final Runnable task) {
        RegionTaskDispatcher.runOnChunk(world, chunkX, chunkZ, task);
    }

    public static void scheduleOnPlayer(final ServerPlayerEntity player, final Runnable task) {
        Objects.requireNonNull(player, "player");
        final ChunkPos pos = player.getChunkPos();
        final ServerWorld world = TickRegionScheduler.getCurrentWorld();
        if (world != null) {
            scheduleOnChunk(world, pos.x, pos.z, task);
        }
    }
}
