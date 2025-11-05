package org.bacon.ruthenium.world;

import ca.spottedleaf.concurrentutil.scheduler.SchedulerThreadPool;
import ca.spottedleaf.concurrentutil.scheduler.SchedulerThreadPool.SchedulableTick;
import ca.spottedleaf.concurrentutil.util.TimeUtil;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerChunkManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.GameRules;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bacon.ruthenium.debug.RegionDebug;
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
    private static final long TICK_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(1L) / 20L;
    private static final long WATCHDOG_WARN_NANOS = loadDuration("ruthenium.scheduler.watchdog.warnSeconds", 10L, TimeUnit.SECONDS);
    private static final long WATCHDOG_CRASH_NANOS = loadDuration("ruthenium.scheduler.watchdog.crashSeconds", 60L, TimeUnit.SECONDS);
    private static final long WATCHDOG_LOG_INTERVAL_NANOS = loadDuration("ruthenium.scheduler.watchdog.logIntervalSeconds", 5L, TimeUnit.SECONDS);
    private static final long WATCHDOG_POLL_INTERVAL_MILLIS = loadDurationMillis("ruthenium.scheduler.watchdog.pollMillis", 1000L);
    private static final long MAIN_THREAD_WARN_NANOS = loadDuration("ruthenium.scheduler.mainThread.warnMillis", 200L, TimeUnit.MILLISECONDS);
    private static final long MAIN_THREAD_CRASH_NANOS = loadDuration("ruthenium.scheduler.mainThread.crashSeconds", 60L, TimeUnit.SECONDS);

    private final SchedulerThreadPool scheduler;
    private final AtomicBoolean halted = new AtomicBoolean();
    private final RegionWatchdog watchdog;

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

    public static ThreadedRegion<RegionTickData, RegionTickData.RegionSectionData> getCurrentRegion() {
        return INSTANCE.currentRegion.get();
    }

    public static ServerWorld getCurrentWorld() {
        return INSTANCE.currentWorld.get();
    }

    public static RegionizedWorldData getCurrentWorldData() {
        return INSTANCE.currentWorldData.get();
    }

    public static RegionScheduleHandle getCurrentHandle() {
        return INSTANCE.currentHandle.get();
    }

    public boolean tickWorld(final ServerWorld world, final BooleanSupplier shouldKeepTicking) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(shouldKeepTicking, "shouldKeepTicking");

        if (this.halted.get()) {
            RegionDebug.log(RegionDebug.LogCategory.SCHEDULER,
                "Scheduler halted; falling back to vanilla world tick for {}", world.getRegistryKey().getValue());
            return false;
        }

        if (!shouldKeepTicking.getAsBoolean()) {
            RegionDebug.log(RegionDebug.LogCategory.SCHEDULER,
                "Server requested stop-ticking; allowing vanilla tick for {}", world.getRegistryKey().getValue());
            return false;
        }

        final ThreadedRegionizer<RegionTickData, RegionTickData.RegionSectionData> regionizer = requireRegionizer(world);
        final RegionizedWorldData worldData = ((RegionizedServerWorld)world).ruthenium$getWorldRegionData();
        final long tickStart = System.nanoTime();
        final RunningTick runningTick = this.watchdog.track(world, null, Thread.currentThread(), tickStart);
        worldData.beginTick(shouldKeepTicking);
        this.currentWorldData.set(worldData);
        boolean drainedTasks = false;
        boolean fallback = false;
        try {
            drainedTasks = drainRegionTasks(regionizer, world, shouldKeepTicking);
            if (this.halted.get()) {
                RegionDebug.log(RegionDebug.LogCategory.SCHEDULER,
                    "Scheduler halted mid-tick; falling back to vanilla world tick for {}", world.getRegistryKey().getValue());
                fallback = true;
                return false;
            }
            if (!shouldKeepTicking.getAsBoolean()) {
                RegionDebug.log(RegionDebug.LogCategory.SCHEDULER,
                    "Server requested stop-ticking during scheduler pump for {}", world.getRegistryKey().getValue());
                fallback = true;
                return false;
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

    public boolean updateTickStartToMax(final RegionScheduleHandle handle, final long newStart) {
        Objects.requireNonNull(handle, "handle");
        final boolean adjusted = this.scheduler.updateTickStartToMax(handle, newStart);
        if (adjusted) {
            handle.onScheduledStartAdjusted(newStart);
        }
        return adjusted;
    }

    public void notifyRegionTasks(final RegionScheduleHandle handle) {
        Objects.requireNonNull(handle, "handle");
        this.scheduler.notifyTasks(handle);
    }

    private boolean drainRegionTasks(final ThreadedRegionizer<RegionTickData, RegionTickData.RegionSectionData> regionizer,
                                     final ServerWorld world,
                                     final BooleanSupplier shouldKeepTicking) {
        boolean drainedAny = false;
        boolean continueDraining = true;
        while (continueDraining && shouldKeepTicking.getAsBoolean()) {
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
            RegionDebug.log(RegionDebug.LogCategory.SCHEDULER,
                "Drained pending region tasks on main thread for world {}", world.getRegistryKey().getValue());
        }
        return drainedAny;
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
        for (int i = 0; i < chunkSnapshot.length && guard.getAsBoolean(); ++i) {
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

        world.getRaidManager().tick(world);

        processedTasks += runQueuedTasks(data, region, guard);
        data.advanceCurrentTick();
        data.advanceRedstoneTick();
        RegionDebug.log(RegionDebug.LogCategory.SCHEDULER,
            "Region {} tick summary: chunksTicked={}, tasksProcessed={} (world={})",
            region.id, tickedChunks, processedTasks, world.getRegistryKey().getValue());
        return true;
    }

    private int runQueuedTasks(final RegionTickData data,
                                final ThreadedRegion<RegionTickData, RegionTickData.RegionSectionData> region,
                                final BooleanSupplier guard) {
        final RegionTaskQueue queue = data.getTaskQueue();
        int processed = 0;

        RegionTaskQueue.RegionChunkTask task;
        while (guard.getAsBoolean() && (task = queue.pollChunkTask()) != null) {
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
        if (processed > 0) {
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
        if (server != null) {
            RegionShutdownThread.requestShutdown(server, this);
        }
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
        if (server != null) {
            RegionShutdownThread.requestShutdown(server, this);
        }
    }

    private static double nanosToMillis(final long nanos) {
        return nanos / 1_000_000.0D;
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

    public static final class RegionScheduleHandle extends SchedulableTick {

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
            if (duration > 0L) {
                this.tickStats.recordTickDuration(duration);
            }
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
}
