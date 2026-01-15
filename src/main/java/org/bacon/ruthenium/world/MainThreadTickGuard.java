package org.bacon.ruthenium.world;

import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Guards and tracks vanilla tick entry points to ensure the main thread
 * ONLY orchestrates the scheduler and never directly ticks chunks, entities,
 * or block entities when regions are active.
 * <p>
 * This class provides:
 * <ul>
 *   <li>Strict enforcement mode that throws exceptions on violations</li>
 *   <li>Fallback metrics tracking for monitoring purposes</li>
 *   <li>System property controls for opt-in fallback behavior</li>
 * </ul>
 * <p>
 * System Properties:
 * <ul>
 *   <li>{@code ruthenium.strict.mode} - When true, throws exceptions on any vanilla tick path (default: false)</li>
 *   <li>{@code ruthenium.allow.fallback} - When true, allows vanilla fallback when regions are inactive (default: true)</li>
 *   <li>{@code ruthenium.log.violations} - When true, logs all violations even in non-strict mode (default: true)</li>
 * </ul>
 */
public final class MainThreadTickGuard {

    private static final Logger LOGGER = LogManager.getLogger(MainThreadTickGuard.class);

    /**
     * When true, any vanilla tick path while regions are active throws an exception.
     */
    private static volatile boolean strictMode = Boolean.getBoolean("ruthenium.strict.mode");

    /**
     * When true, vanilla fallback is allowed when no regions are active.
     * When false, vanilla paths are NEVER allowed (useful for testing full region coverage).
     */
    private static volatile boolean allowFallback = !Boolean.getBoolean("ruthenium.disallow.fallback");

    /**
     * When true, logs all violations to help diagnose issues.
     */
    private static volatile boolean logViolations = !Boolean.getBoolean("ruthenium.quiet.violations");

    // Metrics counters
    private static final AtomicLong vanillaWorldTickCount = new AtomicLong();
    private static final AtomicLong vanillaChunkTickCount = new AtomicLong();
    private static final AtomicLong vanillaEntityTickCount = new AtomicLong();
    private static final AtomicLong vanillaBlockEntityTickCount = new AtomicLong();
    private static final AtomicLong vanillaScheduledTickCount = new AtomicLong();
    private static final AtomicLong vanillaBlockEventCount = new AtomicLong();
    private static final AtomicLong totalViolationCount = new AtomicLong();

    // Thread tracking for main thread identification
    private static volatile Thread mainThread;

    private MainThreadTickGuard() {}

    // ==================== Configuration Methods ====================

    public static boolean isStrictMode() {
        return strictMode;
    }

    public static void setStrictMode(final boolean enabled) {
        strictMode = enabled;
        LOGGER.info("MainThreadTickGuard strict mode set to: {}", enabled);
    }

    public static boolean isAllowFallback() {
        return allowFallback;
    }

    public static void setAllowFallback(final boolean enabled) {
        allowFallback = enabled;
        LOGGER.info("MainThreadTickGuard allow fallback set to: {}", enabled);
    }

    public static boolean isLogViolations() {
        return logViolations;
    }

    public static void setLogViolations(final boolean enabled) {
        logViolations = enabled;
    }

    /**
     * Registers the main server thread for accurate main-thread detection.
     */
    public static void registerMainThread(final Thread thread) {
        mainThread = thread;
    }

    /**
     * Returns true if the current thread is the main server thread.
     */
    public static boolean isMainThread() {
        final Thread main = mainThread;
        return main != null && Thread.currentThread() == main;
    }

    // ==================== Guard Methods ====================

    /**
     * Validates that ServerWorld.tick() is being called appropriately.
     * <p>
     * Main thread responsibilities for world tick:
     * <ul>
     *   <li>Orchestrate the scheduler via TickRegionScheduler.tickWorld()</li>
     *   <li>Tick global services (weather, time, raids at world level)</li>
     *   <li>Handle chunk loading/unloading coordination</li>
     * </ul>
     * Main thread should NEVER directly tick chunks/entities/blocks.
     *
     * @param world the world being ticked
     * @return true if vanilla tick should proceed, false if it should be blocked
     */
    public static boolean guardWorldTick(final ServerWorld world) {
        final TickRegionScheduler scheduler = TickRegionScheduler.getInstance();

        // If regions are active, the scheduler handles all ticking
        if (scheduler.hasActiveRegions(world) && !scheduler.isHalted()) {
            vanillaWorldTickCount.incrementAndGet();
            totalViolationCount.incrementAndGet();
            handleViolation(world, "ServerWorld.tick()",
                "Main thread attempted direct world tick while regions are active. " +
                "Scheduler should orchestrate all region ticks.");
            return false;
        }

        // If graceful degradation is active, vanilla fallback is expected
        if (scheduler.isGracefulDegradationActiveForWorld(world)) {
            if (logViolations) {
                LOGGER.debug("Allowing vanilla world tick for {} due to graceful degradation",
                    world.getRegistryKey().getValue());
            }
            return true;
        }

        // If fallback is explicitly disabled, block even when no regions are active
        if (!allowFallback) {
            vanillaWorldTickCount.incrementAndGet();
            totalViolationCount.incrementAndGet();
            handleViolation(world, "ServerWorld.tick()",
                "Vanilla fallback is disabled via system property");
            return false;
        }

        return true;
    }

    /**
     * Validates that chunk ticking is happening on the correct thread.
     * <p>
     * Chunk ticking MUST happen on region threads when regions are active.
     *
     * @param world  the world containing the chunk
     * @param chunkX the chunk X coordinate
     * @param chunkZ the chunk Z coordinate
     * @return true if chunk tick should proceed, false if it should be blocked
     */
    public static boolean guardChunkTick(final ServerWorld world, final int chunkX, final int chunkZ) {
        // If we're on a region thread, chunk ticking is allowed
        if (RegionizedServer.isOnRegionThread()) {
            return true;
        }

        final TickRegionScheduler scheduler = TickRegionScheduler.getInstance();

        // If regions are active, main thread must not tick chunks
        if (scheduler.hasActiveRegions(world) && !scheduler.isHalted()) {
            vanillaChunkTickCount.incrementAndGet();
            totalViolationCount.incrementAndGet();
            handleViolation(world, "ServerWorld.tickChunk()",
                String.format("Main thread attempted chunk tick at (%d, %d) while regions are active", chunkX, chunkZ));
            return false;
        }

        // Allow vanilla fallback when no regions active (and fallback is enabled)
        if (!allowFallback) {
            vanillaChunkTickCount.incrementAndGet();
            totalViolationCount.incrementAndGet();
            handleViolation(world, "ServerWorld.tickChunk()",
                "Vanilla fallback is disabled via system property");
            return false;
        }

        return true;
    }

    /**
     * Validates that entity ticking is happening on the correct thread.
     * <p>
     * Entity ticking MUST happen on region threads when regions are active.
     *
     * @param world the world containing the entity
     * @return true if entity tick should proceed, false if it should be blocked
     */
    public static boolean guardEntityTick(final ServerWorld world) {
        // If we're on a region thread, entity ticking is allowed
        if (RegionizedServer.isOnRegionThread()) {
            return true;
        }

        final TickRegionScheduler scheduler = TickRegionScheduler.getInstance();

        // If regions are active, main thread must not tick entities
        if (scheduler.hasActiveRegions(world) && !scheduler.isHalted()) {
            vanillaEntityTickCount.incrementAndGet();
            totalViolationCount.incrementAndGet();
            handleViolation(world, "Entity tick",
                "Main thread attempted entity tick while regions are active");
            return false;
        }

        if (!allowFallback) {
            vanillaEntityTickCount.incrementAndGet();
            totalViolationCount.incrementAndGet();
            handleViolation(world, "Entity tick",
                "Vanilla fallback is disabled via system property");
            return false;
        }

        return true;
    }

    /**
     * Validates that block entity ticking is happening on the correct thread.
     * <p>
     * Block entity ticking MUST happen on region threads when regions are active.
     *
     * @param world the world being ticked
     * @return true if block entity tick should proceed, false if it should be blocked
     */
    public static boolean guardBlockEntityTick(final ServerWorld world) {
        // If we're on a region thread, block entity ticking is allowed
        if (RegionizedServer.isOnRegionThread()) {
            return true;
        }

        final TickRegionScheduler scheduler = TickRegionScheduler.getInstance();

        // If regions are active, main thread must not tick block entities
        if (scheduler.hasActiveRegions(world) && !scheduler.isHalted()) {
            vanillaBlockEntityTickCount.incrementAndGet();
            totalViolationCount.incrementAndGet();
            handleViolation(world, "World.tickBlockEntities()",
                "Main thread attempted block entity tick while regions are active");
            return false;
        }

        if (!allowFallback) {
            vanillaBlockEntityTickCount.incrementAndGet();
            totalViolationCount.incrementAndGet();
            handleViolation(world, "World.tickBlockEntities()",
                "Vanilla fallback is disabled via system property");
            return false;
        }

        return true;
    }

    /**
     * Validates that scheduled tick processing is happening on the correct thread.
     *
     * @param world the world being processed
     * @return true if scheduled tick should proceed, false if it should be blocked
     */
    public static boolean guardScheduledTick(final ServerWorld world) {
        // If we're on a region thread, scheduled ticks are allowed
        if (RegionizedServer.isOnRegionThread()) {
            return true;
        }

        final TickRegionScheduler scheduler = TickRegionScheduler.getInstance();

        if (scheduler.hasActiveRegions(world) && !scheduler.isHalted()) {
            vanillaScheduledTickCount.incrementAndGet();
            totalViolationCount.incrementAndGet();
            handleViolation(world, "WorldTickScheduler.tick()",
                "Main thread attempted scheduled tick while regions are active");
            return false;
        }

        if (!allowFallback) {
            vanillaScheduledTickCount.incrementAndGet();
            totalViolationCount.incrementAndGet();
            handleViolation(world, "WorldTickScheduler.tick()",
                "Vanilla fallback is disabled via system property");
            return false;
        }

        return true;
    }

    /**
     * Validates that block event processing is happening on the correct thread.
     *
     * @param world the world being processed
     * @return true if block event processing should proceed, false if it should be blocked
     */
    public static boolean guardBlockEventProcessing(final ServerWorld world) {
        // If we're on a region thread, block events are allowed
        if (RegionizedServer.isOnRegionThread()) {
            return true;
        }

        final TickRegionScheduler scheduler = TickRegionScheduler.getInstance();

        if (scheduler.hasActiveRegions(world) && !scheduler.isHalted()) {
            vanillaBlockEventCount.incrementAndGet();
            totalViolationCount.incrementAndGet();
            handleViolation(world, "ServerWorld.processSyncedBlockEvents()",
                "Main thread attempted block event processing while regions are active");
            return false;
        }

        if (!allowFallback) {
            vanillaBlockEventCount.incrementAndGet();
            totalViolationCount.incrementAndGet();
            handleViolation(world, "ServerWorld.processSyncedBlockEvents()",
                "Vanilla fallback is disabled via system property");
            return false;
        }

        return true;
    }

    // ==================== Assertion Methods ====================

    /**
     * Asserts that the current thread is the main server thread.
     * Used to validate that orchestration-only code runs on main thread.
     *
     * @param action description of the action being performed
     */
    public static void assertMainThread(final String action) {
        if (!isMainThread()) {
            final String message = String.format(
                "Action '%s' must run on main thread but was on thread '%s'",
                action, Thread.currentThread().getName());
            if (strictMode) {
                throw new IllegalStateException(message);
            }
            if (logViolations) {
                LOGGER.warn("[THREAD VIOLATION] {}", message);
            }
        }
    }

    /**
     * Asserts that the current thread is NOT the main server thread.
     * Used to validate that region-only code runs on region threads.
     *
     * @param action description of the action being performed
     */
    public static void assertNotMainThread(final String action) {
        if (isMainThread()) {
            final String message = String.format(
                "Action '%s' must NOT run on main thread",
                action);
            if (strictMode) {
                throw new IllegalStateException(message);
            }
            if (logViolations) {
                LOGGER.warn("[THREAD VIOLATION] {}", message);
            }
        }
    }

    /**
     * Asserts that the main thread is ONLY orchestrating, not directly ticking.
     * Call this at the start of tickWorlds to validate responsibilities.
     */
    public static void assertOrchestratorOnly(final MinecraftServer server) {
        assertMainThread("tickWorlds orchestration");

        // Validate we're not inside any direct tick context
        if (RegionizedServer.isOnRegionThread()) {
            final String message = "Main thread orchestrator should not be marked as region thread";
            if (strictMode) {
                throw new IllegalStateException(message);
            }
            if (logViolations) {
                LOGGER.error("[CRITICAL] {}", message);
            }
        }
    }

    // ==================== Metrics Methods ====================

    /**
     * Returns the total count of vanilla fallback violations.
     */
    public static long getTotalViolationCount() {
        return totalViolationCount.get();
    }

    /**
     * Returns metrics for all tracked vanilla tick paths.
     */
    public static TickMetrics getMetrics() {
        return new TickMetrics(
            vanillaWorldTickCount.get(),
            vanillaChunkTickCount.get(),
            vanillaEntityTickCount.get(),
            vanillaBlockEntityTickCount.get(),
            vanillaScheduledTickCount.get(),
            vanillaBlockEventCount.get(),
            totalViolationCount.get()
        );
    }

    /**
     * Resets all metrics counters. Useful for testing.
     */
    public static void resetMetrics() {
        vanillaWorldTickCount.set(0);
        vanillaChunkTickCount.set(0);
        vanillaEntityTickCount.set(0);
        vanillaBlockEntityTickCount.set(0);
        vanillaScheduledTickCount.set(0);
        vanillaBlockEventCount.set(0);
        totalViolationCount.set(0);
    }

    /**
     * Builds a diagnostic report of current metrics.
     */
    public static String buildMetricsReport() {
        final TickMetrics metrics = getMetrics();
        return String.format(
            "=== Main Thread Tick Guard Metrics ===\n" +
            "Total Violations: %d\n" +
            "World Tick Violations: %d\n" +
            "Chunk Tick Violations: %d\n" +
            "Entity Tick Violations: %d\n" +
            "Block Entity Tick Violations: %d\n" +
            "Scheduled Tick Violations: %d\n" +
            "Block Event Violations: %d\n" +
            "Strict Mode: %s\n" +
            "Allow Fallback: %s\n",
            metrics.totalViolations(),
            metrics.worldTickViolations(),
            metrics.chunkTickViolations(),
            metrics.entityTickViolations(),
            metrics.blockEntityTickViolations(),
            metrics.scheduledTickViolations(),
            metrics.blockEventViolations(),
            strictMode,
            allowFallback
        );
    }

    // ==================== Internal Methods ====================

    private static void handleViolation(final ServerWorld world,
                                        final String method,
                                        final String details) {
        final String worldName = world == null ? "<unknown>" : world.getRegistryKey().getValue().toString();
        final String message = String.format(
            "[TICK GUARD VIOLATION] %s in world '%s': %s",
            method, worldName, details);

        if (strictMode) {
            throw new IllegalStateException(message);
        }

        if (logViolations) {
            LOGGER.warn(message);
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Stack trace for violation:\n{}", formatStackTrace());
            }
        }
    }

    private static String formatStackTrace() {
        final StringBuilder builder = new StringBuilder();
        for (final StackTraceElement element : Thread.currentThread().getStackTrace()) {
            builder.append("    at ").append(element).append("\n");
        }
        return builder.toString();
    }

    /**
     * Record containing metrics for vanilla tick path violations.
     */
    public record TickMetrics(
        long worldTickViolations,
        long chunkTickViolations,
        long entityTickViolations,
        long blockEntityTickViolations,
        long scheduledTickViolations,
        long blockEventViolations,
        long totalViolations
    ) {
        public boolean hasViolations() {
            return totalViolations > 0;
        }
    }
}

