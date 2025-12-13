package org.bacon.ruthenium.world;

import ca.spottedleaf.concurrentutil.scheduler.SchedulerThreadPool;
import ca.spottedleaf.concurrentutil.util.TimeUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.minecraft.entity.Entity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ChunkLevelManager;
import net.minecraft.server.world.ServerChunkManager;
import net.minecraft.server.world.ServerChunkLoadingManager;
import net.minecraft.server.world.ServerEntityManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.profiler.Profiler;
import net.minecraft.util.profiler.Profilers;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.GameRules;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.tick.TickManager;
import net.minecraft.world.entity.SectionedEntityCache;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bacon.ruthenium.debug.RegionDebug;
import org.bacon.ruthenium.mixin.accessor.ServerChunkManagerAccessor;
import org.bacon.ruthenium.mixin.accessor.ServerEntityManagerAccessor;
import org.bacon.ruthenium.mixin.accessor.ServerWorldAccessor;
import org.bacon.ruthenium.region.RegionTaskQueue;
import org.bacon.ruthenium.region.RegionTickData;
import org.bacon.ruthenium.region.ThreadedRegionizer;
import org.bacon.ruthenium.region.ThreadedRegionizer.ThreadedRegion;
import org.bacon.ruthenium.world.RegionChunkTickAccess;
import org.bacon.ruthenium.world.RegionWatchdog.Event;
import org.bacon.ruthenium.world.RegionWatchdog.RunningTick;
import org.bacon.ruthenium.world.RegionizedServerWorld;

/**
 * Port of Folia's TickRegionScheduler adapted for Ruthenium.
 */
public final class TickRegionScheduler {

    private static final Logger LOGGER = LogManager.getLogger(TickRegionScheduler.class);
    private static final AtomicInteger THREAD_ID = new AtomicInteger();
    private static final TickRegionScheduler INSTANCE = new TickRegionScheduler();
    private static final LoggingOptions LOGGING_OPTIONS = LoggingOptions.load(System::getProperty);
    private static final long TICK_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(1L) / 20L;

    /**
     * Hardcoded verbose logging flag for detailed scheduler diagnostics.
     * Set to true to enable extensive logging of scheduler operations.
     * This is useful for debugging stalling/stopping issues.
     */
    public static final boolean VERBOSE_LOGGING = Boolean.getBoolean("ruthenium.scheduler.verbose");
    private static final long WATCHDOG_WARN_NANOS = loadDuration("ruthenium.scheduler.watchdog.warnSeconds", 10L, TimeUnit.SECONDS);
    private static final long WATCHDOG_CRASH_NANOS = loadDuration("ruthenium.scheduler.watchdog.crashSeconds", 60L, TimeUnit.SECONDS);
    private static final long WATCHDOG_LOG_INTERVAL_NANOS = loadDuration("ruthenium.scheduler.watchdog.logIntervalSeconds", 5L, TimeUnit.SECONDS);
    private static final long WATCHDOG_POLL_INTERVAL_MILLIS = loadDurationMillis("ruthenium.scheduler.watchdog.pollMillis", 1000L);
    private static final long MAIN_THREAD_WARN_NANOS = loadDuration("ruthenium.scheduler.mainThread.warnMillis", 200L, TimeUnit.MILLISECONDS);
    private static final long MAIN_THREAD_CRASH_NANOS = loadDuration("ruthenium.scheduler.mainThread.crashSeconds", 60L, TimeUnit.SECONDS);
    private static final long REGION_TICK_STALL_NANOS =
        loadDuration("ruthenium.scheduler.region.stallSeconds", 5L, TimeUnit.SECONDS);
    private static final long REGION_TICK_STALL_MILLIS = TimeUnit.NANOSECONDS.toMillis(REGION_TICK_STALL_NANOS);

    // Use SchedulerThreadPool from concurrentutil (same as Folia)
    @SuppressWarnings("deprecation")
    private final SchedulerThreadPool scheduler;
    private final AtomicBoolean halted = new AtomicBoolean();
    private final RegionWatchdog watchdog;
    private final AtomicReference<MinecraftServer> serverRef = new AtomicReference<>();

    private final ThreadLocal<ThreadedRegion<RegionTickData, RegionTickData.RegionSectionData>> currentRegion = new ThreadLocal<>();
    private final ThreadLocal<ServerWorld> currentWorld = new ThreadLocal<>();
    private final ThreadLocal<RegionizedWorldData> currentWorldData = new ThreadLocal<>();
    private final ThreadLocal<RegionScheduleHandle> currentHandle = new ThreadLocal<>();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                TickRegionScheduler.getInstance().shutdown();
            } catch (final Throwable throwable) {
                LOGGER.warn("Failed to halt region scheduler during shutdown", throwable);
            }
        }, "Ruthenium-RegionScheduler-Shutdown"));
    }

    @SuppressWarnings("deprecation")
    private TickRegionScheduler() {
        final int processorCount = Runtime.getRuntime().availableProcessors();
        final int targetThreads = Math.max(1, processorCount <= 4 ? 1 : processorCount / 2);

        // Create thread factory following Folia's pattern
        final ThreadFactory threadFactory = new ThreadFactory() {
            @Override
            public Thread newThread(final Runnable runnable) {
                final Thread thread = new Thread(runnable, "Ruthenium Region Thread #" + THREAD_ID.incrementAndGet());
                thread.setDaemon(true);
                thread.setUncaughtExceptionHandler((thr, throwable) -> {
                    LOGGER.error("Unhandled exception in {}", thr.getName(), throwable);
                });
                return thread;
            }
        };

        this.scheduler = new SchedulerThreadPool(targetThreads, threadFactory);
        this.watchdog = new RegionWatchdog(
            WATCHDOG_WARN_NANOS,
            WATCHDOG_CRASH_NANOS,
            WATCHDOG_LOG_INTERVAL_NANOS,
            Math.max(1L, WATCHDOG_POLL_INTERVAL_MILLIS),
            this::handleWatchdogWarning,
            this::handleWatchdogCrash
        );
        this.watchdog.start();
        this.scheduler.start();
        LOGGER.info("TickRegionScheduler started with {} tick threads", targetThreads);
        RegionDebug.log(RegionDebug.LogCategory.SCHEDULER, "Scheduler started with {} tick threads", targetThreads);
    }

    public static TickRegionScheduler getInstance() {
        return INSTANCE;
    }

    public boolean isHalted() {
        return this.halted.get();
    }

    public static ThreadedRegion<RegionTickData, RegionTickData.RegionSectionData> getCurrentRegion() {
        return INSTANCE.currentRegion.get();
    }

    public static ServerWorld getCurrentWorld() {
        return INSTANCE.currentWorld.get();
    }

    public static RegionizedWorldData getCurrentWorldData() {
        return INSTANCE.currentWorldData.get();
    }

    @SuppressWarnings("unused") // exposed for future region task integrations
    public static RegionScheduleHandle getCurrentHandle() {
        return INSTANCE.currentHandle.get();
    }

    public boolean tickWorld(final ServerWorld world, final BooleanSupplier shouldKeepTicking) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(shouldKeepTicking, "shouldKeepTicking");

        final long invocationStart = System.nanoTime();

        if (VERBOSE_LOGGING) {
            LOGGER.info("[VERBOSE] tickWorld START for {} (halted={}, shouldKeepTicking={})",
                describeWorld(world), this.halted.get(), shouldKeepTicking.getAsBoolean());
        }

        if (this.halted.get()) {
            if (VERBOSE_LOGGING) {
                LOGGER.info("[VERBOSE] tickWorld HALTED - scheduler halted before start");
            }
            return this.logFallback(world, FallbackReason.SCHEDULER_HALTED_BEFORE_START,
                this.buildDiagnostics(world, "pre-check", false, false,
                    System.nanoTime() - invocationStart));
        }

        final BooleanSupplier orchestratorGuard = this.composeOrchestratorBudget(shouldKeepTicking, invocationStart);
        this.logBudgetAbortIfExceeded(world, orchestratorGuard, "pre-check", false, false);

        final ThreadedRegionizer<RegionTickData, RegionTickData.RegionSectionData> regionizer = requireRegionizer(world);
        final RegionizedWorldData worldData = ((RegionizedServerWorld)world).ruthenium$getWorldRegionData();
        final long tickStart = System.nanoTime();
        final RunningTick runningTick = this.watchdog.track(world, null, Thread.currentThread(), tickStart);
        worldData.beginTick();
        this.currentWorldData.set(worldData);
        boolean fallback = false;
        try {
            final DrainResult drainResult = drainRegionTasks(regionizer, world, shouldKeepTicking);
            drainedTasks = drainResult.drainedAny();
            drainAbortedByBudget = drainResult.abortedByBudget();
            if (this.halted.get()) {
                if (VERBOSE_LOGGING) {
                    LOGGER.info("[VERBOSE] tickWorld HALTED - scheduler halted during tick");
                }
                fallback = true;
                return this.logFallback(world, FallbackReason.SCHEDULER_HALTED_DURING_TICK,
                    this.buildDiagnostics(world, "after-drain", false, false,
                        System.nanoTime() - tickStart));
            }
            this.logBudgetAbortIfExceeded(world, shouldKeepTicking, "after-drain", drainedTasks, true);
            worldData.populateChunkState(shouldKeepTicking);
            if (this.hasActiveRegions(world) && !this.hasRecentRegionTicks(world)) {
                fallback = true;
                return this.logFallback(world, FallbackReason.REGION_TICKS_STALLED,
                    this.buildDiagnostics(world, "after-drain", drainedTasks, drainAbortedByBudget,
                        System.nanoTime() - tickStart));
            }
            return true;
        } finally {
            this.watchdog.untrack(runningTick);
            this.currentWorldData.remove();
            worldData.finishTick();
            final long duration = System.nanoTime() - tickStart;
            this.checkMainThreadBudget(world, duration, drainedTasks, fallback);
        }
    }

    public void scheduleRegion(final RegionScheduleHandle handle) {
        Objects.requireNonNull(handle, "handle");

        // Don't schedule if currently ticking - it will reschedule itself after the tick completes
        if (handle.isCurrentlyTicking()) {
            if (VERBOSE_LOGGING) {
                LOGGER.info("[VERBOSE] scheduleRegion: skipping region {} - currently ticking", handle.getRegion().id);
            }
            return;
        }

        // Prepare the handle for activation BEFORE scheduling
        // This ensures the scheduled start is set to a valid time
        handle.prepareForActivation();

        final long scheduledStart = handle.getScheduledStartNanos();

        // Validate the scheduled start is reasonable
        if (scheduledStart == SchedulerThreadPool.DEADLINE_NOT_SET) {
            LOGGER.error("Cannot schedule region {} - scheduled start is DEADLINE_NOT_SET after prepareForActivation",
                handle.getRegion().id);
            return;
        }

        if (VERBOSE_LOGGING) {
            LOGGER.info("[VERBOSE] scheduleRegion: region {} in {} (scheduledStart={}, chunks={}, state={}, nonSchedulable={})",
                handle.getRegion().id, handle.getWorld().getRegistryKey().getValue(),
                scheduledStart, handle.getData().getChunks().size(),
                handle.getRegion().getStateForDebug(), handle.isMarkedNonSchedulable());
        }
        LOGGER.info("Scheduling region {} in world {} (scheduledStart={}, chunks={})",
            handle.getRegion().id, handle.getWorld().getRegistryKey().getValue(),
            scheduledStart, handle.getData().getChunks().size());
        RegionDebug.log(RegionDebug.LogCategory.SCHEDULER, "Schedule region {} in world {}",
            handle.getRegion().id, handle.getWorld().getRegistryKey().getValue());

        try {
            this.scheduler.schedule(handle);
        } catch (final Throwable throwable) {
            LOGGER.error("Failed to schedule region {} - scheduler error", handle.getRegion().id, throwable);
        }
    }

    public void descheduleRegion(final RegionScheduleHandle handle) {
        Objects.requireNonNull(handle, "handle");
        if (VERBOSE_LOGGING) {
            LOGGER.info("[VERBOSE] descheduleRegion: region {} in {} (state={}, chunks={}, totalTicks={}, currentlyTicking={})",
                handle.getRegion().id, handle.getWorld().getRegistryKey().getValue(),
                handle.getRegion().getStateForDebug(), handle.getData().getChunks().size(),
                handle.getData().getTickStats().getSampleCount(), handle.isCurrentlyTicking());
        }
        // Always mark non-schedulable first - this ensures the handle won't be rescheduled
        handle.markNonSchedulable();
        LOGGER.info("Descheduling region {} in world {} (totalTicks={})",
            handle.getRegion().id, handle.getWorld().getRegistryKey().getValue(),
            handle.getData().getTickStats().getSampleCount());
        RegionDebug.log(RegionDebug.LogCategory.SCHEDULER, "Deschedule region {} in world {}",
            handle.getRegion().id, handle.getWorld().getRegistryKey().getValue());
        // Only try to retire if the handle is not currently being processed
        // If it's currently ticking, it will simply return false from runTick and not be rescheduled
        if (!handle.isCurrentlyTicking()) {
            this.scheduler.tryRetire(handle);
        }
    }

    @SuppressWarnings("unused") // retained for parity with Folia scheduling adjustments
    public boolean updateTickStartToMax(final RegionScheduleHandle handle, final long newStart) {
        Objects.requireNonNull(handle, "handle");
        // Don't update if currently ticking - it will reschedule itself after the tick completes
        if (handle.isCurrentlyTicking()) {
            if (VERBOSE_LOGGING) {
                LOGGER.info("[VERBOSE] updateTickStartToMax: skipping region {} - currently ticking", handle.getRegion().id);
            }
            return false;
        }
        final boolean adjusted = this.scheduler.updateTickStartToMax(handle, newStart);
        if (adjusted) {
            handle.onScheduledStartAdjusted(newStart);
        }
        return adjusted;
    }

    @SuppressWarnings("unused") // called by planned async scheduling hooks
    public void notifyRegionTasks(final RegionScheduleHandle handle) {
        Objects.requireNonNull(handle, "handle");
        // Don't notify the scheduler if the handle is currently being processed
        // This prevents race conditions that can corrupt the scheduler's internal linked list
        if (handle.isCurrentlyTicking()) {
            if (VERBOSE_LOGGING) {
                LOGGER.info("[VERBOSE] notifyRegionTasks: skipping region {} - currently ticking", handle.getRegion().id);
            }
            return;
        }
        this.scheduler.notifyTasks(handle);
    }


    public RegionScheduleHandle createHandle(final RegionTickData data,
                                             final ThreadedRegion<RegionTickData, RegionTickData.RegionSectionData> region,
                                             final RegionScheduleHandle template) {
        final RegionScheduleHandle handle = new RegionScheduleHandle(this, data, region);
        if (template != null) {
            handle.copyStateFrom(template);
            RegionDebug.log(RegionDebug.LogCategory.SCHEDULER, "Create schedule handle for region {} (copied state)", region.id);
        } else {
            RegionDebug.log(RegionDebug.LogCategory.SCHEDULER, "Create schedule handle for region {}", region.id);
        }
        return handle;
    }

    public void shutdown() {
        if (!this.halted.compareAndSet(false, true)) {
            return;
        }
        this.watchdog.shutdown();
        this.scheduler.halt(true, TimeUnit.SECONDS.toNanos(5L));
    }

    public void registerServer(final MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        final MinecraftServer previous = this.serverRef.getAndSet(server);
        if (previous != server) {
            LOGGER.info("Region scheduler attached to server {}", describeServer(server));
            RegionDebug.log(RegionDebug.LogCategory.SCHEDULER,
                "Region scheduler attached to server {}", describeServer(server));
        }
    }

    public void unregisterServer(final MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        if (this.serverRef.compareAndSet(server, null)) {
            LOGGER.info("Region scheduler detached from server {}", describeServer(server));
            RegionDebug.log(RegionDebug.LogCategory.SCHEDULER,
                "Region scheduler detached from server {}", describeServer(server));
        }
    }

    public boolean hasActiveRegions(final ServerWorld world) {
        final ThreadedRegionizer<RegionTickData, RegionTickData.RegionSectionData> regionizer = requireRegionizer(world);
        final boolean[] active = new boolean[1];
        final AtomicInteger totalRegions = new AtomicInteger();
        final AtomicInteger activeCount = new AtomicInteger();
        regionizer.computeForAllRegions(region -> {
            totalRegions.incrementAndGet();
            if (!region.getData().getChunks().isEmpty()) {
                active[0] = true;
                activeCount.incrementAndGet();
            }
        });
        if (VERBOSE_LOGGING) {
            LOGGER.info("[VERBOSE] hasActiveRegions: totalRegions={}, regionsWithChunks={}, result={} for {}",
                totalRegions.get(), activeCount.get(), active[0], describeWorld(world));
        }
        return active[0];
    }

    @SuppressWarnings("deprecation")
    private boolean hasRecentRegionTicks(final ServerWorld world) {
        final ThreadedRegionizer<RegionTickData, RegionTickData.RegionSectionData> regionizer = requireRegionizer(world);
        final long now = System.nanoTime();
        final boolean[] recent = new boolean[1];
        final List<Long> stalledRegions = new ArrayList<>();
        final AtomicInteger checkedCount = new AtomicInteger();
        final AtomicInteger skippedNoHandle = new AtomicInteger();
        final AtomicInteger skippedNoChunks = new AtomicInteger();
        final AtomicInteger skippedNonSchedulable = new AtomicInteger();

        if (VERBOSE_LOGGING) {
            LOGGER.info("[VERBOSE] hasRecentRegionTicks START for {}", describeWorld(world));
        }

        regionizer.computeForAllRegions(region -> {
            checkedCount.incrementAndGet();
            if (recent[0]) {
                return;
            }
            final RegionTickData data = region.getData();

            // Skip regions with no chunks - they have nothing to tick
            if (data.getChunks().isEmpty()) {
                skippedNoChunks.incrementAndGet();
                if (VERBOSE_LOGGING) {
                    LOGGER.info("[VERBOSE] hasRecentRegionTicks region {} skipped - no chunks (state={})",
                        region.id, region.getStateForDebug());
                }
                return;
            }

            final RegionScheduleHandle handle;
            try {
                handle = data.getScheduleHandle();
            } catch (final IllegalStateException ignored) {
                skippedNoHandle.incrementAndGet();
                if (VERBOSE_LOGGING) {
                    LOGGER.info("[VERBOSE] hasRecentRegionTicks region {} skipped - no handle (state={})",
                        region.id, region.getStateForDebug());
                }
                return;
            }

            // Skip regions that are marked non-schedulable (dead, being merged, etc.)
            if (handle.isMarkedNonSchedulable()) {
                skippedNonSchedulable.incrementAndGet();
                if (VERBOSE_LOGGING) {
                    LOGGER.info("[VERBOSE] hasRecentRegionTicks region {} skipped - nonSchedulable (state={})",
                        region.id, region.getStateForDebug());
                }
                return;
            }

            // If a region is currently TICKING, it's actively being processed - not stalled
            // This prevents false stall detection for long-running ticks
            final String stateStr = region.getStateForDebug();
            if ("TICKING".equals(stateStr)) {
                if (VERBOSE_LOGGING) {
                    LOGGER.info("[VERBOSE] hasRecentRegionTicks region {} OK - currently TICKING (chunks={})",
                        region.id, data.getChunks().size());
                }
                recent[0] = true;
                return;
            }

            final long lastTickStart = handle.getLastTickStart();
            final long scheduledStart = handle.getScheduledStartNanos();

            if (lastTickStart == SchedulerThreadPool.DEADLINE_NOT_SET) {
                // Region has never ticked - check if it's newly scheduled
                if (scheduledStart != SchedulerThreadPool.DEADLINE_NOT_SET && scheduledStart > now) {
                    // Region is scheduled for the future - this is fine, consider it "recent"
                    if (VERBOSE_LOGGING) {
                        final long untilScheduledMs = TimeUnit.NANOSECONDS.toMillis(scheduledStart - now);
                        LOGGER.info("[VERBOSE] hasRecentRegionTicks region {} OK - never ticked but scheduled in {}ms (state={}, chunks={})",
                            region.id, untilScheduledMs, region.getStateForDebug(), data.getChunks().size());
                    }
                    recent[0] = true;
                } else {
                    stalledRegions.add(region.id);
                    if (VERBOSE_LOGGING) {
                        LOGGER.info("[VERBOSE] hasRecentRegionTicks region {} STALLED - never ticked, no future schedule (state={}, scheduledStart={}, chunks={})",
                            region.id, region.getStateForDebug(), scheduledStart, data.getChunks().size());
                    }
                }
            } else if (now - lastTickStart > REGION_TICK_STALL_NANOS) {
                stalledRegions.add(region.id);
                final long ageMillis = TimeUnit.NANOSECONDS.toMillis(now - lastTickStart);
                if (VERBOSE_LOGGING) {
                    LOGGER.info("[VERBOSE] hasRecentRegionTicks region {} STALLED - last tick {}ms ago (state={}, scheduledStart={}, chunks={})",
                        region.id, ageMillis, region.getStateForDebug(),
                        scheduledStart, data.getChunks().size());
                }
                LOGGER.warn("Region {} in {} has not ticked for {}ms (chunks={}, scheduledStart={}, isMarkedNonSchedulable={})",
                    region.id, describeWorld(world), ageMillis, data.getChunks().size(),
                    scheduledStart, handle.isMarkedNonSchedulable());
            } else if (!handle.hasScheduledDeadline()) {
                stalledRegions.add(region.id);
                if (VERBOSE_LOGGING) {
                    LOGGER.info("[VERBOSE] hasRecentRegionTicks region {} STALLED - lost deadline (state={}, lastStart={})",
                        region.id, region.getStateForDebug(), lastTickStart);
                }
                LOGGER.warn("Region {} in {} lost its scheduled deadline (lastStart={}, chunks={})",
                    region.id, describeWorld(world), lastTickStart, data.getChunks().size());
                handle.prepareForActivation();
                this.scheduler.notifyTasks(handle);
            } else {
                if (VERBOSE_LOGGING) {
                    final long ageMillis = TimeUnit.NANOSECONDS.toMillis(now - lastTickStart);
                    LOGGER.info("[VERBOSE] hasRecentRegionTicks region {} OK - last tick {}ms ago (state={}, chunks={})",
                        region.id, ageMillis, region.getStateForDebug(), data.getChunks().size());
                }
                recent[0] = true;
            }
        });

        if (!stalledRegions.isEmpty() && !recent[0]) {
            LOGGER.error("ALL active regions in {} are stalled! Stalled regions: {}", describeWorld(world), stalledRegions);
        }

        if (VERBOSE_LOGGING) {
            LOGGER.info("[VERBOSE] hasRecentRegionTicks END: checked={}, skippedNoHandle={}, skippedNoChunks={}, skippedNonSchedulable={}, stalled={}, hasRecent={} for {}",
                checkedCount.get(), skippedNoHandle.get(), skippedNoChunks.get(), skippedNonSchedulable.get(),
                stalledRegions.size(), recent[0], describeWorld(world));
        }

        return recent[0];
    }

    public void logSchedulerConflict(final ServerWorld world, final String message) {
        final String worldId = describeWorld(world);
        LOGGER.warn("{} (world={})", message, worldId);
        RegionDebug.log(RegionDebug.LogCategory.SCHEDULER, message + " (world=" + worldId + ")");
    }

    public void logBudgetAbort(final ServerWorld world, final String stage) {
        this.logBudgetAbort(world, stage, false, false);
    }

    public void logBudgetAbort(final ServerWorld world,
                               final String stage,
                               final boolean drainedTasks,
                               final boolean abortedDrain) {
        if (world instanceof RegionizedServerWorld regionized) {
            final RegionizedWorldData worldData = regionized.ruthenium$getWorldRegionData();
            if (!worldData.shouldLogBudgetWarning(stage)) {
                return;
            }
        }
        final FallbackDiagnostics diagnostics = this.buildDiagnostics(world, stage, drainedTasks, abortedDrain, -1L);
        this.logFallbackMessage("Main-thread budget exhausted while orchestrating {} for {} ({})",
            stage, describeWorld(world), diagnostics.describe());
    }

    private void logRegionChunkAbort(final ServerWorld world,
                                     final ThreadedRegion<RegionTickData, RegionTickData.RegionSectionData> region,
                                     final int processedChunks,
                                     final int scheduledChunks) {
        final String message = String.format(Locale.ROOT,
            "Budget exhausted while ticking chunks for region %s (processed=%d/%d, world=%s)",
            region.id, processedChunks, scheduledChunks, describeWorld(world));
        LOGGER.warn(message);
        RegionDebug.log(RegionDebug.LogCategory.SCHEDULER, message);
    }

    private void logRegionTaskAbort(final ServerWorld world,
                                    final ThreadedRegion<RegionTickData, RegionTickData.RegionSectionData> region,
                                    final int processedTasks) {
        final String message = String.format(Locale.ROOT,
            "Budget exhausted while flushing task queue for region %s (processedTasks=%d, world=%s)",
            region.id, processedTasks, describeWorld(world));
        LOGGER.warn(message);
        RegionDebug.log(RegionDebug.LogCategory.SCHEDULER, message);
    }

    void enterRegionContext(final ThreadedRegion<RegionTickData, RegionTickData.RegionSectionData> region,
                            final ServerWorld world, final RegionScheduleHandle handle) {
        this.currentRegion.set(region);
        this.currentWorld.set(world);
        this.currentWorldData.set(handle.getData().getWorldData());
        this.currentHandle.set(handle);
    }

    void exitRegionContext() {
        this.currentHandle.remove();
        this.currentWorld.remove();
        this.currentWorldData.remove();
        this.currentRegion.remove();
    }

    boolean tickRegion(final RegionScheduleHandle handle, final BooleanSupplier guard) {
        final ThreadedRegion<RegionTickData, RegionTickData.RegionSectionData> region = handle.getRegion();
        final RegionTickData data = handle.getData();
        final ServerWorld world = region.regioniser.world;
        final RegionizedWorldData worldData = getCurrentWorldData();

        int processedTasks = 0;
        processedTasks += runQueuedTasks(data, region, guard);

        final MinecraftServer server = world.getServer();
        final int randomTickSpeed = server.getGameRules().getInt(GameRules.RANDOM_TICK_SPEED);
        final ServerChunkManager chunkManager = world.getChunkManager();

        int tickedChunks = 0;
        int skippedNotContained = 0;
        int skippedNotFull = 0;
        final long[] chunkSnapshot = data.getChunks().toLongArray();
        boolean chunkLoopAborted = false;
        for (int i = 0; i < chunkSnapshot.length; ++i) {
            if (!guard.getAsBoolean()) {
                chunkLoopAborted = i < chunkSnapshot.length;
                break;
            }
            final long chunkKey = chunkSnapshot[i];
            final int chunkX = RegionTickData.decodeChunkX(chunkKey);
            final int chunkZ = RegionTickData.decodeChunkZ(chunkKey);
            if (!data.containsChunk(chunkX, chunkZ)) {
                skippedNotContained++;
                continue;
            }
            final Chunk chunk = chunkManager.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
            if (!(chunk instanceof WorldChunk worldChunk)) {
                skippedNotFull++;
                continue;
            }

            ((RegionChunkTickAccess)world).ruthenium$pushRegionChunkTick();
            if (worldData != null) {
                worldData.markEntityTickingChunk(chunkX, chunkZ);
            }
            try {
                ((ServerWorldAccessor)world).ruthenium$invokeTickChunk(worldChunk, randomTickSpeed);
                this.tickChunkEntities(world, chunkManager, chunkX, chunkZ);
                tickedChunks++;
            } catch (final Throwable throwable) {
                LOGGER.error("Failed to tick chunk {} in region {}", new ChunkPos(chunkX, chunkZ), region.id, throwable);
            } finally {
                ((RegionChunkTickAccess)world).ruthenium$popRegionChunkTick();
                if (worldData != null) {
                    worldData.unmarkEntityTickingChunk(chunkX, chunkZ);
                }
            }
        }
        if (chunkLoopAborted) {
            this.logRegionChunkAbort(world, region, tickedChunks, chunkSnapshot.length);
        }

        processedTasks += runQueuedTasks(data, region, guard);
        data.advanceCurrentTick();
        data.advanceRedstoneTick();
        final long lagCompTick = worldData == null ? -1L : worldData.getLagCompensationTick();

        if (VERBOSE_LOGGING) {
            LOGGER.info("[VERBOSE] tickRegion END region {}: total={}, ticked={}, skippedNotContained={}, skippedNotFull={}, tasks={}, aborted={}",
                region.id, chunkSnapshot.length, tickedChunks, skippedNotContained, skippedNotFull, processedTasks, chunkLoopAborted);
        }

        if (LOGGING_OPTIONS.logRegionSummaries()) {
            LOGGER.info("Region {} tick summary: chunksTicked={}, tasksProcessed={}, lagComp={}ns (world={})",
                region.id, tickedChunks, processedTasks, lagCompTick, world.getRegistryKey().getValue());
        }
        RegionDebug.log(RegionDebug.LogCategory.SCHEDULER,
            "Region {} tick summary: chunksTicked={}, tasksProcessed={}, lagComp={}ns (world={})",
            region.id, tickedChunks, processedTasks, lagCompTick, world.getRegistryKey().getValue());
        RegionTickMonitor.getInstance().recordTick(world, region.id, tickedChunks, processedTasks,
            System.nanoTime() - tickStart);
        return true;
    }

    @SuppressWarnings("unchecked")
    private void tickChunkEntities(final ServerWorld world,
                                   final ServerChunkManager chunkManager,
                                   final int chunkX,
                                   final int chunkZ) {
        final ServerChunkLoadingManager loadingManager =
            ((ServerChunkManagerAccessor)chunkManager).ruthenium$getChunkLoadingManager();
        final ChunkLevelManager levelManager = loadingManager.getLevelManager();
        final long chunkKey = ChunkPos.toLong(chunkX, chunkZ);
        if (!levelManager.shouldTickEntities(chunkKey)) {
            return;
        }

        final TickManager tickManager = world.getTickManager();
        final Profiler profiler = Profilers.get();
        final ServerEntityManager<Entity> entityManager =
            (ServerEntityManager<Entity>)((ServerWorldAccessor)world).ruthenium$getEntityManager();
        final SectionedEntityCache<Entity> cache =
            ((ServerEntityManagerAccessor)entityManager).ruthenium$getEntitySectionCache();

        cache.getTrackingSections(chunkKey).forEach(section -> {
            final List<Entity> entities = section.stream().collect(Collectors.toList());
            for (final Entity entity : entities) {
                this.tickRegionEntity(world, tickManager, profiler, levelManager, entity);
            }
        });
    }

    private void tickRegionEntity(final ServerWorld world,
                                   final TickManager tickManager,
                                   final Profiler profiler,
                                   final ChunkLevelManager levelManager,
                                   final Entity entity) {
        if (entity == null || entity.isRemoved()) {
            return;
        }
        if (tickManager.shouldSkipTick(entity)) {
            return;
        }

        profiler.push("checkDespawn");
        entity.checkDespawn();
        profiler.pop();

        final long entityChunkKey = entity.getChunkPos().toLong();
        if (!(entity instanceof ServerPlayerEntity) && !levelManager.shouldTickEntities(entityChunkKey)) {
            return;
        }

        final Entity vehicle = entity.getVehicle();
        if (vehicle != null) {
            if (!vehicle.isRemoved() && vehicle.hasPassenger(entity)) {
                return;
            }
            entity.stopRiding();
        }

        profiler.push("tick");
        ((ServerWorldAccessor)world).ruthenium$invokeTickEntityLifecycle(tickManager, profiler, entity);
        profiler.pop();
    }

    private int runQueuedTasks(final RegionTickData data,
                                final ThreadedRegion<RegionTickData, RegionTickData.RegionSectionData> region,
                                final BooleanSupplier guard) {
        final RegionTaskQueue queue = data.getTaskQueue();
        final ServerWorld world = region.regioniser.world;
        int processed = 0;
        boolean guardStoppedTasks = false;

        while (true) {
            if (!guard.getAsBoolean()) {
                guardStoppedTasks = !queue.isEmpty();
                break;
            }
            final RegionTaskQueue.RegionChunkTask task = queue.pollChunkTask();
            if (task == null) {
                break;
            }
            if (!data.containsChunk(task.chunkX(), task.chunkZ())) {
                LOGGER.debug("Skipping chunk task for {} in region {} because the chunk is no longer present",
                    new ChunkPos(task.chunkX(), task.chunkZ()), region.id);
                continue;
            }
            try {
                task.runnable().run();
                processed++;
            } catch (final Throwable throwable) {
                LOGGER.error("Chunk task for {} in region {} failed",
                    new ChunkPos(task.chunkX(), task.chunkZ()), region.id, throwable);
            }
        }
        if (guardStoppedTasks) {
            this.logRegionTaskAbort(world, region, processed);
        }
        if (processed > 0) {
            if (LOGGING_OPTIONS.logTaskQueueProcessing()) {
                LOGGER.info("Processed {} queued chunk tasks for region {}", processed, region.id);
            }
            RegionDebug.log(RegionDebug.LogCategory.SCHEDULER,
                "Processed {} queued chunk tasks for region {}", processed, region.id);
        }
        return processed;
    }

    void handleRegionFailure(final RegionScheduleHandle handle, final Throwable throwable) {
        final ThreadedRegion<RegionTickData, RegionTickData.RegionSectionData> region = handle.getRegion();
        final ServerWorld world = region.regioniser.world;
        LOGGER.error("Region {} in world {} failed during tick", region.id, world.getRegistryKey().getValue(), throwable);
        RegionDebug.log(RegionDebug.LogCategory.SCHEDULER,
            "Region {} failed during tick: {}", region.id, String.valueOf(throwable.getMessage()));
        if (this.halted.compareAndSet(false, true)) {
            this.watchdog.shutdown();
            this.scheduler.halt(false, 0L);
        }
        final MinecraftServer server = world.getServer();
        RegionShutdownThread.requestShutdown(server, this);
        handle.markNonSchedulable();
    }

    private ThreadedRegionizer<RegionTickData, RegionTickData.RegionSectionData> requireRegionizer(final ServerWorld world) {
        if (!(world instanceof RegionizedServerWorld regionized)) {
            throw new IllegalStateException("World " + world + " is missing RegionizedServerWorld support");
        }
        return regionized.ruthenium$getRegionizer();
    }

    private static long loadDuration(final String key, final long defaultValue, final TimeUnit unit) {
        final String raw = System.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return unit.toNanos(defaultValue);
        }
        try {
            return unit.toNanos(Long.parseLong(raw.trim()));
        } catch (final NumberFormatException ex) {
            LOGGER.warn("Invalid value '{}' for system property {}. Using default {} {}.", raw, key, defaultValue,
                unit.name().toLowerCase(Locale.ROOT));
            return unit.toNanos(defaultValue);
        }
    }

    private static long loadDurationMillis(final String key, final long defaultValue) {
        final String raw = System.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (final NumberFormatException ex) {
            LOGGER.warn("Invalid value '{}' for system property {}. Using default {} ms.", raw, key, defaultValue);
            return defaultValue;
        }
    }

    private void logFallbackMessage(final String message, final Object... args) {
        if (LOGGING_OPTIONS.logFallbacks()) {
            LOGGER.info(message, args);
            if (LOGGING_OPTIONS.logFallbackStacks()) {
                LOGGER.debug("Vanilla fallback triggered stack:\n{}", formatStackTrace(Thread.currentThread()));
            }
        }
        RegionDebug.log(RegionDebug.LogCategory.SCHEDULER, message, args);
    }

    private boolean logFallback(final ServerWorld world,
                                final FallbackReason reason,
                                final FallbackDiagnostics diagnostics) {
        final String detail = diagnostics == null ? "stage=unknown" : diagnostics.describe();
        final String action = reason.fallbackToVanilla() ? "Scheduler fallback" : "Scheduler skip";
        this.logFallbackMessage("{}: reason={} (world={}, {})",
            action, reason.description(), describeWorld(world), detail);
        return !reason.fallbackToVanilla();
    }

    private void logBudgetAbortIfExceeded(final ServerWorld world,
                                          final BooleanSupplier shouldKeepTicking,
                                          final String stage,
                                          final boolean drainedTasks,
                                          final boolean abortedDrain) {
        if (!shouldKeepTicking.getAsBoolean()) {
            this.logBudgetAbort(world, stage, drainedTasks, abortedDrain);
        }
    }

    /**
     * Builds a local main-thread budget guard for the orchestrator which tolerates
     * vanilla's shouldKeepTicking() returning false too early by allowing work
     * to proceed until the world-local 50ms window elapses. This prevents
     * pathological immediate fallbacks while still bounding main-thread work.
     */
    private BooleanSupplier composeOrchestratorBudget(final BooleanSupplier serverGuard,
                                                      final long startNanos) {
        Objects.requireNonNull(serverGuard, "serverGuard");
        return () -> {
            if (serverGuard.getAsBoolean()) {
                return true;
            }
            final long elapsed = System.nanoTime() - startNanos;
            return elapsed < TICK_INTERVAL_NANOS;
        };
    }

    private FallbackDiagnostics buildDiagnostics(final ServerWorld world,
                                                 final String stage,
                                                 final boolean drainedTasks,
                                                 final boolean abortedDrain,
                                                 final long elapsedNanos) {
        RegionizedWorldData worldData = null;
        boolean handlingTick = false;
        boolean activeRegions = false;
        if (world instanceof RegionizedServerWorld regionized) {
            worldData = regionized.ruthenium$getWorldRegionData();
            handlingTick = worldData.isHandlingTick();
            activeRegions = this.hasActiveRegions(world);
        }
        return new FallbackDiagnostics(stage, drainedTasks, handlingTick, activeRegions, abortedDrain, elapsedNanos);
    }

    private void handleWatchdogWarning(final Event event) {
        final RunningTick tick = event.tick();
        final double millis = nanosToMillis(event.durationNanos());
        final Thread thread = tick.thread();
        final RegionScheduleHandle handle = tick.handle();

        if (handle != null) {
            final ServerWorld world = handle.getWorld();
            final String message = String.format(Locale.ROOT,
                "Region watchdog warning: %s exceeded %.2f ms on thread %s",
                describeHandle(handle), millis, thread.getName());
            LOGGER.warn(message);
            RegionDebug.log(RegionDebug.LogCategory.SCHEDULER, message);
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Region watchdog stack trace:\n{}", formatStackTrace(thread));
            }
        } else {
            final ServerWorld world = tick.world();
            if (world != null) {
                LOGGER.warn("Main thread watchdog warning: world {} tick exceeded {} ms",
                    world.getRegistryKey().getValue(), String.format(Locale.ROOT, "%.2f", millis));
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("Main thread stack:\n{}", formatStackTrace(thread));
                }
            } else {
                LOGGER.warn("Watchdog observed long-running task ({} ms) on thread {}",
                    String.format(Locale.ROOT, "%.2f", millis), thread.getName());
            }
        }
    }

    private void handleWatchdogCrash(final Event event) {
        final RunningTick tick = event.tick();
        final Thread thread = tick.thread();
        final long durationNanos = event.durationNanos();
        final double millis = nanosToMillis(durationNanos);
        final RegionScheduleHandle handle = tick.handle();

        if (handle == null) {
            final ServerWorld world = tick.world();
            if (world != null) {
                LOGGER.error("Main thread watchdog timeout: world {} tick exceeded {} ms",
                    world.getRegistryKey().getValue(), String.format(Locale.ROOT, "%.2f", millis));
                LOGGER.error("Main thread stack:\n{}", formatStackTrace(thread));
                this.handleMainThreadTimeout(world, durationNanos);
            } else {
                LOGGER.error("Watchdog timeout on thread {} (duration {} ms) with no associated world",
                    thread.getName(), String.format(Locale.ROOT, "%.2f", millis));
            }
            return;
        }

        final String message = String.format(Locale.ROOT,
            "Region watchdog timeout: %s exceeded %.2f ms on thread %s",
            describeHandle(handle), millis, thread.getName());
        LOGGER.error(message);
        LOGGER.error("Region stack trace:\n{}", formatStackTrace(thread));
        this.handleRegionFailure(handle, new RuntimeException(message));
    }

    private void checkMainThreadBudget(final ServerWorld world, final long durationNanos,
                                       final boolean drainedTasks, final boolean fallback) {
        if (MAIN_THREAD_CRASH_NANOS > 0L && durationNanos >= MAIN_THREAD_CRASH_NANOS) {
            this.handleMainThreadTimeout(world, durationNanos);
            return;
        }

        if (MAIN_THREAD_WARN_NANOS > 0L && durationNanos >= MAIN_THREAD_WARN_NANOS) {
            this.handleMainThreadWarning(world, durationNanos, drainedTasks, fallback);
        }
    }

    private void handleMainThreadWarning(final ServerWorld world, final long durationNanos,
                                         final boolean drainedTasks, final boolean fallback) {
        final double millis = nanosToMillis(durationNanos);
        final String message = String.format(Locale.ROOT,
            "Main thread spent %.2f ms orchestrating region ticks for world %s (tasksDrained=%s, fallback=%s)",
            millis, world.getRegistryKey().getValue(), drainedTasks, fallback);
        LOGGER.warn(message);
        RegionDebug.log(RegionDebug.LogCategory.SCHEDULER, message);
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Main thread stack:\n{}", formatStackTrace(Thread.currentThread()));
        }
    }

    private void handleMainThreadTimeout(final ServerWorld world, final long durationNanos) {
        if (!this.halted.compareAndSet(false, true)) {
            return;
        }
        final double millis = nanosToMillis(durationNanos);
        final String worldId = String.valueOf(world.getRegistryKey().getValue());
        final RuntimeException exception = new RuntimeException(String.format(Locale.ROOT,
            "Main thread watchdog timeout: world %s tick exceeded %.2f ms", worldId, millis));
        LOGGER.error(exception.getMessage());
        LOGGER.error("Main thread stack:\n{}", formatStackTrace(Thread.currentThread()));
        this.watchdog.shutdown();
        this.scheduler.halt(false, 0L);
        final MinecraftServer server = world.getServer();
        RegionShutdownThread.requestShutdown(server, this);
    }

    private static double nanosToMillis(final long nanos) {
        return nanos / 1_000_000.0D;
    }

    private enum FallbackReason {
        SCHEDULER_HALTED_BEFORE_START("Scheduler halted before running world tick", true),
        SERVER_BUDGET_EXHAUSTED_BEFORE_START("Server requested stop-ticking before scheduler start", true),
        SCHEDULER_HALTED_DURING_TICK("Scheduler halted mid-tick", true),
        SERVER_BUDGET_EXHAUSTED_DURING_TICK("Server requested stop-ticking during scheduler pump", true),
        REGION_TICKS_STALLED("Region scheduler produced no ticks recently", true),
        NO_ACTIVE_REGIONS("No active regions available to tick", true);

        private final String description;
        private final boolean fallbackToVanilla;

        FallbackReason(final String description, final boolean fallbackToVanilla) {
            this.description = description;
            this.fallbackToVanilla = fallbackToVanilla;
        }

        public String description() {
            return this.description;
        }

        public boolean fallbackToVanilla() {
            return this.fallbackToVanilla;
        }
    }

    private record FallbackDiagnostics(String stage,
                                       boolean drainedTasks,
                                       boolean handlingTick,
                                       boolean activeRegions,
                                       boolean abortedDrain,
                                       long elapsedNanos) {

        String describe() {
            final String elapsed;
            if (this.elapsedNanos >= 0L) {
                elapsed = String.format(Locale.ROOT, "%.3f", nanosToMillis(this.elapsedNanos));
            } else {
                elapsed = "n/a";
            }
            return String.format(Locale.ROOT,
                "stage=%s, drainedTasks=%s, handlingTick=%s, activeRegions=%s, abortedDrain=%s, elapsedMillis=%s",
                this.stage, this.drainedTasks, this.handlingTick, this.activeRegions, this.abortedDrain, elapsed);
        }
    }

    private static String formatStackTrace(final Thread thread) {
        final StringBuilder builder = new StringBuilder(256);
        builder.append(thread.getName()).append(':').append(System.lineSeparator());
        for (final StackTraceElement element : thread.getStackTrace()) {
            builder.append("    at ").append(element).append(System.lineSeparator());
        }
        return builder.toString();
    }

    private String describeHandle(final RegionScheduleHandle handle) {
        if (handle == null) {
            return "<global>";
        }
        final ThreadedRegion<RegionTickData, RegionTickData.RegionSectionData> region = handle.getRegion();
        if (region == null) {
            return "<unassigned>";
        }
        final ChunkPos center = region.getCenterChunk();
        final String worldId = String.valueOf(handle.getWorld().getRegistryKey().getValue());
        final String centerStr = center == null ? "unknown" : center.x + "," + center.z;
        return "region=" + region.id + "@" + centerStr + " world=" + worldId;
    }

    public List<String> buildDebugDump() {
        final List<String> lines = new ArrayList<>();
        lines.add("=== RUTHENIUM SCHEDULER DEBUG DUMP ===");
        lines.add("Timestamp: " + System.currentTimeMillis());
        lines.add("Scheduler halted=" + this.halted.get());
        lines.add("Thread pool: " + this.scheduler.toString());
        lines.add("Watchdog active=" + !this.watchdog.isShutdown());
        final MinecraftServer server = this.serverRef.get();
        if (server == null) {
            lines.add("No server registered.");
            return lines;
        }
        lines.add("Server=" + describeServer(server));
        for (final ServerWorld world : server.getWorlds()) {
            lines.add("World " + describeWorld(world) + ":");
            lines.add("  hasActiveRegions=" + this.hasActiveRegions(world));
            if (world instanceof RegionizedServerWorld regionized) {
                final RegionizedWorldData worldData = regionized.ruthenium$getWorldRegionData();
                lines.add("  handlingTick=" + worldData.isHandlingTick()
                    + " tickAllowed=" + worldData.isTickAllowed());
                lines.add("  tickingChunks=" + worldData.snapshotTickingChunks().length
                    + " entityChunks=" + worldData.snapshotEntityTickingChunks().length);
                final ThreadedRegionizer<RegionTickData, RegionTickData.RegionSectionData> regionizer =
                    regionized.ruthenium$getRegionizer();
                final AtomicInteger totalRegions = new AtomicInteger();
                final AtomicInteger chunkRegions = new AtomicInteger();
                final AtomicLong totalChunks = new AtomicLong();
                final AtomicInteger pendingTasks = new AtomicInteger();
                regionizer.computeForAllRegions(region -> {
                    totalRegions.incrementAndGet();
                    final int chunkCount = region.getData().getChunks().size();
                    if (chunkCount > 0) {
                        chunkRegions.incrementAndGet();
                        totalChunks.addAndGet(chunkCount);
                    }
                    final int taskCount = region.getData().getTaskQueue().size();
                    if (taskCount > 0) {
                        pendingTasks.addAndGet(taskCount);
                    }
                });
                lines.add("  regions total=" + totalRegions.get() + " withChunks=" + chunkRegions.get()
                    + " trackedChunks=" + totalChunks.get() + " pendingTasks=" + pendingTasks.get());
                final List<RegionDebugInfo> debugInfos = this.gatherRegionDebugInfo(world, System.nanoTime());
                if (!debugInfos.isEmpty()) {
                    final long stalledCount = debugInfos.stream().filter(RegionDebugInfo::isStalled).count();
                    lines.add("  stalledRegions=" + stalledCount + " thresholdMillis=" + REGION_TICK_STALL_MILLIS);
                    final int detailLimit = Math.min(debugInfos.size(), 5);
                    for (int i = 0; i < detailLimit; ++i) {
                        final RegionDebugInfo info = debugInfos.get(i);
                        final String age = info.lastTickAgeMillis() < 0L ? "n/a" : String.valueOf(info.lastTickAgeMillis());
                        lines.add(String.format(Locale.ROOT,
                            "    region %d: chunks=%d tasks=%d lastTickMs=%.3f ageMillis=%s",
                            info.regionId(), info.chunkCount(), info.pendingTasks(), info.lastTickMillis(), age));
                    }
                    if (debugInfos.size() > detailLimit) {
                        lines.add("    ... " + (debugInfos.size() - detailLimit) + " more regions");
                    }
                }
            } else {
                lines.add("  (world not regionized)");
            }
        }
        return lines;
    }

    @SuppressWarnings("deprecation")
    private List<RegionDebugInfo> gatherRegionDebugInfo(final ServerWorld world, final long now) {
        final List<RegionDebugInfo> infos = new ArrayList<>();
        if (!(world instanceof RegionizedServerWorld regionized)) {
            return infos;
        }
        final ThreadedRegionizer<RegionTickData, RegionTickData.RegionSectionData> regionizer =
            regionized.ruthenium$getRegionizer();
        regionizer.computeForAllRegions(region -> {
            final RegionTickData data = region.getData();
            final int chunkCount = data.getChunks().size();
            final int pendingTasks = data.getTaskQueue().size();
            final RegionTickStats stats = data.getTickStats();
            final double lastTickMs = stats == null ? 0.0D : stats.getLastTickNanos() / 1_000_000.0D;
            long lastTickAgeMillis = -1L;
            try {
                final RegionScheduleHandle handle = data.getScheduleHandle();
                final long lastStart = handle.getLastTickStart();
                if (lastStart != SchedulerThreadPool.DEADLINE_NOT_SET) {
                    lastTickAgeMillis = Math.max(0L, TimeUnit.NANOSECONDS.toMillis(now - lastStart));
                }
            } catch (final IllegalStateException ignored) {
                // handle not yet initialised; leave age as unknown
            }
            infos.add(new RegionDebugInfo(region.id, chunkCount, pendingTasks, lastTickMs, lastTickAgeMillis));
        });
        infos.sort((left, right) -> Integer.compare(right.chunkCount(), left.chunkCount()));
        return infos;
    }

    private static String describeWorld(final ServerWorld world) {
        return String.valueOf(world.getRegistryKey().getValue());
    }

    private static String describeServer(final MinecraftServer server) {
        final String motd = server.getServerMotd();
        if (motd == null || motd.isBlank()) {
            return server.toString();
        }
        return motd;
    }

    /**
     * Handle for scheduling and managing a region's tick lifecycle.
     * Extends SchedulableTick to integrate with SchedulerThreadPool.
     *
     * Following Folia's pattern where the handle is created with DEADLINE_NOT_SET
     * and then setInitialStart() is called before scheduling.
     */
    @SuppressWarnings("deprecation")
    public static final class RegionScheduleHandle extends SchedulerThreadPool.SchedulableTick {

        private final TickRegionScheduler scheduler;
        private final RegionTickData data;
        private ThreadedRegion<RegionTickData, RegionTickData.RegionSectionData> region;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicBoolean currentlyTicking = new AtomicBoolean();
        private long lastTickStart = SchedulerThreadPool.DEADLINE_NOT_SET;
        private final RegionTickStats tickStats = new RegionTickStats();
        private final Schedule tickSchedule;

        private RegionScheduleHandle(final TickRegionScheduler scheduler,
                                     final RegionTickData data,
                                     final ThreadedRegion<RegionTickData, RegionTickData.RegionSectionData> region) {
            this.scheduler = scheduler;
            this.data = data;
            this.region = region;
            // Following Folia's pattern: initialize with DEADLINE_NOT_SET
            // The tickSchedule is also initialized properly
            this.setScheduledStart(SchedulerThreadPool.DEADLINE_NOT_SET);
            this.tickSchedule = new Schedule(SchedulerThreadPool.DEADLINE_NOT_SET);
        }

        public RegionTickData getData() {
            return this.data;
        }

        public ThreadedRegion<RegionTickData, RegionTickData.RegionSectionData> getRegion() {
            return this.region;
        }

        public ServerWorld getWorld() {
            return this.region.regioniser.world;
        }

        public long getLastTickStart() {
            return this.lastTickStart;
        }

        public long getScheduledStartNanos() {
            return this.getScheduledStart();
        }

        boolean hasScheduledDeadline() {
            return this.getScheduledStart() != SchedulerThreadPool.DEADLINE_NOT_SET;
        }

        void attachRegion(final ThreadedRegion<RegionTickData, RegionTickData.RegionSectionData> newRegion) {
            this.region = Objects.requireNonNull(newRegion, "newRegion");
        }

        public void copyStateFrom(final RegionScheduleHandle other) {
            this.lastTickStart = other.lastTickStart;
            this.tickStats.copyFrom(other.tickStats);
            // Copy scheduled start - updateScheduledStartInternal also sets tickSchedule.lastPeriod correctly
            final long otherScheduledStart = other.getScheduledStart();
            final long otherLastPeriod = other.tickSchedule.getLastPeriod();
            this.updateScheduledStartInternal(otherScheduledStart);
            // Only copy the other's lastPeriod if it's a valid value (not Long.MIN_VALUE from uninitialized state)
            // Otherwise, updateScheduledStartInternal already set it correctly based on scheduledStart
            if (otherLastPeriod != SchedulerThreadPool.DEADLINE_NOT_SET) {
                this.tickSchedule.setLastPeriod(otherLastPeriod);
            }
        }

        void prepareForActivation() {
            final boolean wasNonSchedulable = this.cancelled.get();
            this.cancelled.set(false);

            // Always validate and fix tickSchedule to be consistent with scheduledStart
            final long currentLastPeriod = this.tickSchedule.getLastPeriod();
            final long currentScheduledStart = this.getScheduledStart();

            if (!this.hasScheduledDeadline()) {
                // No deadline set yet, create one
                this.updateScheduledStartInternal(System.nanoTime() + TICK_INTERVAL_NANOS);
                if (VERBOSE_LOGGING) {
                    LOGGER.info("[VERBOSE] prepareForActivation region {} - set new deadline (wasNonSchedulable={})",
                        this.region.id, wasNonSchedulable);
                }
            } else if (currentLastPeriod == SchedulerThreadPool.DEADLINE_NOT_SET) {
                // scheduledStart is set but tickSchedule.lastPeriod is still DEADLINE_NOT_SET (Long.MIN_VALUE)
                // This can happen if the handle was copied from an uninitialized template
                // Fix by setting lastPeriod based on the existing scheduledStart
                this.tickSchedule.setLastPeriod(currentScheduledStart - TICK_INTERVAL_NANOS);
                if (VERBOSE_LOGGING) {
                    LOGGER.info("[VERBOSE] prepareForActivation region {} - fixed uninitialized lastPeriod (wasNonSchedulable={}, scheduledStart={})",
                        this.region.id, wasNonSchedulable, currentScheduledStart);
                }
            } else if (VERBOSE_LOGGING) {
                LOGGER.info("[VERBOSE] prepareForActivation region {} - already has deadline (wasNonSchedulable={})",
                    this.region.id, wasNonSchedulable);
            }
        }

        void markNonSchedulable() {
            if (VERBOSE_LOGGING) {
                LOGGER.info("[VERBOSE] markNonSchedulable region {} (state={})",
                    this.region.id, this.region.getStateForDebug());
            }
            this.cancelled.set(true);
        }

        boolean isMarkedNonSchedulable() {
            return this.cancelled.get();
        }

        public boolean isCurrentlyTicking() {
            return this.currentlyTicking.get();
        }

        @Override
        public boolean runTick() {
            // Set the currently ticking flag to prevent race conditions with schedule/retire operations
            if (!this.currentlyTicking.compareAndSet(false, true)) {
                // Already being processed by another thread - this should not happen
                LOGGER.error("Region {} runTick called while already ticking!", this.region.id);
                return false;
            }

            try {
                return runTickInternal();
            } finally {
                this.currentlyTicking.set(false);
            }
        }

        private boolean runTickInternal() {
            if (VERBOSE_LOGGING) {
                LOGGER.info("[VERBOSE] runTick START region {} (state={}, nonSchedulable={}, chunks={})",
                    this.region.id, this.region.getStateForDebug(), this.isMarkedNonSchedulable(),
                    this.data.getChunks().size());
            }

            if (this.isMarkedNonSchedulable()) {
                if (VERBOSE_LOGGING) {
                    LOGGER.info("[VERBOSE] runTick region {} SKIP - marked non-schedulable", this.region.id);
                }
                LOGGER.debug("Region {} skipping tick - marked non-schedulable", this.region.id);
                return false;
            }

            final long tickStart = System.nanoTime();
            // If this is the first tick (never ticked before), use 1; otherwise calculate periods ahead
            final int tickCount = this.lastTickStart == SchedulerThreadPool.DEADLINE_NOT_SET
                ? 1 : Math.max(1, this.tickSchedule.getPeriodsAhead(TICK_INTERVAL_NANOS, tickStart));

            if (VERBOSE_LOGGING) {
                LOGGER.info("[VERBOSE] runTick region {} attempting tryMarkTicking (tickCount={}, lastTickStart={})",
                    this.region.id, tickCount, this.lastTickStart);
            }

            if (!this.tryMarkTicking()) {
                if (this.isMarkedNonSchedulable()) {
                    if (VERBOSE_LOGGING) {
                        LOGGER.info("[VERBOSE] runTick region {} became non-schedulable while trying to mark ticking", this.region.id);
                    }
                    LOGGER.debug("Region {} became non-schedulable while trying to mark ticking", this.region.id);
                    return false;
                }
                if (VERBOSE_LOGGING) {
                    LOGGER.info("[VERBOSE] runTick region {} tryMarkTicking FAILED (state={}) - will retry",
                        this.region.id, this.region.getStateForDebug());
                }
                LOGGER.error("Region {} could not acquire ticking state - this should not happen! Attempting to reschedule.", this.region.id);
                // Return true to try again later rather than stopping permanently
                return true;
            }

            if (VERBOSE_LOGGING) {
                LOGGER.info("[VERBOSE] runTick region {} tryMarkTicking SUCCESS - now TICKING", this.region.id);
            }

            final ServerWorld world = this.getWorld();
            final BooleanSupplier guard = () -> !this.isMarkedNonSchedulable();
            final RunningTick runningTick = this.scheduler == null ? null
                : this.scheduler.watchdog.track(world, this, Thread.currentThread(), tickStart);

            RegionDebug.log(RegionDebug.LogCategory.SCHEDULER,
                "Tick start region {} (world={}, chunks={})", this.region.id,
                world.getRegistryKey().getValue(), this.data.getChunks().size());

            this.scheduler.enterRegionContext(this.region, world, this);
            boolean success = false;
            long tickEnd = tickStart;
            boolean readyForNext = true;
            try {
                success = this.scheduler.tickRegion(this, guard);
                tickEnd = System.nanoTime();
                if (VERBOSE_LOGGING) {
                    LOGGER.info("[VERBOSE] runTick region {} tickRegion returned success={} (duration={}ms)",
                        this.region.id, success, nanosToMillis(tickEnd - tickStart));
                }
            } catch (final Throwable throwable) {
                LOGGER.error("CRITICAL: Region {} failed during tick with exception", this.region.id, throwable);
                RegionTickMonitor.getInstance().recordError(world, this.region.id);
                readyForNext = false;
                try {
                    this.scheduler.handleRegionFailure(this, throwable);
                } catch (final Throwable nested) {
                    LOGGER.error("CRITICAL: Failed to handle region failure for region {}", this.region.id, nested);
                }
                if (VERBOSE_LOGGING) {
                    LOGGER.info("[VERBOSE] runTick region {} EXCEPTION - returning false", this.region.id);
                }
                return false;
            } finally {
                if (runningTick != null) {
                    try {
                        this.scheduler.watchdog.untrack(runningTick);
                    } catch (final Throwable throwable) {
                        LOGGER.error("Failed to untrack watchdog for region {}", this.region.id, throwable);
                    }
                }
                try {
                    this.scheduler.exitRegionContext();
                } catch (final Throwable throwable) {
                    LOGGER.error("Failed to exit region context for region {}", this.region.id, throwable);
                }
                try {
                    this.region.markNotTicking();
                    if (VERBOSE_LOGGING) {
                        LOGGER.info("[VERBOSE] runTick region {} markNotTicking SUCCESS (state now={})",
                            this.region.id, this.region.getStateForDebug());
                    }
                } catch (final Throwable throwable) {
                    LOGGER.error("Failed to release region {} after tick - marking as non-schedulable", this.region.id, throwable);
                    this.cancelled.set(true);
                    readyForNext = false;
                }
            }

            if (!success) {
                if (VERBOSE_LOGGING) {
                    LOGGER.info("[VERBOSE] runTick region {} tickRegion returned false - will attempt reschedule", this.region.id);
                }
                LOGGER.warn("Region {} tick returned false - will attempt reschedule", this.region.id);
                // Still try to reschedule even if tick returned false
                return true;
            }

            this.lastTickStart = tickStart;
            final long duration = Math.max(0L, tickEnd - tickStart);
            this.tickStats.recordTickDuration(duration);
            RegionDebug.log(RegionDebug.LogCategory.SCHEDULER,
                "Tick end region {}: {} ms", this.region.id, (duration / 1_000_000.0D));

            long nextStart = tickEnd + TICK_INTERVAL_NANOS;
            try {
                final long lastPeriodBefore = this.tickSchedule.getLastPeriod();

                // Safety check: if lastPeriod is still DEADLINE_NOT_SET (Long.MIN_VALUE),
                // the schedule was never properly initialized. Fix it now.
                if (lastPeriodBefore == SchedulerThreadPool.DEADLINE_NOT_SET) {
                    LOGGER.warn("Region {} has uninitialized tickSchedule.lastPeriod - fixing now (tickStart={})",
                        this.region.id, tickStart);
                    this.tickSchedule.setLastPeriod(tickStart - TICK_INTERVAL_NANOS);
                }

                this.tickSchedule.advanceBy(tickCount, TICK_INTERVAL_NANOS);
                final long lastPeriodAfter = this.tickSchedule.getLastPeriod();
                final long scheduledDeadline = this.tickSchedule.getDeadline(TICK_INTERVAL_NANOS);

                // Protect against overflow - if the scheduled deadline looks invalid, use the fallback
                final long calculatedNext = TimeUtil.getGreatestTime(tickEnd, scheduledDeadline);

                // Sanity check: if the calculated next start is more than 60 seconds in the past
                // or more than 60 seconds in the future, something went wrong
                final long now = System.nanoTime();
                final long maxFuture = now + TimeUnit.SECONDS.toNanos(60L);
                final long maxPast = now - TimeUnit.SECONDS.toNanos(60L);

                if (calculatedNext < maxPast || calculatedNext > maxFuture) {
                    // Schedule is invalid, use fallback
                    LOGGER.warn("Region {} schedule calculation resulted in invalid value: calculatedNext={}, tickEnd={}, scheduledDeadline={}, lastPeriodBefore={}, lastPeriodAfter={} - using fallback",
                        this.region.id, calculatedNext, tickEnd, scheduledDeadline, lastPeriodBefore, lastPeriodAfter);
                    // Reset the schedule to a sane value
                    this.tickSchedule.setLastPeriod(tickEnd);
                    nextStart = tickEnd + TICK_INTERVAL_NANOS;
                } else {
                    nextStart = calculatedNext;
                }

                this.setScheduledStart(nextStart);
                final long delayMillis = TimeUnit.NANOSECONDS.toMillis(nextStart - now);
                if (VERBOSE_LOGGING) {
                    LOGGER.info("[VERBOSE] runTick region {} schedule updated: nextStart={}, delayMs={}, lastPeriod={}, deadline={}",
                        this.region.id, nextStart, delayMillis, lastPeriodAfter, scheduledDeadline);
                }
                RegionDebug.log(RegionDebug.LogCategory.SCHEDULER,
                    "Region {} rescheduled for {}ms from now (nextStart={})",
                    this.region.id, delayMillis, nextStart);
            } catch (final Throwable throwable) {
                LOGGER.error("Failed to update schedule for region {} - using fallback schedule", this.region.id, throwable);
                // Set a fallback schedule
                this.setScheduledStart(nextStart);
                if (VERBOSE_LOGGING) {
                    LOGGER.info("[VERBOSE] runTick region {} using fallback schedule due to exception", this.region.id);
                }
                // Still return true to keep trying
                return true;
            }

            final boolean willReschedule = readyForNext && !this.isMarkedNonSchedulable();
            if (!willReschedule) {
                if (VERBOSE_LOGGING) {
                    LOGGER.info("[VERBOSE] runTick region {} END - will NOT reschedule! (readyForNext={}, nonSchedulable={}, state={}, chunks={})",
                        this.region.id, readyForNext, this.isMarkedNonSchedulable(), this.region.getStateForDebug(), this.data.getChunks().size());
                }
                LOGGER.error("Region {} will NOT be rescheduled! (readyForNext={}, nonSchedulable={}, chunks={})",
                    this.region.id, readyForNext, this.isMarkedNonSchedulable(), this.data.getChunks().size());
            } else {
                if (VERBOSE_LOGGING) {
                    LOGGER.info("[VERBOSE] runTick region {} END - will reschedule (state={}, nextStart={}, tickCount={})",
                        this.region.id, this.region.getStateForDebug(), nextStart, tickCount);
                }
                RegionDebug.log(RegionDebug.LogCategory.SCHEDULER,
                    "Region {} will be rescheduled (nextStart={}, tickCount={})",
                    this.region.id, nextStart, tickCount);
            }
            return willReschedule;
        }

        @Override
        public boolean hasTasks() {
            return !this.data.getTaskQueue().isEmpty();
        }

        @Override
        public Boolean runTasks(final BooleanSupplier canContinue) {
            if (this.isMarkedNonSchedulable()) {
                return null;
            }
            final BooleanSupplier guard = () -> !this.isMarkedNonSchedulable() && canContinue.getAsBoolean();
            final TickRegionScheduler owningScheduler = Objects.requireNonNull(this.scheduler, "scheduler");
            final ServerWorld world = this.region == null ? null : this.getWorld();
            final long tickStart = System.nanoTime();
            final RunningTick runningTick = owningScheduler == null ? null
                : owningScheduler.watchdog.track(world, this, Thread.currentThread(), tickStart);
            final boolean enteredContext = this.region != null && world != null;
            if (enteredContext) {
                owningScheduler.enterRegionContext(this.region, world, this);
            }
            try {
                owningScheduler.runQueuedTasks(this.data, this.region, guard);
            } finally {
                if (enteredContext) {
                    owningScheduler.exitRegionContext();
                }
                if (runningTick != null) {
                    owningScheduler.watchdog.untrack(runningTick);
                }
            }
            return Boolean.TRUE;
        }

        private boolean tryMarkTicking() {
            return this.region.tryMarkTicking(this::isMarkedNonSchedulable);
        }

        public RegionTickStats getTickStats() {
            return this.tickStats;
        }

        private void updateScheduledStartInternal(final long scheduledStart) {
            this.setScheduledStart(scheduledStart);
            if (scheduledStart == SchedulerThreadPool.DEADLINE_NOT_SET) {
                // Don't set lastPeriod to DEADLINE_NOT_SET directly, it causes overflow
                // Instead, set it to a reasonable "not set" state by leaving it alone
                // or setting to current time minus interval
                final long now = System.nanoTime();
                this.tickSchedule.setLastPeriod(now - TICK_INTERVAL_NANOS);
            } else {
                this.tickSchedule.setLastPeriod(scheduledStart - TICK_INTERVAL_NANOS);
            }
        }

        private void onScheduledStartAdjusted(final long newStart) {
            if (newStart == SchedulerThreadPool.DEADLINE_NOT_SET) {
                // Same protection as above
                final long now = System.nanoTime();
                this.tickSchedule.setLastPeriod(now - TICK_INTERVAL_NANOS);
            } else {
                this.tickSchedule.setLastPeriod(newStart - TICK_INTERVAL_NANOS);
            }
        }
    }

    private record RegionDebugInfo(long regionId, int chunkCount, int pendingTasks, double lastTickMillis, long lastTickAgeMillis) {
        boolean isStalled() {
            return this.lastTickAgeMillis >= 0L && this.lastTickAgeMillis > REGION_TICK_STALL_MILLIS;
        }

        long ageMillis() {
            return this.lastTickAgeMillis;
        }

        double lastTickAgeSeconds() {
            return this.lastTickAgeMillis / 1000.0D;
        }
    }

    // Made package-visible for test access
    static final class LoggingOptions {

        private static final String LOG_FALLBACK = "ruthenium.scheduler.logFallback";
        private static final String LOG_FALLBACK_STACKS = "ruthenium.scheduler.logFallbackStackTraces";
        private static final String LOG_DRAINED_TASKS = "ruthenium.scheduler.logDrainedTasks";
        private static final String LOG_REGION_SUMMARIES = "ruthenium.scheduler.logRegionSummaries";
        private static final String LOG_TASK_QUEUE = "ruthenium.scheduler.logTaskQueueProcessing";

        private final boolean logFallbacks;
        private final boolean logFallbackStacks;
        private final boolean logDrainedTasks;
        private final boolean logRegionSummaries;
        private final boolean logTaskQueueProcessing;

        private LoggingOptions(final boolean logFallbacks,
                                final boolean logFallbackStacks,
                                final boolean logDrainedTasks,
                                final boolean logRegionSummaries,
                                final boolean logTaskQueueProcessing) {
            this.logFallbacks = logFallbacks;
            this.logFallbackStacks = logFallbackStacks;
            this.logDrainedTasks = logDrainedTasks;
            this.logRegionSummaries = logRegionSummaries;
            this.logTaskQueueProcessing = logTaskQueueProcessing;
        }

        static LoggingOptions load(Function<String, String> valueProvider) {
            Objects.requireNonNull(valueProvider, "propertyProvider");
            final boolean fallback = loadBoolean(valueProvider, LOG_FALLBACK, true);
            final boolean fallbackStacks = loadBoolean(valueProvider, LOG_FALLBACK_STACKS, false);
            final boolean drained = loadBoolean(valueProvider, LOG_DRAINED_TASKS, false);
            final boolean summaries = loadBoolean(valueProvider, LOG_REGION_SUMMARIES, false);
            final boolean taskQueue = loadBoolean(valueProvider, LOG_TASK_QUEUE, false);
            return new LoggingOptions(fallback, fallbackStacks, drained, summaries, taskQueue);
        }

        boolean logFallbacks() {
            return this.logFallbacks;
        }

        boolean logFallbackStacks() {
            return this.logFallbackStacks;
        }

        boolean logDrainedTasks() {
            return this.logDrainedTasks;
        }

        boolean logRegionSummaries() {
            return this.logRegionSummaries;
        }

        boolean logTaskQueueProcessing() {
            return this.logTaskQueueProcessing;
        }

        private static boolean loadBoolean(final Function<String, String> propertyProvider,
                                           final String key,
                                           final boolean defaultValue) {
            final String raw = propertyProvider.apply(key);
            if (raw == null || raw.isBlank()) {
                return defaultValue;
            }
            final String normalized = raw.trim().toLowerCase(Locale.ROOT);
            if ("true".equals(normalized)) {
                return true;
            }
            if ("false".equals(normalized)) {
                return false;
            }
            LOGGER.warn("Invalid boolean value '{}' for system property {}. Using default {}.", raw, key, defaultValue);
            return defaultValue;
        }
    }
}
