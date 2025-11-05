package org.bacon.ruthenium.debug;

import org.apache.logging.log4j.Logger;
import org.bacon.ruthenium.Ruthenium;
import org.bacon.ruthenium.region.RegionTickData;
import org.bacon.ruthenium.region.ThreadedRegionizer;
import org.bacon.ruthenium.world.RegionizedServerWorld;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;

import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Debug utility providing category-based logging and simple movement tracking
 * for development and diagnostics.
 */
public final class RegionDebug {

    public enum LogCategory {
        LIFECYCLE,
        MOVEMENT,
        SCHEDULER
    }

    private static final EnumSet<LogCategory> ENABLED = EnumSet.noneOf(LogCategory.class);

    // Tracks last-known region id for each player to report crossings when MOVEMENT is enabled
    private static final Map<UUID, Long> LAST_PLAYER_REGION = new ConcurrentHashMap<>();

    private RegionDebug() {}

    public static boolean isEnabled(final LogCategory category) {
        synchronized (ENABLED) {
            return ENABLED.contains(category);
        }
    }

    public static void enable(final LogCategory category) {
        synchronized (ENABLED) {
            ENABLED.add(category);
        }
        Ruthenium.getLogger().info("Region debug category {} enabled", category);
    }

    public static void disable(final LogCategory category) {
        synchronized (ENABLED) {
            ENABLED.remove(category);
        }
        Ruthenium.getLogger().info("Region debug category {} disabled", category);
    }

    public static boolean toggle(final LogCategory category) {
        final boolean enabled;
        synchronized (ENABLED) {
            if (ENABLED.contains(category)) {
                ENABLED.remove(category);
                enabled = false;
            } else {
                ENABLED.add(category);
                enabled = true;
            }
        }
        Ruthenium.getLogger().info("Region debug category {} toggled {}", category, enabled ? "on" : "off");
        return enabled;
    }

    public static void setAll(final boolean on) {
        synchronized (ENABLED) {
            ENABLED.clear();
            if (on) {
                ENABLED.add(LogCategory.LIFECYCLE);
                ENABLED.add(LogCategory.MOVEMENT);
                ENABLED.add(LogCategory.SCHEDULER);
            }
        }
        Ruthenium.getLogger().info("Region debug logging {} for all categories", on ? "enabled" : "disabled");
    }

    public static String statusLine() {
        final EnumSet<LogCategory> snapshot;
        synchronized (ENABLED) {
            snapshot = ENABLED.clone();
        }
        return "Lifecycle=" + (snapshot.contains(LogCategory.LIFECYCLE) ? "on" : "off") +
               ", Movement=" + (snapshot.contains(LogCategory.MOVEMENT) ? "on" : "off") +
               ", Scheduler=" + (snapshot.contains(LogCategory.SCHEDULER) ? "on" : "off");
    }

    public static void log(final LogCategory category, final String message, final Object... args) {
        if (!isEnabled(category)) {
            return;
        }
        final Logger logger = Ruthenium.getLogger();
        logger.info(message, args);
    }

    /**
     * Called once per world tick to report player movement between regions when enabled.
     */
    public static void onWorldTick(final ServerWorld world) {
        if (!isEnabled(LogCategory.MOVEMENT)) {
            return;
        }
        if (!(world instanceof RegionizedServerWorld regionized)) {
            return;
        }
        final ThreadedRegionizer<RegionTickData, RegionTickData.RegionSectionData> regionizer = regionized.ruthenium$getRegionizer();
        for (final ServerPlayerEntity player : world.getPlayers()) {
            final ChunkPos pos = player.getChunkPos();
            final ThreadedRegionizer.ThreadedRegion<RegionTickData, RegionTickData.RegionSectionData> region =
                regionizer.getRegionForChunk(pos.x, pos.z);
            final Long current = region == null ? null : region.id;
            final UUID id = player.getUuid();
            final Long previous = LAST_PLAYER_REGION.get(id);
            if (!Objects.equals(previous, current)) {
                final String fromStr = previous == null ? "none" : previous.toString();
                final String toStr = current == null ? "none" : current.toString();
                log(LogCategory.MOVEMENT, "Player {} moved region: {} -> {} at chunk {},{}", player.getName().getString(), fromStr, toStr, pos.x, pos.z);
                LAST_PLAYER_REGION.put(id, current);
            }
        }
    }
}
