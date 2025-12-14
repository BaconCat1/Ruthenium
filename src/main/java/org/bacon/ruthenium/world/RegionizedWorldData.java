package org.bacon.ruthenium.world;

import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2ReferenceOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.BooleanSupplier;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.fluid.Fluid;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerChunkLoadingManager;
import net.minecraft.server.world.ServerChunkManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.server.world.ChunkLevelManager;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.profiler.Profilers;
import net.minecraft.village.raid.RaidManager;
import net.minecraft.world.World;
import net.minecraft.world.chunk.BlockEntityTickInvoker;
import net.minecraft.world.tick.WorldTickScheduler;
import org.bacon.ruthenium.mixin.accessor.ServerChunkLoadingManagerAccessor;
import org.bacon.ruthenium.mixin.accessor.ServerChunkManagerAccessor;
import org.bacon.ruthenium.mixin.accessor.ServerWorldAccessor;
import org.bacon.ruthenium.util.CoordinateUtil;
import org.bacon.ruthenium.world.raid.RaidManagerThreadSafe;

/**
 * Minimal backing store for world-scoped data needed by the region scheduler. The implementation
 * mirrors the structure of Folia's {@code RegionizedWorldData} but intentionally limits the
 * maintained state to the pieces currently consumed by Ruthenium.
 */
public class RegionizedWorldData {

    private static final org.apache.logging.log4j.Logger LOGGER = org.apache.logging.log4j.LogManager.getLogger(RegionizedWorldData.class);

    private final ServerWorld world;
    private final Object chunkLock = new Object();
    private final LongSet tickingChunks = new LongOpenHashSet();
    private final LongSet entityTickingChunks = new LongOpenHashSet();
    private volatile boolean handlingTick;
    private volatile boolean tickAllowed;
    private volatile long lagCompensationTick;
    private long redstoneGameTime;
    private int wakeupInactiveRemainingAnimals;
    private int wakeupInactiveRemainingFlying;
    private int wakeupInactiveRemainingMonsters;
    private int wakeupInactiveRemainingVillagers;
    private final Object2LongMap<String> budgetWarningTicks = new Object2LongOpenHashMap<>();

    private final List<ServerPlayerEntity> players = new ArrayList<>();
    private final List<Entity> entities = new ArrayList<>();

    // Block event queue (note blocks, comparator updates, etc.)
    private final ObjectLinkedOpenHashSet<BlockEventData> blockEvents = new ObjectLinkedOpenHashSet<>();
    private final Object blockEventsLock = new Object();

    // Block entity tickers
    private final List<BlockEntityTickInvoker> blockEntityTickers = new ArrayList<>();
    private final List<BlockEntityTickInvoker> pendingBlockEntityTickers = new ArrayList<>();
    private final ReferenceOpenHashSet<BlockEntityTickInvoker> blockEntityTickerSet = new ReferenceOpenHashSet<>();
    private volatile boolean tickingBlockEntities = false;

    /**
     * Lock to synchronize chunk data access between region threads and main thread.
     * Region threads acquire read lock during chunk ticking (allows parallel ticking).
     * Main thread acquires write lock before broadcasting updates (blocks until regions complete).
     */
    private final ReentrantReadWriteLock chunkAccessLock = new ReentrantReadWriteLock();
    private static final long CHUNK_LOCK_TIMEOUT_MILLIS = 50L;

    /**
     * Creates a new world data wrapper for the supplied world.
     *
     * @param world backing server world
     */
    public RegionizedWorldData(final ServerWorld world) {
        this.world = Objects.requireNonNull(world, "world");
        this.redstoneGameTime = world.getTime();
        this.budgetWarningTicks.defaultReturnValue(Long.MIN_VALUE);
    }

    /**
     * Returns the world associated with this data object.
     *
     * @return backing server world
     */
    public ServerWorld getWorld() {
        return this.world;
    }

    /**
     * Indicates whether the world is currently in the middle of a regionized tick.
     *
     * @return {@code true} when a tick is being processed
     */
    public boolean isHandlingTick() {
        return this.handlingTick;
    }

    /**
     * Marks the world as actively handling a tick.
     *
     * @param handlingTick {@code true} when a tick is underway
     */
    public void setHandlingTick(final boolean handlingTick) {
        this.handlingTick = handlingTick;
    }

    /**
     * Returns whether the current tick allows world updates (mirrors {@link net.minecraft.world.tick.TickManager#shouldTick()}).
     */
    public boolean isTickAllowed() {
        return this.tickAllowed;
    }

    /**
     * Returns the last recorded lag compensation timestamp.
     *
     * @return nano time captured just before the latest tick
     */
    public long getLagCompensationTick() {
        return this.lagCompensationTick;
    }

    /**
     * Updates cached timing information so that region threads can apply simple lag compensation
     * when necessary. The value reflects the number of nanoseconds since the server booted.
     */
    public void updateTickData() {
        this.lagCompensationTick = System.nanoTime();
        this.redstoneGameTime++;
    }

    /**
     * Returns the redstone game time maintained for region ticks.
     *
     * @return cached redstone time
     */
    public long getRedstoneGameTime() {
        return this.redstoneGameTime;
    }

    /**
     * Resets per-tick mob wake-up budgets based on the supplied allowances.
     *
     * @param animals   budget for animals
     * @param monsters  budget for monsters
     * @param flying    budget for mobs that fly
     * @param villagers budget for villagers
     */
    public void resetMobWakeupBudgets(final int animals, final int monsters, final int flying, final int villagers) {
        this.wakeupInactiveRemainingAnimals = Math.max(0, animals);
        this.wakeupInactiveRemainingMonsters = Math.max(0, monsters);
        this.wakeupInactiveRemainingFlying = Math.max(0, flying);
        this.wakeupInactiveRemainingVillagers = Math.max(0, villagers);
    }

    /**
     * Attempts to consume the animal wake-up budget.
     *
     * @return {@code true} when the budget allowed the request
     */
    public boolean tryConsumeAnimalWakeBudget() {
        if (this.wakeupInactiveRemainingAnimals <= 0) {
            return false;
        }
        this.wakeupInactiveRemainingAnimals--;
        return true;
    }

    /**
     * Attempts to consume the monster wake-up budget.
     *
     * @return {@code true} when the budget allowed the request
     */
    public boolean tryConsumeMonsterWakeBudget() {
        if (this.wakeupInactiveRemainingMonsters <= 0) {
            return false;
        }
        this.wakeupInactiveRemainingMonsters--;
        return true;
    }

    /**
     * Attempts to consume the flying mob wake-up budget.
     *
     * @return {@code true} when the budget allowed the request
     */
    public boolean tryConsumeFlyingWakeBudget() {
        if (this.wakeupInactiveRemainingFlying <= 0) {
            return false;
        }
        this.wakeupInactiveRemainingFlying--;
        return true;
    }

    /**
     * Attempts to consume the villager wake-up budget.
     *
     * @return {@code true} when the budget allowed the request
     */
    public boolean tryConsumeVillagerWakeBudget() {
        if (this.wakeupInactiveRemainingVillagers <= 0) {
            return false;
        }
        this.wakeupInactiveRemainingVillagers--;
        return true;
    }

    /**
     * Marks a chunk as ticking within the current region state.
     *
     * @param chunkX chunk X coordinate
     * @param chunkZ chunk Z coordinate
     */
    public void addChunk(final int chunkX, final int chunkZ) {
        final long key = CoordinateUtil.getChunkKey(chunkX, chunkZ);
        synchronized (this.chunkLock) {
            this.tickingChunks.add(key);
        }
    }

    /**
     * Removes a chunk from both ticking sets.
     *
     * @param chunkX chunk X coordinate
     * @param chunkZ chunk Z coordinate
     */
    public void removeChunk(final int chunkX, final int chunkZ) {
        final long key = CoordinateUtil.getChunkKey(chunkX, chunkZ);
        synchronized (this.chunkLock) {
            this.tickingChunks.remove(key);
            this.entityTickingChunks.remove(key);
        }
    }

    /**
     * Marks a chunk as entity-ticking.
     *
     * @param chunkX chunk X coordinate
     * @param chunkZ chunk Z coordinate
     */
    public void markEntityTickingChunk(final int chunkX, final int chunkZ) {
        final long key = CoordinateUtil.getChunkKey(chunkX, chunkZ);
        synchronized (this.chunkLock) {
            this.entityTickingChunks.add(key);
        }
    }

    /**
     * Removes a chunk from the entity-ticking set.
     *
     * @param chunkX chunk X coordinate
     * @param chunkZ chunk Z coordinate
     */
    public void unmarkEntityTickingChunk(final int chunkX, final int chunkZ) {
        final long key = CoordinateUtil.getChunkKey(chunkX, chunkZ);
        synchronized (this.chunkLock) {
            this.entityTickingChunks.remove(key);
        }
    }

    /**
     * Produces a snapshot of the currently ticking chunk keys.
     *
     * @return array containing ticking chunk keys
     */
    public long[] snapshotTickingChunks() {
        synchronized (this.chunkLock) {
            return this.tickingChunks.toLongArray();
        }
    }

    /**
     * Produces a snapshot of chunks that are ticking entities.
     *
     * @return array containing entity ticking chunk keys
     */
    public long[] snapshotEntityTickingChunks() {
        synchronized (this.chunkLock) {
            return this.entityTickingChunks.toLongArray();
        }
    }

    /**
     * Returns whether the supplied chunk should tick blocks according to the most recent
     * orchestrator snapshot.
     */
    public boolean shouldTickBlocksInChunk(final int chunkX, final int chunkZ) {
        final long key = CoordinateUtil.getChunkKey(chunkX, chunkZ);
        synchronized (this.chunkLock) {
            return this.tickingChunks.contains(key);
        }
    }

    /**
     * Returns whether the supplied chunk should tick entities according to the most recent
     * orchestrator snapshot.
     */
    public boolean shouldTickEntitiesInChunk(final int chunkX, final int chunkZ) {
        final long key = CoordinateUtil.getChunkKey(chunkX, chunkZ);
        synchronized (this.chunkLock) {
            return this.entityTickingChunks.contains(key);
        }
    }

    /**
     * Ticks pending world services that must continue to execute on the orchestrator thread to
     * keep the world responsive.
     */
    public void tickGlobalServices() {
        // this.tickConnections(); // Regionized
        if (this.tickAllowed) {
            this.tickWorldBorder();
            this.tickWeather();
            this.tickTime();
            // Scheduled ticks are now regionized - processed on region threads
            // this.tickScheduledTickSchedulers();
            this.tickRaids();
        }
        this.updateSleepingPlayers();
        this.resetMobWakeupBudgets(4, 8, 2, 4);
    }

    public void tickRegionServices() {
        this.tickConnections();
    }

    /**
     * Prepares the world for a new tick, updating timing data and running global services.
     */
    public void beginTick() {
        this.setHandlingTick(true);
        this.tickAllowed = this.world.getTickManager().shouldTick();
        this.updateTickData();
        this.tickGlobalServices();
    }

    /**
     * Marks the completion of a world tick.
     */
    public void finishTick() {
        this.setHandlingTick(false);
    }

    /**
     * Acquires the read lock for chunk access. Region threads call this before ticking chunks.
     * Multiple region threads can hold the read lock simultaneously for parallel ticking.
     */
    public void acquireChunkReadLock() {
        this.chunkAccessLock.readLock().lock();
    }

    /**
     * Releases the read lock for chunk access. Region threads call this after ticking chunks.
     */
    public void releaseChunkReadLock() {
        this.chunkAccessLock.readLock().unlock();
    }

    /**
     * Attempts to acquire the write lock for chunk access with a timeout.
     * Main thread calls this before broadcasting chunk updates to players.
     * This blocks until all region threads release their read locks.
     *
     * @return {@code true} if the lock was acquired, {@code false} if timeout was reached
     */
    public boolean tryAcquireChunkWriteLock() {
        try {
            return this.chunkAccessLock.writeLock().tryLock(CHUNK_LOCK_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warn("Interrupted while waiting for chunk write lock", e);
            return false;
        }
    }

    /**
     * Releases the write lock for chunk access. Main thread calls this after broadcasting updates.
     */
    public void releaseChunkWriteLock() {
        this.chunkAccessLock.writeLock().unlock();
    }

    /**
     * Returns whether the chunk access write lock is held by the current thread.
     */
    public boolean isChunkWriteLockHeld() {
        return this.chunkAccessLock.isWriteLockedByCurrentThread();
    }

    private void tickConnections() {
        final List<ServerPlayerEntity> players = List.copyOf(this.players); // avoids CME when handlers disconnect players
        for (final ServerPlayerEntity player : players) {
            final ServerPlayNetworkHandler networkHandler = player.networkHandler;
            if (networkHandler != null) {
                networkHandler.tick();
            }
        }
    }

    public void addPlayer(final ServerPlayerEntity player) {
        this.players.add(player);
    }

    public void removePlayer(final ServerPlayerEntity player) {
        this.players.remove(player);
    }

    public void addEntity(final Entity entity) {
        this.entities.add(entity);
    }

    public void removeEntity(final Entity entity) {
        this.entities.remove(entity);
    }

    public void merge(final RegionizedWorldData other) {
        this.players.addAll(other.players);
        this.entities.addAll(other.entities);
        synchronized (this.chunkLock) {
            this.tickingChunks.addAll(other.tickingChunks);
            this.entityTickingChunks.addAll(other.entityTickingChunks);
        }
        // Merge block events
        synchronized (this.blockEventsLock) {
            synchronized (other.blockEventsLock) {
                this.blockEvents.addAll(other.blockEvents);
            }
        }
        // Merge block entity tickers
        for (final BlockEntityTickInvoker ticker : other.blockEntityTickers) {
            this.addBlockEntityTicker(ticker);
        }
        for (final BlockEntityTickInvoker ticker : other.pendingBlockEntityTickers) {
            this.addBlockEntityTicker(ticker);
        }
    }

    public void split(final int chunkToRegionShift,
                      final Long2ReferenceOpenHashMap<RegionizedWorldData> regionToData,
                      final ReferenceOpenHashSet<RegionizedWorldData> dataSet) {
        for (final ServerPlayerEntity player : this.players) {
            final ChunkPos pos = player.getChunkPos();
            final long regionKey = CoordinateUtil.getChunkKey(pos.x >> chunkToRegionShift, pos.z >> chunkToRegionShift);
            final RegionizedWorldData target = regionToData.get(regionKey);
            if (target != null) {
                target.addPlayer(player);
            }
        }
        for (final Entity entity : this.entities) {
            final ChunkPos pos = entity.getChunkPos();
            final long regionKey = CoordinateUtil.getChunkKey(pos.x >> chunkToRegionShift, pos.z >> chunkToRegionShift);
            final RegionizedWorldData target = regionToData.get(regionKey);
            if (target != null) {
                target.addEntity(entity);
            }
        }
        synchronized (this.chunkLock) {
            final LongIterator iterator = this.tickingChunks.iterator();
            while (iterator.hasNext()) {
                final long chunkKey = iterator.nextLong();
                final int chunkX = CoordinateUtil.getChunkX(chunkKey);
                final int chunkZ = CoordinateUtil.getChunkZ(chunkKey);
                final long regionKey = CoordinateUtil.getChunkKey(chunkX >> chunkToRegionShift, chunkZ >> chunkToRegionShift);
                final RegionizedWorldData target = regionToData.get(regionKey);
                if (target != null) {
                    target.addChunk(chunkX, chunkZ);
                }
            }
            final LongIterator entityIterator = this.entityTickingChunks.iterator();
            while (entityIterator.hasNext()) {
                final long chunkKey = entityIterator.nextLong();
                final int chunkX = CoordinateUtil.getChunkX(chunkKey);
                final int chunkZ = CoordinateUtil.getChunkZ(chunkKey);
                final long regionKey = CoordinateUtil.getChunkKey(chunkX >> chunkToRegionShift, chunkZ >> chunkToRegionShift);
                final RegionizedWorldData target = regionToData.get(regionKey);
                if (target != null) {
                    target.markEntityTickingChunk(chunkX, chunkZ);
                }
            }
        }
        this.players.clear();
        this.entities.clear();
        synchronized (this.chunkLock) {
            this.tickingChunks.clear();
            this.entityTickingChunks.clear();
        }

        // Split block events by position
        synchronized (this.blockEventsLock) {
            for (final BlockEventData blockEventData : this.blockEvents) {
                final int chunkX = blockEventData.chunkX();
                final int chunkZ = blockEventData.chunkZ();
                final long regionKey = CoordinateUtil.getChunkKey(chunkX >> chunkToRegionShift, chunkZ >> chunkToRegionShift);
                final RegionizedWorldData target = regionToData.get(regionKey);
                if (target != null) {
                    target.pushBlockEvent(blockEventData);
                }
            }
            this.blockEvents.clear();
        }

        // Split block entity tickers by position
        for (final BlockEntityTickInvoker ticker : this.blockEntityTickers) {
            final BlockPos pos = ticker.getPos();
            if (pos != null) {
                final int chunkX = pos.getX() >> 4;
                final int chunkZ = pos.getZ() >> 4;
                final long regionKey = CoordinateUtil.getChunkKey(chunkX >> chunkToRegionShift, chunkZ >> chunkToRegionShift);
                final RegionizedWorldData target = regionToData.get(regionKey);
                if (target != null) {
                    target.addBlockEntityTicker(ticker);
                }
            }
        }
        for (final BlockEntityTickInvoker ticker : this.pendingBlockEntityTickers) {
            final BlockPos pos = ticker.getPos();
            if (pos != null) {
                final int chunkX = pos.getX() >> 4;
                final int chunkZ = pos.getZ() >> 4;
                final long regionKey = CoordinateUtil.getChunkKey(chunkX >> chunkToRegionShift, chunkZ >> chunkToRegionShift);
                final RegionizedWorldData target = regionToData.get(regionKey);
                if (target != null) {
                    target.addBlockEntityTicker(ticker);
                }
            }
        }
        this.blockEntityTickers.clear();
        this.pendingBlockEntityTickers.clear();
        this.blockEntityTickerSet.clear();
    }

    /**
     * Refreshes ticking chunk snapshots so that region threads operate on up-to-date data.
     *
     * @param shouldKeepTicking supplier used to abort work when deadlines are exceeded
     */
    public void populateChunkState(final BooleanSupplier shouldKeepTicking) {
        final ServerChunkManager chunkManager = this.world.getChunkManager();
        final ServerChunkLoadingManager loadingManager = ((ServerChunkManagerAccessor)chunkManager).ruthenium$getChunkLoadingManager();

        /*
         * We cannot call ServerChunkManager.tick(..., false) and then run broadcastUpdates /
         * tickEntityMovement afterwards: the vanilla tick ordering runs tickEntityMovement
         * before ServerChunkLoadingManager.tick(...), and reordering can corrupt internal
         * chunk-system queues (seen as LongLinkedOpenHashSet.removeFirstLong crashes).
         *
         * Instead, run the required sub-steps in vanilla order while still avoiding chunk ticking.
         *
         * broadcastUpdates() reads PalettedContainer data to send chunk updates to players.
         * We must acquire the write lock to ensure no region threads are modifying chunks.
         */
        if (this.tryAcquireChunkWriteLock()) {
            try {
                ((ServerChunkManagerAccessor)chunkManager).ruthenium$getTicketManager().tick(loadingManager);
                ((ServerChunkManagerAccessor)chunkManager).ruthenium$invokeUpdateChunks();
                if (!this.world.isDebugWorld()) {
                    ((ServerChunkManagerAccessor)chunkManager).ruthenium$invokeBroadcastUpdates(Profilers.get());
                    ((ServerChunkLoadingManagerAccessor)loadingManager).ruthenium$invokeTickEntityMovement();
                }
                ((ServerChunkLoadingManagerAccessor)loadingManager).ruthenium$invokeTick(shouldKeepTicking);
                ((ServerChunkManagerAccessor)chunkManager).ruthenium$invokeInitChunkCaches();
            } finally {
                this.releaseChunkWriteLock();
            }
        } else {
            // Could not acquire lock - skip broadcast this tick to avoid blocking
            LOGGER.debug("Skipped chunk updates for {} - region threads are still ticking chunks",
                this.world.getRegistryKey().getValue());
        }

        final LongOpenHashSet newTicking = new LongOpenHashSet();
        ((ServerChunkLoadingManagerAccessor)loadingManager).ruthenium$forEachBlockTickingChunk(chunk -> {
            final ChunkPos pos = chunk.getPos();
            newTicking.add(CoordinateUtil.getChunkKey(pos.x, pos.z));
        });

        final ChunkLevelManager levelManager = loadingManager.getLevelManager();
        synchronized (this.chunkLock) {
            this.tickingChunks.clear();
            final LongIterator tickingIterator = newTicking.iterator();
            while (tickingIterator.hasNext()) {
                this.tickingChunks.add(tickingIterator.nextLong());
            }

            this.entityTickingChunks.clear();
            final LongIterator entityIterator = newTicking.iterator();
            while (entityIterator.hasNext()) {
                final long chunkKey = entityIterator.nextLong();
                final int chunkX = CoordinateUtil.getChunkX(chunkKey);
                final int chunkZ = CoordinateUtil.getChunkZ(chunkKey);
                if (levelManager.shouldTickEntities(ChunkPos.toLong(chunkX, chunkZ))) {
                    this.entityTickingChunks.add(chunkKey);
                }
            }
        }

        this.refreshMobWakeBudgets(newTicking.size());
    }

    private void refreshMobWakeBudgets(final int tickingChunkCount) {
        final int base = Math.max(1, tickingChunkCount / 16);
        final int monsters = Math.max(base * 2, 1);
        final int animals = Math.max(base, 1);
        final int flying = Math.max(base / 2, 1);
        final int villagers = Math.max(base / 2, 1);
        this.resetMobWakeupBudgets(animals, monsters, flying, villagers);
    }

    private void tickWorldBorder() {
        this.world.getWorldBorder().tick();
    }

    private void tickWeather() {
        ((ServerWorldAccessor)this.world).ruthenium$invokeTickWeather();
    }

    private void updateSleepingPlayers() {
        ((ServerWorldAccessor)this.world).ruthenium$invokeUpdateSleepingPlayers();
    }

    private void tickRaids() {
        final RaidManager raidManager = this.world.getRaidManager();
        if (raidManager instanceof RaidManagerThreadSafe threadSafe) {
            threadSafe.ruthenium$globalTick();
        } else {
            raidManager.tick(this.world);
        }
    }

    private void tickTime() {
        ((ServerWorldAccessor)this.world).ruthenium$invokeTickTime();
    }

    private void tickScheduledTickSchedulers() {
        if (this.world.isDebugWorld()) {
            return;
        }
        final long time = this.world.getTime();
        final ServerWorldAccessor accessor = (ServerWorldAccessor)this.world;
        final WorldTickScheduler<Block> blockTicks = this.world.getBlockTickScheduler();
        blockTicks.tick(time, 65536, accessor::ruthenium$invokeTickBlock);
        final WorldTickScheduler<Fluid> fluidTicks = this.world.getFluidTickScheduler();
        fluidTicks.tick(time, 65536, accessor::ruthenium$invokeTickFluid);
    }

    /**
     * Removes chunks from the entity ticking set once the server reports that their tasks drained.
     *
     * @param chunks iterable containing drained chunks
     */
    public void notifyChunkListDrained(final Iterable<ChunkPos> chunks) {
        synchronized (this.chunkLock) {
            for (final ChunkPos pos : chunks) {
                this.entityTickingChunks.remove(CoordinateUtil.getChunkKey(pos.x, pos.z));
            }
        }
    }

    // ========== Block Event Queue Methods ==========

    /**
     * Adds a block event to this region's queue.
     *
     * @param blockEventData the block event to queue
     */
    public void pushBlockEvent(final BlockEventData blockEventData) {
        synchronized (this.blockEventsLock) {
            this.blockEvents.add(blockEventData);
        }
    }

    /**
     * Adds a block event using raw parameters.
     *
     * @param pos        position of the block event
     * @param block      block type
     * @param eventId    event ID
     * @param eventParam event parameter
     */
    public void pushBlockEvent(final BlockPos pos, final Block block, final int eventId, final int eventParam) {
        this.pushBlockEvent(new BlockEventData(pos, block, eventId, eventParam));
    }

    /**
     * Adds multiple block events to this region's queue.
     *
     * @param events collection of block events to add
     */
    public void pushBlockEvents(final Collection<? extends BlockEventData> events) {
        synchronized (this.blockEventsLock) {
            for (final BlockEventData event : events) {
                this.blockEvents.add(event);
            }
        }
    }

    /**
     * Removes and returns the first block event from the queue, or null if empty.
     *
     * @return the first block event, or null
     */
    public BlockEventData removeFirstBlockEvent() {
        synchronized (this.blockEventsLock) {
            if (this.blockEvents.isEmpty()) {
                return null;
            }
            return this.blockEvents.removeFirst();
        }
    }

    /**
     * Checks if there are any block events pending.
     *
     * @return true if the block events queue is not empty
     */
    public boolean hasBlockEvents() {
        synchronized (this.blockEventsLock) {
            return !this.blockEvents.isEmpty();
        }
    }

    /**
     * Returns the current size of the block events queue.
     *
     * @return number of pending block events
     */
    public int getBlockEventCount() {
        synchronized (this.blockEventsLock) {
            return this.blockEvents.size();
        }
    }

    /**
     * Processes block events for this region. This should be called from region threads.
     * Mirrors Folia's runBlockEvents() implementation.
     */
    public void processBlockEvents() {
        final List<BlockEventData> toReschedule = new ArrayList<>(64);

        synchronized (this.blockEventsLock) {
            while (!this.blockEvents.isEmpty()) {
                final BlockEventData event = this.blockEvents.removeFirst();
                if (this.doBlockEvent(event)) {
                    toReschedule.add(event);
                }
            }
            // Re-add events that need to be rescheduled (e.g., pistons)
            for (final BlockEventData event : toReschedule) {
                this.blockEvents.add(event);
            }
        }
    }

    /**
     * Executes a single block event.
     *
     * @param event the block event to execute
     * @return true if the event should be rescheduled
     */
    private boolean doBlockEvent(final BlockEventData event) {
        final BlockState state = this.world.getBlockState(event.pos());
        return state.isOf(event.block()) &&
               state.onSyncedBlockEvent((World)(Object)this.world, event.pos(), event.eventId(), event.eventParam());
    }

    // ========== Block Entity Ticker Methods ==========

    /**
     * Adds a block entity ticker to this region.
     * If we're currently ticking block entities, it goes to the pending list.
     *
     * @param ticker the block entity ticker to add
     */
    public void addBlockEntityTicker(final BlockEntityTickInvoker ticker) {
        if (ticker == null) {
            return;
        }
        if (!this.blockEntityTickerSet.add(ticker)) {
            return;
        }
        if (this.tickingBlockEntities) {
            this.pendingBlockEntityTickers.add(ticker);
        } else {
            this.blockEntityTickers.add(ticker);
        }
    }

    /**
     * Returns the list of block entity tickers for this region.
     * Used by the region tick loop to iterate and tick block entities.
     *
     * @return the list of block entity tickers
     */
    public List<BlockEntityTickInvoker> getBlockEntityTickers() {
        return this.blockEntityTickers;
    }

    /**
     * Splices pending block entity tickers into the main list.
     * Should be called after finishing block entity ticking for this tick.
     */
    public void splicePendingBlockEntityTickers() {
        if (!this.pendingBlockEntityTickers.isEmpty()) {
            this.blockEntityTickers.addAll(this.pendingBlockEntityTickers);
            this.pendingBlockEntityTickers.clear();
        }
    }

    /**
     * Marks the start of block entity ticking. New tickers will go to the pending list.
     */
    public void setTickingBlockEntities(final boolean ticking) {
        this.tickingBlockEntities = ticking;
    }

    /**
     * Returns whether we're currently ticking block entities.
     *
     * @return true if block entity ticking is in progress
     */
    public boolean isTickingBlockEntities() {
        return this.tickingBlockEntities;
    }

    /**
     * Ticks all block entities for this region.
     * Should be called from region threads.
     */
    public void tickBlockEntities() {
        this.setTickingBlockEntities(true);
        try {
            final boolean tickAllowed = this.world.getTickManager().shouldTick();
            final List<BlockEntityTickInvoker> toRemove = new ArrayList<>();
            for (int i = 0; i < this.blockEntityTickers.size(); i++) {
                final BlockEntityTickInvoker ticker = this.blockEntityTickers.get(i);
                if (ticker.isRemoved()) {
                    toRemove.add(ticker);
                    continue;
                }
                if (!tickAllowed) {
                    continue;
                }

                final BlockPos pos = ticker.getPos();
                if (pos == null) {
                    continue;
                }

                if (this.world instanceof RegionizedServerWorld regionized) {
                    final RegionizedWorldData tickView = regionized.ruthenium$getWorldRegionData();
                    if (!tickView.shouldTickBlocksInChunk(pos.getX() >> 4, pos.getZ() >> 4)) {
                        continue;
                    }
                }

                ticker.tick();
            }
            this.blockEntityTickers.removeAll(toRemove);
            for (final BlockEntityTickInvoker removed : toRemove) {
                this.blockEntityTickerSet.remove(removed);
            }
        } finally {
            this.setTickingBlockEntities(false);
            this.splicePendingBlockEntityTickers();
        }
    }

    /**
     * Returns the count of block entity tickers in this region.
     *
     * @return number of block entity tickers
     */
    public int getBlockEntityTickerCount() {
        return this.blockEntityTickers.size() + this.pendingBlockEntityTickers.size();
    }

    /**
     * Determines whether the supplied scheduler budget warning should be logged.
     *
     * @param stage diagnostic stage requesting logging
     * @return {@code true} if the warning should be emitted for the current tick
     */
    public boolean shouldLogBudgetWarning(final String stage) {
        if (stage == null) {
            return true;
        }
        synchronized (this.budgetWarningTicks) {
            final long currentTick = this.world.getTime();
            final long lastTick = this.budgetWarningTicks.getLong(stage);
            if (lastTick == currentTick) {
                return false;
            }
            this.budgetWarningTicks.put(stage, currentTick);
            return true;
        }
    }
}
