package org.bacon.ruthenium.world;

import ca.spottedleaf.concurrentutil.scheduler.SchedulerThreadPool;
import ca.spottedleaf.concurrentutil.util.TimeUtil;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
    private static final long WATCHDOG_WARN_NANOS = loadDuration("ruthenium.scheduler.watchdog.warnSeconds", 10L, TimeUnit.SECONDS);
    private static final long WATCHDOG_CRASH_NANOS = loadDuration("ruthenium.scheduler.watchdog.crashSeconds", 60L, TimeUnit.SECONDS);
    private static final long WATCHDOG_LOG_INTERVAL_NANOS = loadDuration("ruthenium.scheduler.watchdog.logIntervalSeconds", 5L, TimeUnit.SECONDS);
    private static final long WATCHDOG_POLL_INTERVAL_MILLIS = loadDurationMillis("ruthenium.scheduler.watchdog.pollMillis", 1000L);
    private static final long MAIN_THREAD_WARN_NANOS = loadDuration("ruthenium.scheduler.mainThread.warnMillis", 200L, TimeUnit.MILLISECONDS);
    private static final long MAIN_THREAD_CRASH_NANOS = loadDuration("ruthenium.scheduler.mainThread.crashSeconds", 60L, TimeUnit.SECONDS);

    @SuppressWarnings("deprecation") // concurrentutil 0.0.3 only provides the deprecated SchedulerThreadPool implementation
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

    @SuppressWarnings("deprecation") // SchedulerThreadPool is deprecated without a replacement in concurrentutil 0.0.3
    private TickRegionScheduler() {
        final ThreadFactory threadFactory = runnable -> {
            final Thread thread = new Thread(runnable, "Ruthenium Region Thread #" + THREAD_ID.incrementAndGet());
            thread.setDaemon(true);
            thread.setUncaughtExceptionHandler((thr, throwable) -> LOGGER.error("Unhandled exception in {}", thr.getName(), throwable));
            return thread;
        };

        final int processorCount = Runtime.getRuntime().availableProcessors();
        final int targetThreads = Math.max(1, processorCount <= 4 ? 1 : processorCount / 2);
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
        if (this.halted.get()) {
            return this.logFallback(world, FallbackReason.SCHEDULER_HALTED_BEFORE_START,
                this.buildDiagnostics(world, "pre-check", false, false,
                    System.nanoTime() - invocationStart));
        }

        if (!shouldKeepTicking.getAsBoolean()) {
            this.logBudgetAbort(world, "pre-check");
        }

        final ThreadedRegionizer<RegionTickData, RegionTickData.RegionSectionData> regionizer = requireRegionizer(world);
        final RegionizedWorldData worldData = ((RegionizedServerWorld)world).ruthenium$getWorldRegionData();
        final long tickStart = System.nanoTime();
        final RunningTick runningTick = this.watchdog.track(world, null, Thread.currentThread(), tickStart);
        worldData.beginTick();
        this.currentWorldData.set(worldData);
        boolean drainedTasks = false;
        boolean drainAbortedByBudget = false;
        boolean fallback = false;
        try {
            final DrainResult drainResult = drainRegionTasks(regionizer, world, shouldKeepTicking);
            drainedTasks = drainResult.drainedAny();
            drainAbortedByBudget = drainResult.abortedByBudget();
            if (this.halted.get()) {
                fallback = true;
                return this.logFallback(world, FallbackReason.SCHEDULER_HALTED_DURING_TICK,
                    this.buildDiagnostics(world, "after-drain", drainedTasks, drainAbortedByBudget,
                        System.nanoTime() - tickStart));
            }
            if (!shouldKeepTicking.getAsBoolean()) {
                this.logBudgetAbort(world, "after-drain", drainedTasks, true);
            }
            worldData.populateChunkState(shouldKeepTicking);
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
        handle.prepareForActivation();
        RegionDebug.log(RegionDebug.LogCategory.SCHEDULER, "Schedule region {} in world {}",
            handle.getRegion().id, handle.getWorld().getRegistryKey().getValue());
        this.scheduler.schedule(handle);
    }

    public void descheduleRegion(final RegionScheduleHandle handle) {
        Objects.requireNonNull(handle, "handle");
        handle.markNonSchedulable();
        RegionDebug.log(RegionDebug.LogCategory.SCHEDULER, "Deschedule region {} in world {}",
            handle.getRegion().id, handle.getWorld().getRegistryKey().getValue());
        this.scheduler.tryRetire(handle);
    }

    @SuppressWarnings("unused") // retained for parity with Folia scheduling adjustments
    public boolean updateTickStartToMax(final RegionScheduleHandle handle, final long newStart) {
        Objects.requireNonNull(handle, "handle");
        final boolean adjusted = this.scheduler.updateTickStartToMax(handle, newStart);
        if (adjusted) {
            handle.onScheduledStartAdjusted(newStart);
        }
        return adjusted;
    }

    @SuppressWarnings("unused") // called by planned async scheduling hooks
    public void notifyRegionTasks(final RegionScheduleHandle handle) {
        Objects.requireNonNull(handle, "handle");
        this.scheduler.notifyTasks(handle);
    }

    private DrainResult drainRegionTasks(final ThreadedRegionizer<RegionTickData, RegionTickData.RegionSectionData> regionizer,
                                         final ServerWorld world,
                                         final BooleanSupplier shouldKeepTicking) {
        boolean drainedAny = false;
        boolean abortedByBudget = false;
        boolean continueDraining = true;
        while (continueDraining) {
            if (!shouldKeepTicking.getAsBoolean()) {
                abortedByBudget = true;
                break;
            }
            continueDraining = false;
            final boolean[] loopDrained = new boolean[1];
            regionizer.computeForAllRegions(region -> {
                final RegionScheduleHandle handle = region.getData().getScheduleHandle();
                if (!handle.hasTasks()) {
                    return;
                }
                final Boolean drained = handle.runTasks(() -> shouldKeepTicking.getAsBoolean());
                if (Boolean.TRUE.equals(drained)) {
                    loopDrained[0] = true;
                }
            });
            if (loopDrained[0]) {
                drainedAny = true;
                continueDraining = true;
            }
        }
        if (drainedAny) {
            if (LOGGING_OPTIONS.logDrainedTasks()) {
                LOGGER.info("Drained pending region tasks on main thread for world {}", world.getRegistryKey().getValue());
            }
            RegionDebug.log(RegionDebug.LogCategory.SCHEDULER,
                "Drained pending region tasks on main thread for world {}", world.getRegistryKey().getValue());
        }
        if (abortedByBudget) {
            this.logBudgetAbort(world, "drain-region-tasks", drainedAny, true);
        }
        return new DrainResult(drainedAny, abortedByBudget);
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
        regionizer.computeForAllRegions(region -> {
            if (!region.getData().getChunks().isEmpty()) {
                active[0] = true;
            }
        });
        return active[0];
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
        if (world instanceof RegionizedServerWorld regionized) {
            this.currentWorldData.set(regionized.ruthenium$getWorldRegionData());
        } else {
            this.currentWorldData.set(null);
        }
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
                continue;
            }
            final Chunk chunk = chunkManager.getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
            if (!(chunk instanceof WorldChunk worldChunk)) {
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
        if (LOGGING_OPTIONS.logRegionSummaries()) {
            LOGGER.info("Region {} tick summary: chunksTicked={}, tasksProcessed={}, lagComp={}ns (world={})",
                region.id, tickedChunks, processedTasks, lagCompTick, world.getRegistryKey().getValue());
        }
        RegionDebug.log(RegionDebug.LogCategory.SCHEDULER,
            "Region {} tick summary: chunksTicked={}, tasksProcessed={}, lagComp={}ns (world={})",
            region.id, tickedChunks, processedTasks, lagCompTick, world.getRegistryKey().getValue());
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

    private record DrainResult(boolean drainedAny, boolean abortedByBudget) {
    }

    private enum FallbackReason {
        SCHEDULER_HALTED_BEFORE_START("Scheduler halted before running world tick", true),
        SERVER_BUDGET_EXHAUSTED_BEFORE_START("Server requested stop-ticking before scheduler start", true),
        SCHEDULER_HALTED_DURING_TICK("Scheduler halted mid-tick", true),
        SERVER_BUDGET_EXHAUSTED_DURING_TICK("Server requested stop-ticking during scheduler pump", true);

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

    @SuppressWarnings("deprecation") // required while concurrentutil replaces SchedulableTick
    public static final class RegionScheduleHandle extends SchedulerThreadPool.SchedulableTick {

        private final TickRegionScheduler scheduler;
        private final RegionTickData data;
        private ThreadedRegion<RegionTickData, RegionTickData.RegionSectionData> region;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private long lastTickStart = SchedulerThreadPool.DEADLINE_NOT_SET;
        private final RegionTickStats tickStats = new RegionTickStats();
        private final Schedule tickSchedule;

        private RegionScheduleHandle(final TickRegionScheduler scheduler,
                                     final RegionTickData data,
                                     final ThreadedRegion<RegionTickData, RegionTickData.RegionSectionData> region) {
            this.scheduler = scheduler;
            this.data = data;
            this.region = region;
            this.tickSchedule = new Schedule(SchedulerThreadPool.DEADLINE_NOT_SET);
            this.updateScheduledStartInternal(SchedulerThreadPool.DEADLINE_NOT_SET);
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

        void attachRegion(final ThreadedRegion<RegionTickData, RegionTickData.RegionSectionData> newRegion) {
            this.region = Objects.requireNonNull(newRegion, "newRegion");
        }

        public void copyStateFrom(final RegionScheduleHandle other) {
            this.lastTickStart = other.lastTickStart;
            this.tickStats.copyFrom(other.tickStats);
            this.updateScheduledStartInternal(other.getScheduledStart());
            this.tickSchedule.setLastPeriod(other.tickSchedule.getLastPeriod());
        }

        void prepareForActivation() {
            this.cancelled.set(false);
            if (this.getScheduledStart() == SchedulerThreadPool.DEADLINE_NOT_SET) {
                this.updateScheduledStartInternal(System.nanoTime() + TICK_INTERVAL_NANOS);
            }
        }

        void markNonSchedulable() {
            this.cancelled.set(true);
        }

        boolean isMarkedNonSchedulable() {
            return this.cancelled.get();
        }

        @Override
        public boolean runTick() {
            if (this.isMarkedNonSchedulable()) {
                return false;
            }

            if (!this.tryMarkTicking()) {
                if (this.isMarkedNonSchedulable()) {
                    return false;
                }
                LOGGER.warn("Region {} could not acquire ticking state", this.region.id);
                return true;
            }

            final ServerWorld world = this.getWorld();
            final BooleanSupplier guard = () -> !this.isMarkedNonSchedulable();
            final long tickStart = System.nanoTime();
            final int tickCount;
            if (this.tickSchedule.getLastPeriod() == SchedulerThreadPool.DEADLINE_NOT_SET) {
                tickCount = 1;
            } else {
                tickCount = Math.max(1, this.tickSchedule.getPeriodsAhead(TICK_INTERVAL_NANOS, tickStart));
            }
            final RunningTick runningTick = this.scheduler == null ? null
                : this.scheduler.watchdog.track(world, this, Thread.currentThread(), tickStart);

            RegionDebug.log(RegionDebug.LogCategory.SCHEDULER,
                "Tick start region {} (world={}, chunks={})", this.region.id,
                world.getRegistryKey().getValue(), this.data.getChunks().size());

            this.scheduler.enterRegionContext(this.region, world, this);
            boolean success = false;
            long tickEnd = tickStart;
            try {
                success = this.scheduler.tickRegion(this, guard);
                tickEnd = System.nanoTime();
            } catch (final Throwable throwable) {
                this.scheduler.handleRegionFailure(this, throwable);
                return false;
            } finally {
                if (runningTick != null) {
                    this.scheduler.watchdog.untrack(runningTick);
                }
                this.scheduler.exitRegionContext();
                try {
                    this.region.markNotTicking();
                } catch (final Throwable throwable) {
                    LOGGER.error("Failed to release region {} after tick", this.region.id, throwable);
                    this.cancelled.set(true);
                }
            }

            if (!success) {
                return false;
            }

            this.lastTickStart = tickStart;
            final long duration = Math.max(0L, tickEnd - tickStart);
            this.tickStats.recordTickDuration(duration);
            RegionDebug.log(RegionDebug.LogCategory.SCHEDULER,
                "Tick end region {}: {} ms", this.region.id, (duration / 1_000_000.0D));
            this.tickSchedule.advanceBy(tickCount, TICK_INTERVAL_NANOS);
            final long scheduledDeadline = this.tickSchedule.getDeadline(TICK_INTERVAL_NANOS);
            final long nextStart = TimeUtil.getGreatestTime(tickEnd, scheduledDeadline);
            this.updateScheduledStartInternal(nextStart);
            return !this.isMarkedNonSchedulable();
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
            try {
                owningScheduler.runQueuedTasks(this.data, this.region, guard);
            } finally {
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
                this.tickSchedule.setLastPeriod(scheduledStart);
            } else {
                this.tickSchedule.setLastPeriod(scheduledStart - TICK_INTERVAL_NANOS);
            }
        }

        private void onScheduledStartAdjusted(final long newStart) {
            if (newStart == SchedulerThreadPool.DEADLINE_NOT_SET) {
                this.tickSchedule.setLastPeriod(newStart);
            } else {
                this.tickSchedule.setLastPeriod(newStart - TICK_INTERVAL_NANOS);
            }
        }
    }

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

        static LoggingOptions load(final Function<String, String> propertyProvider) {
            Objects.requireNonNull(propertyProvider, "propertyProvider");
            final boolean fallback = loadBoolean(propertyProvider, LOG_FALLBACK, true);
            final boolean fallbackStacks = loadBoolean(propertyProvider, LOG_FALLBACK_STACKS, false);
            final boolean drained = loadBoolean(propertyProvider, LOG_DRAINED_TASKS, false);
            final boolean summaries = loadBoolean(propertyProvider, LOG_REGION_SUMMARIES, false);
            final boolean taskQueue = loadBoolean(propertyProvider, LOG_TASK_QUEUE, false);
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
