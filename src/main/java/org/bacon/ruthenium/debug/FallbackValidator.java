package org.bacon.ruthenium.debug;

import net.minecraft.server.world.ServerWorld;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bacon.ruthenium.world.RegionizedServer;
import org.bacon.ruthenium.world.RegionizedServerWorld;
import org.bacon.ruthenium.world.TickRegionScheduler;

/**
 * Validation utility for detecting when vanilla tick paths execute while
 * regions are active. This mirrors Folia's "no cross-region tick" guarantee
 * by logging whenever global fallback paths are taken unintentionally.
 */
public final class FallbackValidator {

    private static final Logger LOGGER = LogManager.getLogger(FallbackValidator.class);

    /**
     * System property to enable fallback assertions. When true, fallback
     * validation will throw exceptions instead of just logging.
     */
    private static volatile boolean assertMode = Boolean.getBoolean("ruthenium.fallback.assert");

    /**
     * Whether to log fallback events at all. Disable to reduce log spam.
     */
    private static volatile boolean logFallbacks = Boolean.getBoolean("ruthenium.fallback.log");

    private FallbackValidator() {}

    public static boolean isAssertMode() {
        return assertMode;
    }

    public static void setAssertMode(final boolean enabled) {
        assertMode = enabled;
    }

    public static boolean isLogFallbacks() {
        return logFallbacks;
    }

    public static void setLogFallbacks(final boolean enabled) {
        logFallbacks = enabled;
    }

    /**
     * Asserts that the current thread is a region thread. If not, logs a
     * warning or throws depending on configuration.
     *
     * @param context description of what operation expected to be on a region thread
     */
    public static void assertOnRegionThread(final String context) {
        if (RegionizedServer.isOnRegionThread()) {
            return;
        }
        final String message = String.format(
            "Expected to be on region thread for '%s' but was on thread '%s'",
            context, Thread.currentThread().getName());
        if (assertMode) {
            throw new IllegalStateException(message);
        }
        if (logFallbacks) {
            LOGGER.warn("[FALLBACK] {}", message);
        }
    }

    /**
     * Logs when a subsystem falls back to global/main-thread processing
     * while regions are active for the given world.
     *
     * @param world     the world being processed
     * @param subsystem identifier for the subsystem (e.g., "block_entities", "block_events")
     * @param details   additional context for the fallback
     */
    public static void logGlobalFallback(final ServerWorld world,
                                         final String subsystem,
                                         final String details) {
        if (!logFallbacks) {
            return;
        }
        final TickRegionScheduler scheduler = TickRegionScheduler.getInstance();
        if (!scheduler.hasActiveRegions(world)) {
            return; // No active regions - fallback is expected
        }
        final String worldName = world.getRegistryKey().getValue().toString();
        final String message = String.format(
            "[FALLBACK] Global tick path taken for '%s' in world '%s' while regions are active: %s",
            subsystem, worldName, details);
        if (assertMode) {
            throw new IllegalStateException(message);
        }
        LOGGER.warn(message);
    }

    /**
     * Validates that block entity ticking is happening on the correct thread.
     * Should be called at the start of tickBlockEntities().
     *
     * @param world the world being ticked
     */
    public static void validateBlockEntityTicking(final ServerWorld world) {
        if (world instanceof RegionizedServerWorld regionized) {
            if (!RegionizedServer.isOnRegionThread() &&
                TickRegionScheduler.getInstance().hasActiveRegions(world)) {
                logGlobalFallback(world, "block_entities",
                    "tickBlockEntities() called on main thread");
            }
        }
    }

    /**
     * Validates that block events are being processed on the correct thread.
     * Should be called at the start of processSyncedBlockEvents().
     *
     * @param world the world being processed
     */
    public static void validateBlockEventProcessing(final ServerWorld world) {
        if (world instanceof RegionizedServerWorld regionized) {
            if (!RegionizedServer.isOnRegionThread() &&
                TickRegionScheduler.getInstance().hasActiveRegions(world)) {
                logGlobalFallback(world, "block_events",
                    "processSyncedBlockEvents() called on main thread");
            }
        }
    }

    /**
     * Validates that scheduled tick processing is happening on the correct thread.
     * Should be called at the start of WorldTickScheduler.tick().
     *
     * @param world the world being processed
     */
    public static void validateScheduledTickProcessing(final ServerWorld world) {
        if (world instanceof RegionizedServerWorld regionized) {
            if (!RegionizedServer.isOnRegionThread() &&
                TickRegionScheduler.getInstance().hasActiveRegions(world)) {
                logGlobalFallback(world, "scheduled_ticks",
                    "WorldTickScheduler.tick() called on main thread");
            }
        }
    }
}
