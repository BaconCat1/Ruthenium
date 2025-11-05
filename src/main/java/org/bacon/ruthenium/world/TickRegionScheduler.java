package org.bacon.ruthenium.world;

import ca.spottedleaf.concurrentutil.scheduler.SchedulerThreadPool;
import ca.spottedleaf.concurrentutil.scheduler.SchedulerThreadPool.SchedulableTick;
import ca.spottedleaf.concurrentutil.util.TimeUtil;
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
import org.bacon.ruthenium.world.RegionizedServerWorld;

/**
 * Port of Folia's TickRegionScheduler adapted for Ruthenium.
 */
public final class TickRegionScheduler {

    private static final Logger LOGGER = LogManager.getLogger(TickRegionScheduler.class);
    private static final AtomicInteger THREAD_ID = new AtomicInteger();
    private static final TickRegionScheduler INSTANCE = new TickRegionScheduler();
    private static final long TICK_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(1L) / 20L;

    private final SchedulerThreadPool scheduler;
    private final AtomicBoolean halted = new AtomicBoolean();

    private final ThreadLocal<ThreadedRegion<RegionTickData, RegionTickData.RegionSectionData>> currentRegion = new ThreadLocal<>();
    private final ThreadLocal<ServerWorld> currentWorld = new ThreadLocal<>();
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

    public static RegionScheduleHandle getCurrentHandle() {
        return INSTANCE.currentHandle.get();
    }

    public boolean tickWorld(final ServerWorld world, final BooleanSupplier shouldKeepTicking) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(shouldKeepTicking, "shouldKeepTicking");
        requireRegionizer(world);
        // If the scheduler has been halted (shutdown or fatal error), fall back to vanilla ticking.
        if (this.halted.get()) {
            RegionDebug.log(RegionDebug.LogCategory.SCHEDULER, "Scheduler halted; falling back to vanilla world tick for {}", world.getRegistryKey().getValue());
            return false;
        }

        // If the server indicates it should stop ticking (shutdown path), allow vanilla/main-thread shutdown to proceed.
        if (!shouldKeepTicking.getAsBoolean()) {
            RegionDebug.log(RegionDebug.LogCategory.SCHEDULER, "Server requested stop-ticking; allowing vanilla tick for {}", world.getRegistryKey().getValue());
            return false;
        }

        // At this stage, the scheduler is active and the server wants to keep ticking. The region scheduler
        // will drive chunk ticks on region threads; signal the mixin to skip vanilla per-chunk ticking.
        // Note: finer-grained pumping (handling pending main-thread tasks) will be implemented later
        // once scheduler APIs for main-thread task draining are available.
        return true;
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
        this.scheduler.halt(true, TimeUnit.SECONDS.toNanos(5L));
    }

    void enterRegionContext(final ThreadedRegion<RegionTickData, RegionTickData.RegionSectionData> region,
                            final ServerWorld world, final RegionScheduleHandle handle) {
        this.currentRegion.set(region);
        this.currentWorld.set(world);
        this.currentHandle.set(handle);
    }

    void exitRegionContext() {
        this.currentHandle.remove();
        this.currentWorld.remove();
        this.currentRegion.remove();
    }

    boolean tickRegion(final RegionScheduleHandle handle, final BooleanSupplier guard) {
        final ThreadedRegion<RegionTickData, RegionTickData.RegionSectionData> region = handle.getRegion();
        final RegionTickData data = handle.getData();
        final ServerWorld world = region.regioniser.world;

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
            try {
                ((ServerWorldAccessor)world).ruthenium$invokeTickChunk(worldChunk, randomTickSpeed);
                tickedChunks++;
            } catch (final Throwable throwable) {
                LOGGER.error("Failed to tick chunk {} in region {}", new ChunkPos(chunkX, chunkZ), region.id, throwable);
            } finally {
                ((RegionChunkTickAccess)world).ruthenium$popRegionChunkTick();
            }
        }

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
        handle.markNonSchedulable();
    }

    private ThreadedRegionizer<RegionTickData, RegionTickData.RegionSectionData> requireRegionizer(final ServerWorld world) {
        if (!(world instanceof RegionizedServerWorld regionized)) {
            throw new IllegalStateException("World " + world + " is missing RegionizedServerWorld support");
        }
        return regionized.ruthenium$getRegionizer();
    }

    public static final class RegionScheduleHandle extends SchedulableTick {

        private final TickRegionScheduler scheduler;
        private final RegionTickData data;
        private ThreadedRegion<RegionTickData, RegionTickData.RegionSectionData> region;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private long lastTickStart = SchedulerThreadPool.DEADLINE_NOT_SET;
        private final RegionTickStats tickStats = new RegionTickStats();

        private RegionScheduleHandle(final TickRegionScheduler scheduler,
                                     final RegionTickData data,
                                     final ThreadedRegion<RegionTickData, RegionTickData.RegionSectionData> region) {
            this.scheduler = scheduler;
            this.data = data;
            this.region = region;
            this.setScheduledStart(SchedulerThreadPool.DEADLINE_NOT_SET);
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
        }

        void prepareForActivation() {
            this.cancelled.set(false);
            if (this.getScheduledStart() == SchedulerThreadPool.DEADLINE_NOT_SET) {
                this.setScheduledStart(System.nanoTime() + TICK_INTERVAL_NANOS);
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
            final long nextStart = TimeUtil.getGreatestTime(tickStart + TICK_INTERVAL_NANOS, tickEnd);
            this.setScheduledStart(nextStart);
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
            this.scheduler.runQueuedTasks(this.data, this.region, guard);
            return Boolean.TRUE;
        }

        private boolean tryMarkTicking() {
            return this.region.tryMarkTicking(this::isMarkedNonSchedulable);
        }

        public RegionTickStats getTickStats() {
            return this.tickStats;
        }
    }
}
