package org.bacon.ruthenium.world;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Handles scheduler failure detection, tracking, and recovery.
 * <p>
 * This class tracks:
 * <ul>
 *   <li>Per-region consecutive failure counts</li>
 *   <li>Global failure rate over a sliding window</li>
 *   <li>Stalled region detection</li>
 * </ul>
 * <p>
 * When failures exceed thresholds, this handler can:
 * <ul>
 *   <li>Attempt auto-recovery by rescheduling failed regions</li>
 *   <li>Trigger graceful degradation to main-thread ticking</li>
 *   <li>Request full shutdown if recovery fails</li>
 * </ul>
 */
public final class SchedulerFailureHandler {

    private static final Logger LOGGER = LogManager.getLogger(SchedulerFailureHandler.class);

    /**
     * Maximum consecutive failures for a single region before marking it non-recoverable.
     */
    private static final int MAX_CONSECUTIVE_REGION_FAILURES = 3;

    /**
     * Maximum total failures across all regions within the sliding window before
     * triggering graceful degradation.
     */
    private static final int MAX_GLOBAL_FAILURES_THRESHOLD = 10;

    /**
     * Sliding window duration for tracking global failures (in milliseconds).
     */
    private static final long FAILURE_WINDOW_MILLIS = TimeUnit.MINUTES.toMillis(1);

    /**
     * Time threshold for considering a region stalled (in milliseconds).
     */
    private static final long STALL_THRESHOLD_MILLIS = TimeUnit.SECONDS.toMillis(30);

    /**
     * Minimum time between auto-recovery attempts for the same region (in milliseconds).
     */
    private static final long RECOVERY_COOLDOWN_MILLIS = TimeUnit.SECONDS.toMillis(5);

    private final TickRegionScheduler scheduler;
    private final AtomicBoolean gracefulDegradationActive = new AtomicBoolean(false);
    private final AtomicBoolean shutdownRequested = new AtomicBoolean(false);

    /**
     * Tracks per-region failure state keyed by world -> region ID.
     */
    private final ConcurrentHashMap<RegistryKey<World>, ConcurrentHashMap<Long, RegionFailureState>> regionFailures =
        new ConcurrentHashMap<>();

    /**
     * Global failure tracking for sliding window calculations.
     */
    private final AtomicLong globalFailureCount = new AtomicLong();
    private final AtomicLong windowStartMillis = new AtomicLong(System.currentTimeMillis());

    /**
     * Tracks when graceful degradation was activated for each world.
     */
    private final ConcurrentHashMap<RegistryKey<World>, Long> gracefulDegradationStartTimes =
        new ConcurrentHashMap<>();

    public SchedulerFailureHandler(final TickRegionScheduler scheduler) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    /**
     * Returns whether graceful degradation mode is currently active.
     * When active, vanilla main-thread ticking should be used as a fallback.
     */
    public boolean isGracefulDegradationActive() {
        return this.gracefulDegradationActive.get();
    }

    /**
     * Returns whether graceful degradation is active for a specific world.
     */
    public boolean isGracefulDegradationActiveForWorld(final ServerWorld world) {
        if (!this.gracefulDegradationActive.get()) {
            return false;
        }
        final RegistryKey<World> worldKey = world.getRegistryKey();
        return this.gracefulDegradationStartTimes.containsKey(worldKey);
    }

    /**
     * Called when a region tick fails with an exception.
     *
     * @param world     the world containing the failed region
     * @param regionId  the ID of the failed region
     * @param throwable the exception that caused the failure
     * @return the recommended recovery action
     */
    public RecoveryAction handleRegionFailure(final ServerWorld world,
                                              final long regionId,
                                              final Throwable throwable) {
        LOGGER.error("Region {} in world {} failed with exception",
            regionId, world.getRegistryKey().getValue(), throwable);

        final RegistryKey<World> worldKey = world.getRegistryKey();
        final RegionFailureState state = getOrCreateFailureState(worldKey, regionId);
        state.recordFailure();

        // Update global failure tracking
        updateGlobalFailureCount();

        // Check if this region has exceeded its failure threshold
        if (state.getConsecutiveFailures() >= MAX_CONSECUTIVE_REGION_FAILURES) {
            LOGGER.error("Region {} has exceeded maximum consecutive failures ({}) - marking as non-recoverable",
                regionId, MAX_CONSECUTIVE_REGION_FAILURES);
            state.markNonRecoverable();
            return RecoveryAction.MARK_NON_SCHEDULABLE;
        }

        // Check if global failures have exceeded threshold
        if (shouldTriggerGracefulDegradation()) {
            return RecoveryAction.GRACEFUL_DEGRADATION;
        }

        // Attempt auto-recovery if cooldown has elapsed
        if (state.canAttemptRecovery()) {
            LOGGER.info("Attempting auto-recovery for region {} (consecutiveFailures={})",
                regionId, state.getConsecutiveFailures());
            state.recordRecoveryAttempt();
            return RecoveryAction.ATTEMPT_RESCHEDULE;
        }

        return RecoveryAction.WAIT_FOR_COOLDOWN;
    }

    /**
     * Called when a region appears to be stalled (not ticking for an extended period).
     *
     * @param world    the world containing the stalled region
     * @param regionId the ID of the stalled region
     * @param stallDurationMillis how long the region has been stalled
     * @return the recommended recovery action
     */
    public RecoveryAction handleStalledRegion(final ServerWorld world,
                                              final long regionId,
                                              final long stallDurationMillis) {
        LOGGER.warn("Region {} in world {} has been stalled for {}ms",
            regionId, world.getRegistryKey().getValue(), stallDurationMillis);

        final RegistryKey<World> worldKey = world.getRegistryKey();
        final RegionFailureState state = getOrCreateFailureState(worldKey, regionId);
        state.recordStall();

        // Update tick monitor
        RegionTickMonitor.getInstance().recordStall(world, regionId);

        // If stall is severe (3x the threshold), trigger immediate recovery
        if (stallDurationMillis > STALL_THRESHOLD_MILLIS * 3) {
            LOGGER.error("Region {} severely stalled ({}ms) - attempting forced recovery",
                regionId, stallDurationMillis);
            return RecoveryAction.FORCE_RESCHEDULE;
        }

        // Check if this region has too many consecutive stalls
        if (state.getConsecutiveStalls() >= MAX_CONSECUTIVE_REGION_FAILURES) {
            LOGGER.error("Region {} has exceeded maximum consecutive stalls ({}) - marking as non-recoverable",
                regionId, MAX_CONSECUTIVE_REGION_FAILURES);
            state.markNonRecoverable();
            return RecoveryAction.MARK_NON_SCHEDULABLE;
        }

        // Attempt gentle recovery
        if (state.canAttemptRecovery()) {
            state.recordRecoveryAttempt();
            return RecoveryAction.ATTEMPT_RESCHEDULE;
        }

        return RecoveryAction.WAIT_FOR_COOLDOWN;
    }

    /**
     * Called when a region tick succeeds. Resets failure tracking for that region.
     *
     * @param world    the world containing the region
     * @param regionId the ID of the region
     */
    public void handleRegionSuccess(final ServerWorld world, final long regionId) {
        final RegistryKey<World> worldKey = world.getRegistryKey();
        final ConcurrentHashMap<Long, RegionFailureState> worldStates = this.regionFailures.get(worldKey);
        if (worldStates == null) {
            return;
        }
        final RegionFailureState state = worldStates.get(regionId);
        if (state != null) {
            state.recordSuccess();
        }
    }

    /**
     * Activates graceful degradation mode for a specific world.
     * In this mode, vanilla main-thread ticking will be used as a fallback.
     *
     * @param world  the world to degrade
     * @param reason description of why degradation was triggered
     */
    public void activateGracefulDegradation(final ServerWorld world, final String reason) {
        final RegistryKey<World> worldKey = world.getRegistryKey();

        if (this.gracefulDegradationStartTimes.putIfAbsent(worldKey, System.currentTimeMillis()) == null) {
            this.gracefulDegradationActive.set(true);
            LOGGER.error("========================================");
            LOGGER.error("GRACEFUL DEGRADATION ACTIVATED");
            LOGGER.error("World: {}", worldKey.getValue());
            LOGGER.error("Reason: {}", reason);
            LOGGER.error("The scheduler will fall back to main-thread ticking");
            LOGGER.error("for this world until the issue is resolved.");
            LOGGER.error("========================================");
        }
    }

    /**
     * Attempts to deactivate graceful degradation for a world if conditions have improved.
     *
     * @param world the world to check
     * @return true if graceful degradation was deactivated
     */
    public boolean tryDeactivateGracefulDegradation(final ServerWorld world) {
        final RegistryKey<World> worldKey = world.getRegistryKey();
        final Long startTime = this.gracefulDegradationStartTimes.get(worldKey);
        if (startTime == null) {
            return false;
        }

        // Require at least 30 seconds of graceful degradation before attempting recovery
        final long degradationDuration = System.currentTimeMillis() - startTime;
        if (degradationDuration < TimeUnit.SECONDS.toMillis(30)) {
            return false;
        }

        // Check if failure rate has decreased
        final long windowFailures = this.globalFailureCount.get();
        if (windowFailures > MAX_GLOBAL_FAILURES_THRESHOLD / 2) {
            return false;
        }

        // Deactivate graceful degradation for this world
        if (this.gracefulDegradationStartTimes.remove(worldKey) != null) {
            LOGGER.info("========================================");
            LOGGER.info("GRACEFUL DEGRADATION DEACTIVATED");
            LOGGER.info("World: {}", worldKey.getValue());
            LOGGER.info("Resuming normal region scheduling.");
            LOGGER.info("========================================");

            // Check if all worlds are now out of graceful degradation
            if (this.gracefulDegradationStartTimes.isEmpty()) {
                this.gracefulDegradationActive.set(false);
            }
            return true;
        }
        return false;
    }

    /**
     * Requests a full server shutdown due to unrecoverable scheduler failure.
     *
     * @param server the server to shut down
     * @param reason description of why shutdown was requested
     */
    public void requestShutdown(final MinecraftServer server, final String reason) {
        if (!this.shutdownRequested.compareAndSet(false, true)) {
            return;
        }
        LOGGER.error("========================================");
        LOGGER.error("SCHEDULER SHUTDOWN REQUESTED");
        LOGGER.error("Reason: {}", reason);
        LOGGER.error("========================================");
        RegionShutdownThread.requestShutdown(server, this.scheduler);
    }

    /**
     * Clears all failure state for a world. Called when a world is unloaded.
     */
    public void clearWorldState(final RegistryKey<World> worldKey) {
        this.regionFailures.remove(worldKey);
        this.gracefulDegradationStartTimes.remove(worldKey);
        if (this.gracefulDegradationStartTimes.isEmpty()) {
            this.gracefulDegradationActive.set(false);
        }
    }

    /**
     * Clears failure state for a specific region. Called when a region is destroyed.
     */
    public void clearRegionState(final RegistryKey<World> worldKey, final long regionId) {
        final ConcurrentHashMap<Long, RegionFailureState> worldStates = this.regionFailures.get(worldKey);
        if (worldStates != null) {
            worldStates.remove(regionId);
        }
    }

    /**
     * Builds a diagnostic report of current failure states.
     */
    public String buildDiagnosticReport() {
        final StringBuilder report = new StringBuilder();
        report.append("=== Scheduler Failure Handler Diagnostics ===\n");
        report.append("Graceful Degradation Active: ").append(this.gracefulDegradationActive.get()).append("\n");
        report.append("Shutdown Requested: ").append(this.shutdownRequested.get()).append("\n");
        report.append("Global Failure Count (window): ").append(this.globalFailureCount.get()).append("\n");

        if (!this.gracefulDegradationStartTimes.isEmpty()) {
            report.append("Worlds in Graceful Degradation:\n");
            for (Map.Entry<RegistryKey<World>, Long> entry : this.gracefulDegradationStartTimes.entrySet()) {
                final long duration = System.currentTimeMillis() - entry.getValue();
                report.append("  - ").append(entry.getKey().getValue())
                    .append(" (").append(duration).append("ms)\n");
            }
        }

        if (!this.regionFailures.isEmpty()) {
            report.append("Per-Region Failure States:\n");
            for (Map.Entry<RegistryKey<World>, ConcurrentHashMap<Long, RegionFailureState>> worldEntry : this.regionFailures.entrySet()) {
                report.append("  World: ").append(worldEntry.getKey().getValue()).append("\n");
                for (Map.Entry<Long, RegionFailureState> regionEntry : worldEntry.getValue().entrySet()) {
                    final RegionFailureState state = regionEntry.getValue();
                    report.append("    Region ").append(regionEntry.getKey()).append(": ")
                        .append("failures=").append(state.getConsecutiveFailures())
                        .append(", stalls=").append(state.getConsecutiveStalls())
                        .append(", recoverable=").append(!state.isNonRecoverable())
                        .append("\n");
                }
            }
        }

        return report.toString();
    }

    private RegionFailureState getOrCreateFailureState(final RegistryKey<World> worldKey, final long regionId) {
        return this.regionFailures
            .computeIfAbsent(worldKey, ignored -> new ConcurrentHashMap<>())
            .computeIfAbsent(regionId, RegionFailureState::new);
    }

    private void updateGlobalFailureCount() {
        final long now = System.currentTimeMillis();
        final long windowStart = this.windowStartMillis.get();

        // If window has expired, reset the counter
        if (now - windowStart > FAILURE_WINDOW_MILLIS) {
            if (this.windowStartMillis.compareAndSet(windowStart, now)) {
                this.globalFailureCount.set(1);
                return;
            }
        }

        this.globalFailureCount.incrementAndGet();
    }

    private boolean shouldTriggerGracefulDegradation() {
        return this.globalFailureCount.get() >= MAX_GLOBAL_FAILURES_THRESHOLD;
    }

    /**
     * Actions that can be taken in response to failures.
     */
    public enum RecoveryAction {
        /**
         * Attempt to reschedule the region for another tick.
         */
        ATTEMPT_RESCHEDULE,

        /**
         * Force immediate rescheduling, bypassing cooldown.
         */
        FORCE_RESCHEDULE,

        /**
         * Wait for cooldown before attempting recovery.
         */
        WAIT_FOR_COOLDOWN,

        /**
         * Mark the region as non-schedulable (give up on it).
         */
        MARK_NON_SCHEDULABLE,

        /**
         * Activate graceful degradation mode.
         */
        GRACEFUL_DEGRADATION
    }

    /**
     * Tracks failure state for a single region.
     */
    private static final class RegionFailureState {

        private final long regionId;
        private final AtomicInteger consecutiveFailures = new AtomicInteger();
        private final AtomicInteger consecutiveStalls = new AtomicInteger();
        private final AtomicLong lastFailureMillis = new AtomicLong();
        private final AtomicLong lastRecoveryAttemptMillis = new AtomicLong();
        private final AtomicBoolean nonRecoverable = new AtomicBoolean(false);

        RegionFailureState(final long regionId) {
            this.regionId = regionId;
        }

        void recordFailure() {
            this.consecutiveFailures.incrementAndGet();
            this.lastFailureMillis.set(System.currentTimeMillis());
        }

        void recordStall() {
            this.consecutiveStalls.incrementAndGet();
        }

        void recordSuccess() {
            this.consecutiveFailures.set(0);
            this.consecutiveStalls.set(0);
        }

        void recordRecoveryAttempt() {
            this.lastRecoveryAttemptMillis.set(System.currentTimeMillis());
        }

        void markNonRecoverable() {
            this.nonRecoverable.set(true);
        }

        int getConsecutiveFailures() {
            return this.consecutiveFailures.get();
        }

        int getConsecutiveStalls() {
            return this.consecutiveStalls.get();
        }

        boolean isNonRecoverable() {
            return this.nonRecoverable.get();
        }

        boolean canAttemptRecovery() {
            if (this.nonRecoverable.get()) {
                return false;
            }
            final long lastAttempt = this.lastRecoveryAttemptMillis.get();
            if (lastAttempt <= 0) {
                return true;
            }
            return System.currentTimeMillis() - lastAttempt >= RECOVERY_COOLDOWN_MILLIS;
        }
    }
}

