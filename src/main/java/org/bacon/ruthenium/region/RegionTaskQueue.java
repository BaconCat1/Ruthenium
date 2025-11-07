package org.bacon.ruthenium.region;

import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Simple task queue that keeps per-chunk runnables associated with a region.
 *
 * <p>This is a deliberately straightforward implementation that mirrors the
 * bookkeeping performed by Folia: chunk bound tasks remain with whichever
 * region currently owns the chunk, moving across merges and splits alongside
 * the chunk metadata.</p>
 */
public final class RegionTaskQueue {

    private final Deque<RegionChunkTask> chunkTasks = new LinkedList<>();

    /**
     * Creates an empty queue with no pending region tasks.
     */
    public RegionTaskQueue() {
    }

    /**
     * Queues a runnable that should execute when the owning region next ticks
     * the specified chunk. The runnable will execute at most once.
     *
     * @param chunkX  chunk X coordinate
     * @param chunkZ  chunk Z coordinate
     * @param task    work to execute during the region tick
     */
    public void queueChunkTask(final int chunkX, final int chunkZ, final Runnable task) {
        Objects.requireNonNull(task, "task");
        synchronized (this) {
            this.chunkTasks.addLast(new RegionChunkTask(chunkX, chunkZ, task));
        }
    }

    /**
     * Retrieves and removes the next pending chunk task, or {@code null} when
     * no chunk tasks remain.
     *
     * @return the next chunk task
     */
    public RegionChunkTask pollChunkTask() {
        synchronized (this) {
            return this.chunkTasks.pollFirst();
        }
    }

    /**
     * Determines whether any tasks remain in the queue.
     *
     * @return {@code true} when no pending chunk tasks remain
     */
    public boolean isEmpty() {
        synchronized (this) {
            return this.chunkTasks.isEmpty();
        }
    }

    /**
     * Removes all queued tasks.
     */
    public void clear() {
        synchronized (this) {
            this.chunkTasks.clear();
        }
    }

    /**
     * Checks whether a task targeting the provided chunk is currently queued.
     * Primarily intended for unit tests.
     *
     * @param chunkX chunk X coordinate
     * @param chunkZ chunk Z coordinate
     * @return {@code true} if at least one task targets the chunk
     */
    public boolean containsTask(final int chunkX, final int chunkZ) {
        synchronized (this) {
            for (final RegionChunkTask task : this.chunkTasks) {
                if (task.chunkX == chunkX && task.chunkZ == chunkZ) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Moves all tasks from {@code other} into this queue, draining the source
     * queue in the process.
     *
     * @param other queue to merge
     */
    public void absorb(final RegionTaskQueue other) {
        Objects.requireNonNull(other, "other");
        final List<RegionChunkTask> transfer = new ArrayList<>();
        synchronized (other) {
            transfer.addAll(other.chunkTasks);
            other.chunkTasks.clear();
        }
        synchronized (this) {
            this.chunkTasks.addAll(transfer);
        }
    }

    /**
     * Copies all tasks from this queue into the supplied target without
     * modifying the current queue. The copy shares runnable references, which
     * is acceptable because tasks execute at most once.
     *
     * @param target queue receiving the copy
     */
    public void copyInto(final RegionTaskQueue target) {
        Objects.requireNonNull(target, "target");
        final List<RegionChunkTask> snapshot;
        synchronized (this) {
            snapshot = new ArrayList<>(this.chunkTasks);
        }
        synchronized (target) {
            target.chunkTasks.addAll(snapshot);
        }
    }

    /**
     * Extracts tasks whose chunks fall within the supplied section set,
     * returning them in a new queue.
     *
     * @param sectionPositions sections being reassigned to another region
     * @param sectionChunkShift section shift used for chunk to section mapping
     * @return a queue containing the transferred tasks
     */
    public RegionTaskQueue splitForSections(final Set<RegionSectionPos> sectionPositions,
                                            final int sectionChunkShift) {
        Objects.requireNonNull(sectionPositions, "sectionPositions");
        final Set<RegionSectionPos> lookup = new HashSet<>(sectionPositions);
        final List<RegionChunkTask> moved = new ArrayList<>();
        synchronized (this) {
            final Iterator<RegionChunkTask> iterator = this.chunkTasks.iterator();
            while (iterator.hasNext()) {
                final RegionChunkTask task = iterator.next();
                final RegionSectionPos sectionPos =
                    RegionSectionPos.fromChunk(task.chunkX, task.chunkZ, sectionChunkShift);
                if (lookup.contains(sectionPos)) {
                    moved.add(task);
                    iterator.remove();
                }
            }
        }
        final RegionTaskQueue result = new RegionTaskQueue();
        synchronized (result) {
            result.chunkTasks.addAll(moved);
        }
        return result;
    }

    /**
     * Encapsulates a chunk bound task along with its target coordinates.
     */
    public static final class RegionChunkTask {

        private final int chunkX;
        private final int chunkZ;
        private final Runnable runnable;

        private RegionChunkTask(final int chunkX, final int chunkZ, final Runnable runnable) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.runnable = runnable;
        }

        /**
         * Returns the chunk X coordinate targeted by this task.
         *
         * @return chunk X coordinate
         */
        public int chunkX() {
            return this.chunkX;
        }

        /**
         * Returns the chunk Z coordinate targeted by this task.
         *
         * @return chunk Z coordinate
         */
        public int chunkZ() {
            return this.chunkZ;
        }

        /**
         * Returns the runnable that should execute when the owning region ticks the chunk.
         *
         * @return runnable scheduled for execution
         */
        public Runnable runnable() {
            return this.runnable;
        }
    }
}
