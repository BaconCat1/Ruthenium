package org.bacon.ruthenium.region;

import it.unimi.dsi.fastutil.longs.Long2ReferenceMap;
import it.unimi.dsi.fastutil.longs.Long2ReferenceOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.server.world.ServerWorld;
import org.bacon.ruthenium.util.CoordinateUtil;
import org.bacon.ruthenium.world.RegionTickStats;
import org.bacon.ruthenium.world.RegionizedServerWorld;
import org.bacon.ruthenium.world.RegionizedWorldData;
import org.bacon.ruthenium.world.RegionTaskDispatcher;
import org.bacon.ruthenium.world.TickRegionScheduler;

/**
 * Simple region data container that stores per-region tick counters.
 */
public final class RegionTickData implements ThreadedRegionizer.ThreadedRegionData<RegionTickData, RegionTickData.RegionSectionData> {

    private final TickRegionScheduler scheduler;
    private ThreadedRegionizer.ThreadedRegion<RegionTickData, RegionSectionData> region;
    private TickRegionScheduler.RegionScheduleHandle scheduleHandle;
    private long currentTick;
    private long redstoneTick;
    private final LongSet chunks = new LongOpenHashSet();
    private final RegionTaskQueue taskQueue = new RegionTaskQueue();
    private final RegionizedWorldData worldData;

    /**
     * Creates a new region tick data instance using the global scheduler.
     */
    public RegionTickData(final ServerWorld world) {
        this(TickRegionScheduler.getInstance(), world);
    }

    private RegionTickData(final TickRegionScheduler scheduler, final ServerWorld world) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.worldData = new RegionizedWorldData(world);
    }

    public RegionizedWorldData getWorldData() {
        return this.worldData;
    }

    /**
     * Returns the current tick counter recorded for the region.
     *
     * @return the current tick counter for the region
     */
    public long getCurrentTick() {
        return this.currentTick;
    }

    /**
     * Returns the redstone tick counter maintained for the region.
     *
     * @return the redstone tick counter for the region
     */
    public long getRedstoneTick() {
        return this.redstoneTick;
    }

    /**
     * Advances the current tick counter by one.
     */
    public void advanceCurrentTick() {
        this.currentTick++;
    }

    /**
     * Advances the redstone tick counter by one.
     */
    public void advanceRedstoneTick() {
        this.redstoneTick++;
    }

    /**
     * Applies an offset to both counters so that they align with another region's time line.
     *
     * @param currentOffset  offset to apply to the current tick counter
     * @param redstoneOffset offset to apply to the redstone tick counter
     */
    public void applyOffset(final long currentOffset, final long redstoneOffset) {
        this.currentTick += currentOffset;
        this.redstoneTick += redstoneOffset;
    }

    /**
     * Integrates the provided data into this instance, keeping the most up-to-date counters.
     *
     * @param other the data instance to merge
     */
    public void absorb(final RegionTickData other) {
        this.worldData.merge(other.worldData);
        this.currentTick = Math.max(this.currentTick, other.currentTick);
        this.redstoneTick = Math.max(this.redstoneTick, other.redstoneTick);
        this.chunks.addAll(other.chunks);
        this.taskQueue.absorb(other.taskQueue);
    }

    /**
     * Creates a deep copy of this data object.
     *
     * @return the copy
     */
    public RegionTickData copy() {
        final RegionTickData copy = new RegionTickData(this.scheduler);
        copy.currentTick = this.currentTick;
        copy.redstoneTick = this.redstoneTick;
        copy.chunks.addAll(this.chunks);
        this.taskQueue.copyInto(copy.taskQueue);
        return copy;
    }

    /**
     * Attaches this data object to the supplied region and scheduler.
     *
     * @param region    region receiving the data
     * @param scheduler scheduler that owns the region
     */
    public void attachRegion(final ThreadedRegionizer.ThreadedRegion<RegionTickData, RegionSectionData> region,
                              final TickRegionScheduler scheduler) {
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(scheduler, "scheduler");
        if (scheduler != this.scheduler) {
            throw new IllegalStateException("Mismatched scheduler instance");
        }
        this.region = region;
        this.scheduleHandle = scheduler.createHandle(this, region, this.scheduleHandle);
    }

    /**
     * Returns the scheduling handle associated with this region.
     *
     * @return active schedule handle
     * @throws IllegalStateException when the handle has not been initialised
     */
    public TickRegionScheduler.RegionScheduleHandle getScheduleHandle() {
        if (this.scheduleHandle == null) {
            throw new IllegalStateException("Region schedule handle has not been initialised");
        }
        return this.scheduleHandle;
    }

    /**
     * Rebuilds the schedule handle when the region configuration changes.
     */
    public void refreshScheduleHandle() {
        if (this.region == null) {
            return;
        }
        this.scheduleHandle = this.scheduler.createHandle(this, this.region, this.scheduleHandle);
    }

    /**
     * Registers a chunk with the region and corresponding world data.
     *
     * @param chunkX chunk X coordinate
     * @param chunkZ chunk Z coordinate
     */
    public void addChunk(final int chunkX, final int chunkZ) {
        this.chunks.add(encodeChunk(chunkX, chunkZ));
        final RegionizedWorldData worldData = this.resolveWorldData();
        if (worldData != null) {
            worldData.addChunk(chunkX, chunkZ);
        }
        if (this.region != null) {
            RegionTaskDispatcher.flushPendingChunkTasks(this.region.regioniser.world, chunkX, chunkZ, this);
        }
    }

    /**
     * Removes a chunk from the region and synchronises the world data accordingly.
     *
     * @param chunkX chunk X coordinate
     * @param chunkZ chunk Z coordinate
     */
    public void removeChunk(final int chunkX, final int chunkZ) {
        this.chunks.remove(encodeChunk(chunkX, chunkZ));
        final RegionizedWorldData worldData = this.resolveWorldData();
        if (worldData != null) {
            worldData.removeChunk(chunkX, chunkZ);
        }
    }

    /**
     * Returns the set of chunk keys owned by the region.
     *
     * @return chunk key set
     */
    public LongSet getChunks() {
        return this.chunks;
    }

    /**
     * Determines whether the region owns the supplied chunk coordinates.
     *
     * @param chunkX chunk X coordinate
     * @param chunkZ chunk Z coordinate
     * @return {@code true} when the chunk is present
     */
    public boolean containsChunk(final int chunkX, final int chunkZ) {
        return this.chunks.contains(encodeChunk(chunkX, chunkZ));
    }

    /**
     * Returns the queue used to coordinate tasks scheduled against this region.
     *
     * @return queue used to coordinate tasks scheduled against this region
     */
    public RegionTaskQueue getTaskQueue() {
        return this.taskQueue;
    }

    /**
     * Provides access to the rolling tick statistics maintained by the region's schedule handle.
     *
     * @return the stats tracker or {@code null} when the handle has not been initialised
     */
    public RegionTickStats getTickStats() {
        if (this.scheduleHandle == null) {
            return null;
        }
        return this.scheduleHandle.getTickStats();
    }

    /** {@inheritDoc} */
    @Override
    public void split(final ThreadedRegionizer<RegionTickData, RegionSectionData> regioniser,
                      final Long2ReferenceOpenHashMap<ThreadedRegionizer.ThreadedRegion<RegionTickData, RegionSectionData>> into,
                      final ReferenceOpenHashSet<ThreadedRegionizer.ThreadedRegion<RegionTickData, RegionSectionData>> regions) {
        final int sectionShift = regioniser.sectionChunkShift;

        final Long2ReferenceOpenHashMap<RegionizedWorldData> regionToData = new Long2ReferenceOpenHashMap<>(into.size());
        for (final Long2ReferenceMap.Entry<ThreadedRegionizer.ThreadedRegion<RegionTickData, RegionSectionData>> entry : into.long2ReferenceEntrySet()) {
             if (entry.getValue() != null) {
                 regionToData.put(entry.getLongKey(), entry.getValue().getData().getWorldData());
             }
        }
        
        final ReferenceOpenHashSet<RegionizedWorldData> dataSet = new ReferenceOpenHashSet<>(regions.size());
        for (final ThreadedRegionizer.ThreadedRegion<RegionTickData, RegionSectionData> region : regions) {
            dataSet.add(region.getData().getWorldData());
        }

        this.worldData.split(sectionShift, regionToData, dataSet);

        final Map<Long, Set<RegionSectionPos>> sectionsByRegion = new HashMap<>();
        for (final Long2ReferenceMap.Entry<ThreadedRegionizer.ThreadedRegion<RegionTickData, RegionSectionData>> entry : into.long2ReferenceEntrySet()) {
            final long sectionKey = entry.getLongKey();
            final ThreadedRegionizer.ThreadedRegion<RegionTickData, RegionSectionData> region = entry.getValue();
            if (region == null) {
                continue;
            }
            sectionsByRegion.computeIfAbsent(region.id, ignored -> new HashSet<>())
                .add(new RegionSectionPos(CoordinateUtil.getChunkX(sectionKey), CoordinateUtil.getChunkZ(sectionKey)));
        }

        final Long2ReferenceOpenHashMap<LongArrayList> chunksByRegion = new Long2ReferenceOpenHashMap<>(regions.size());
        for (final ThreadedRegionizer.ThreadedRegion<RegionTickData, RegionSectionData> region : regions) {
            chunksByRegion.put(region.id, new LongArrayList());
            final RegionTickData targetData = region.getData();
            targetData.currentTick = this.currentTick;
            targetData.redstoneTick = this.redstoneTick;
            if (this.scheduleHandle != null && targetData.scheduleHandle != null) {
                targetData.scheduleHandle.copyStateFrom(this.scheduleHandle);
            }
        }

        final LongIterator iterator = this.chunks.iterator();
        while (iterator.hasNext()) {
            final long chunkKey = iterator.nextLong();
            final int sectionX = CoordinateUtil.getChunkX(chunkKey) >> sectionShift;
            final int sectionZ = CoordinateUtil.getChunkZ(chunkKey) >> sectionShift;
            final long sectionKey = CoordinateUtil.getChunkKey(sectionX, sectionZ);
            final ThreadedRegionizer.ThreadedRegion<RegionTickData, RegionSectionData> targetRegion = into.get(sectionKey);
            if (targetRegion == null) {
                continue;
            }
            final LongArrayList list = chunksByRegion.get(targetRegion.id);
            if (list != null) {
                list.add(chunkKey);
                iterator.remove();
            }
        }

        for (final ThreadedRegionizer.ThreadedRegion<RegionTickData, RegionSectionData> region : regions) {
            final RegionTickData targetData = region.getData();
            final LongArrayList movedChunks = chunksByRegion.get(region.id);
            if (movedChunks != null) {
                for (int i = 0, len = movedChunks.size(); i < len; ++i) {
                    targetData.chunks.add(movedChunks.getLong(i));
                }
            }

            final Set<RegionSectionPos> positions = sectionsByRegion.get(region.id);
            if (positions != null && !positions.isEmpty()) {
                final RegionTaskQueue transferred = this.taskQueue.splitForSections(positions, sectionShift);
                targetData.taskQueue.absorb(transferred);
            }
        }

        this.taskQueue.clear();
        this.chunks.clear();
    }

    /** {@inheritDoc} */
    @Override
    public void mergeInto(final ThreadedRegionizer.ThreadedRegion<RegionTickData, RegionSectionData> into) {
        final RegionTickData targetData = into.getData();
        targetData.getWorldData().merge(this.worldData);
        targetData.currentTick = Math.max(targetData.currentTick, this.currentTick);
        targetData.redstoneTick = Math.max(targetData.redstoneTick, this.redstoneTick);
        targetData.chunks.addAll(this.chunks);
        this.chunks.clear();
        targetData.taskQueue.absorb(this.taskQueue);
        if (this.scheduleHandle != null && targetData.scheduleHandle != null) {
            targetData.scheduleHandle.copyStateFrom(this.scheduleHandle);
        }
    }

    /**
     * Marker object containing per-section state for the threaded regionizer.
     */
    public static final class RegionSectionData implements ThreadedRegionizer.ThreadedRegionSectionData {}

    /**
     * Extracts the chunk X coordinate from a packed chunk key.
     *
     * @param chunkKey packed chunk key
     * @return chunk X coordinate
     */
    public static int decodeChunkX(final long chunkKey) {
        return (int)(chunkKey & 0xFFFFFFFFL);
    }

    /**
     * Extracts the chunk Z coordinate from a packed chunk key.
     *
     * @param chunkKey packed chunk key
     * @return chunk Z coordinate
     */
    public static int decodeChunkZ(final long chunkKey) {
        return (int)((chunkKey >>> 32) & 0xFFFFFFFFL);
    }

    private static long encodeChunk(final int chunkX, final int chunkZ) {
        return (chunkX & 0xFFFFFFFFL) | ((chunkZ & 0xFFFFFFFFL) << 32);
    }

    private RegionizedWorldData resolveWorldData() {
        if (this.region == null) {
            return null;
        }
        final ServerWorld world = this.region.regioniser.world;
        if (!(world instanceof RegionizedServerWorld regionized)) {
            return null;
        }
        return regionized.ruthenium$getWorldRegionData();
    }
}
