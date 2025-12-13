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

    /**
     * Logging categories exposed to command toggles.
     */
    public enum LogCategory {
        /** Lifecycle events such as region creation and destruction. */
        LIFECYCLE,
        /** Player movement events between regions. */
        MOVEMENT,
        /** Scheduler activity and task dispatch information. */
        SCHEDULER
    }

    private static final EnumSet<LogCategory> ENABLED = EnumSet.noneOf(LogCategory.class);

    // Tracks last-known region id for each player to report crossings when MOVEMENT is enabled
    private static final Map<UUID, Long> LAST_PLAYER_REGION = new ConcurrentHashMap<>();

    private RegionDebug() {}

    /**
     * Determines whether logging is currently enabled for the supplied category.
     *
     * @param category category to check
     * @return {@code true} when logging is enabled
     */
    public static boolean isEnabled(final LogCategory category) {
        synchronized (ENABLED) {
            return ENABLED.contains(category);
        }
    }

    /**
     * Enables the requested logging category.
     *
     * @param category category to enable
     */
    public static void enable(final LogCategory category) {
        synchronized (ENABLED) {
            ENABLED.add(category);
        }
        Ruthenium.getLogger().info("Region debug category {} enabled", category);
    }

    /**
     * Disables the requested logging category and clears associated caches when required.
     *
     * @param category category to disable
     */
    public static void disable(final LogCategory category) {
        synchronized (ENABLED) {
            ENABLED.remove(category);
        }
        if (category == LogCategory.MOVEMENT) {
            LAST_PLAYER_REGION.clear();
        }
        Ruthenium.getLogger().info("Region debug category {} disabled", category);
    }

    /**
     * Toggles the supplied category and reports the new state.
     *
     * @param category category to toggle
     * @return {@code true} when the category becomes enabled
     */
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
        if (!enabled && category == LogCategory.MOVEMENT) {
            LAST_PLAYER_REGION.clear();
        }
        Ruthenium.getLogger().info("Region debug category {} toggled {}", category, enabled ? "on" : "off");
        return enabled;
    }

    /**
     * Enables or disables all categories and logs the change.
     *
     * @param on {@code true} to enable every category
     */
    public static void setAll(final boolean on) {
        setAllInternal(on, true);
    }

    /**
     * Enables or disables all categories without emitting a log message.
     *
     * @param on {@code true} to enable every category
     */
    public static void setAllQuietly(final boolean on) {
        setAllInternal(on, false);
    }

    public static void setEnabledCategoriesQuietly(final EnumSet<LogCategory> categories) {
        Objects.requireNonNull(categories, "categories");
        synchronized (ENABLED) {
            ENABLED.clear();
            ENABLED.addAll(categories);
        }
        if (!categories.contains(LogCategory.MOVEMENT)) {
            LAST_PLAYER_REGION.clear();
        }
    }

    private static void setAllInternal(final boolean on, final boolean announce) {
        synchronized (ENABLED) {
            ENABLED.clear();
            if (on) {
                ENABLED.add(LogCategory.LIFECYCLE);
                ENABLED.add(LogCategory.MOVEMENT);
                ENABLED.add(LogCategory.SCHEDULER);
            }
        }
        LAST_PLAYER_REGION.clear();
        if (announce) {
            Ruthenium.getLogger().info("Region debug logging {} for all categories", on ? "enabled" : "disabled");
        }
    }

    /**
     * Summarises the current category enablement state for command feedback.
     *
     * @return textual status line
     */
    public static String statusLine() {
        final EnumSet<LogCategory> snapshot;
        synchronized (ENABLED) {
            snapshot = ENABLED.clone();
        }
        return "Lifecycle=" + (snapshot.contains(LogCategory.LIFECYCLE) ? "on" : "off") +
               ", Movement=" + (snapshot.contains(LogCategory.MOVEMENT) ? "on" : "off") +
               ", Scheduler=" + (snapshot.contains(LogCategory.SCHEDULER) ? "on" : "off");
    }

    /**
     * Returns whether at least one logging category is currently active.
     *
     * @return {@code true} when at least one logging category is enabled
     */
    public static boolean anyEnabled() {
        synchronized (ENABLED) {
            return !ENABLED.isEmpty();
        }
    }

    /**
     * Emits a formatted log line when the specified category is currently enabled.
     *
     * @param category category associated with the message
     * @param message  log message template
     * @param args     template arguments
     */
    public static void log(final LogCategory category, final String message, final Object... args) {
        if (!isEnabled(category)) {
            return;
        }
        final Logger logger = Ruthenium.getLogger();
        logger.info(message, args);
    }

    /**
     * Called once per world tick to report player movement between regions when enabled.
     *
     * @param world world being ticked
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
