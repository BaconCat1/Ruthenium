package org.bacon.ruthenium.region;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Fundamental regionizing logic inspired by Folia.
 *
 * @param <D> type of region local data stored by each {@link ThreadedRegion}
 */
public final class ThreadedRegionizer<D> {

    private static final Logger LOGGER = LogManager.getLogger(ThreadedRegionizer.class);

    private final RegionizerConfig config;
    private final RegionDataController<D> dataController;
    private final Map<RegionSectionPos, RegionSection> sections = new LinkedHashMap<>();
    private final Set<ThreadedRegion<D>> regions = new LinkedHashSet<>();
    private final Map<Long, ThreadedRegion<D>> regionIndex = new HashMap<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final AtomicLong idGenerator = new AtomicLong();

    /**
     * Creates a new regionizer.
     *
     * @param config        configuration describing how sections are grouped
     * @param dataController region data handler
     */
    public ThreadedRegionizer(final RegionizerConfig config, final RegionDataController<D> dataController) {
        this.config = Objects.requireNonNull(config, "config");
        this.dataController = Objects.requireNonNull(dataController, "dataController");
    }

    /**
     * @return configuration backing this regionizer.
     */
    public RegionizerConfig getConfig() {
        return this.config;
    }

    /**
     * Registers a chunk with the regionizer.
     *
     * @param chunkX chunk X coordinate
     * @param chunkZ chunk Z coordinate
     * @return region that owns the chunk after the operation completes
     */
    public ThreadedRegion<D> addChunk(final int chunkX, final int chunkZ) {
        this.lock.lock();
        try {
            final RegionSectionPos sectionPos = RegionSectionPos.fromChunk(chunkX, chunkZ, this.config.getSectionChunkShift());
            RegionSection section = this.sections.get(sectionPos);
            if (section != null) {
                final ThreadedRegion<D> owner = section.getOwner() == null ? null : castOwner(section.getOwner());
                if (section.hasChunks() || (owner != null && !owner.isTicking())) {
                    section.addChunk();
                    if (owner != null) {
                        reviveBufferAround(owner, sectionPos);
                        owner.markDirty();
                    }
                    LOGGER.debug("Added chunk to existing section {} owned by region {}", sectionPos, owner);
                    return owner;
                }
            }

            final List<RegionSection> createdSections = ensureBufferSections(sectionPos);
            section = this.sections.get(sectionPos);
            section.addChunk();
            final Set<ThreadedRegion<D>> neighbors = collectRegionsAround(sectionPos);
            LOGGER.debug("Chunk addition at {} touches regions {}", sectionPos, neighbors);

            final ThreadedRegion<D> targetRegion;
            if (neighbors.isEmpty()) {
                targetRegion = createRegion(createdSections, RegionState.READY, null);
            } else if (neighbors.size() == 1) {
                final ThreadedRegion<D> only = neighbors.iterator().next();
                if (only.isTicking()) {
                    targetRegion = createRegion(createdSections, RegionState.TRANSIENT, null);
                    scheduleMergeLater(targetRegion, only);
                } else {
                    targetRegion = only;
                    assignSections(targetRegion, createdSections);
                }
            } else {
                ThreadedRegion<D> primary = neighbors.stream().filter(r -> !r.isTicking()).findFirst().orElse(null);
                if (primary == null) {
                    primary = createRegion(createdSections, RegionState.TRANSIENT, null);
                } else {
                    assignSections(primary, createdSections);
                }
                for (final ThreadedRegion<D> other : neighbors) {
                    if (other == primary) {
                        continue;
                    }
                    if (other.isTicking()) {
                        scheduleMergeLater(primary, other);
                    } else {
                        mergeRegions(other, primary);
                    }
                }
                targetRegion = primary;
            }

            reviveBufferAround(targetRegion, sectionPos);
            targetRegion.markDirty();
            LOGGER.debug("Chunk at {} now owned by region {}", sectionPos, targetRegion);
            return targetRegion;
        } finally {
            this.lock.unlock();
        }
    }

    /**
     * Removes a chunk from the regionizer.
     *
     * @param chunkX chunk X coordinate
     * @param chunkZ chunk Z coordinate
     */
    public void removeChunk(final int chunkX, final int chunkZ) {
        this.lock.lock();
        try {
            final RegionSectionPos sectionPos = RegionSectionPos.fromChunk(chunkX, chunkZ, this.config.getSectionChunkShift());
            final RegionSection section = this.sections.get(sectionPos);
            if (section == null || section.getOwner() == null) {
                LOGGER.warn("Attempted to remove chunk from unassigned section {}", sectionPos);
                return;
            }
            section.removeChunk();
            final ThreadedRegion<D> owner = castOwner(section.getOwner());
            if (section.getChunkCount() == 0) {
                updateSectionDeathState(owner, section);
                owner.markDirty();
                refreshEmptyNeighbors(owner, section);
            }
            LOGGER.debug("Removed chunk from {} now {} chunks in section", sectionPos, section.getChunkCount());
        } finally {
            this.lock.unlock();
        }
    }

    /**
     * Attempts to move the region into the ticking state.
     *
     * @param region the region to schedule
     * @return {@code true} if the region was marked ticking
     */
    public boolean tryMarkTicking(final ThreadedRegion<D> region) {
        this.lock.lock();
        try {
            if (region.getState() != RegionState.READY) {
                LOGGER.debug("Region {} is not ready to tick (state={})", region, region.getState());
                return false;
            }
            region.setState(RegionState.TICKING);
            LOGGER.debug("Region {} entered ticking state", region);
            return true;
        } finally {
            this.lock.unlock();
        }
    }

    /**
     * Marks a region as no longer ticking and performs deferred maintenance.
     *
     * @param region region completing its tick
     * @return {@code true} if the region transitions back to {@link RegionState#READY}
     */
    public boolean markNotTicking(final ThreadedRegion<D> region) {
        this.lock.lock();
        try {
            if (region.getState() != RegionState.TICKING) {
                LOGGER.debug("Region {} is not ticking; ignoring markNotTicking", region);
                return false;
            }

            processPendingMerges(region);

            if (region.hasPendingOutgoing()) {
                region.setState(RegionState.TRANSIENT);
                LOGGER.debug("Region {} remains transient due to pending outgoing merges", region);
                return false;
            }

            pruneDeadSections(region);
            splitIfRequired(region);

            if (region.isEmpty()) {
                region.setState(RegionState.DEAD);
                this.regions.remove(region);
                this.regionIndex.remove(region.getId());
                LOGGER.debug("Region {} became dead after maintenance", region);
                return false;
            }

            region.setState(RegionState.READY);
            region.clearDirty();
            LOGGER.debug("Region {} returned to READY state", region);
            return true;
        } finally {
            this.lock.unlock();
        }
    }

    /**
     * @return immutable snapshot of the regions currently tracked.
     */
    public Set<ThreadedRegion<D>> snapshotRegions() {
        this.lock.lock();
        try {
            return Collections.unmodifiableSet(new LinkedHashSet<>(this.regions));
        } finally {
            this.lock.unlock();
        }
    }

    private void processPendingMerges(final ThreadedRegion<D> region) {
        for (final ThreadedRegion<D> incoming : region.drainPendingIncoming()) {
            if (incoming.getState() == RegionState.DEAD) {
                continue;
            }
            mergeRegions(incoming, region);
        }
    }

    private void scheduleMergeLater(final ThreadedRegion<D> from, final ThreadedRegion<D> intoTicking) {
        LOGGER.debug("Scheduling merge of region {} into ticking region {}", from, intoTicking);
        from.setState(RegionState.TRANSIENT);
        from.addPendingOutgoing(intoTicking);
        intoTicking.addPendingIncoming(from);
    }

    private void mergeRegions(final ThreadedRegion<D> source, final ThreadedRegion<D> target) {
        if (source == target) {
            return;
        }
        LOGGER.debug("Merging region {} into {}", source, target);
        final List<RegionSection> transfer = new ArrayList<>(source.getSections().values());
        for (final RegionSection section : transfer) {
            source.removeSection(section);
            target.addSection(section);
        }

        // Forward pending incoming merges.
        final Set<ThreadedRegion<D>> incoming = new LinkedHashSet<>(source.pendingIncomingInternal());
        source.pendingIncomingInternal().clear();
        for (final ThreadedRegion<D> waiting : incoming) {
            waiting.removePendingOutgoing(source);
            waiting.addPendingOutgoing(target);
            target.addPendingIncoming(waiting);
        }

        // Forward pending outgoing merges.
        for (final ThreadedRegion<D> waiting : new LinkedHashSet<>(source.pendingOutgoingInternal())) {
            waiting.pendingIncomingInternal().remove(source);
            if (waiting != target) {
                waiting.addPendingIncoming(target);
                target.addPendingOutgoing(waiting);
            }
        }
        source.pendingOutgoingInternal().clear();

        this.dataController.mergeData(target, target.getData(), source, source.getData());
        this.regions.remove(source);
        this.regionIndex.remove(source.getId());
        source.setState(RegionState.DEAD);
        LOGGER.debug("Region {} merged into {}", source, target);
    }

    private void pruneDeadSections(final ThreadedRegion<D> region) {
        if (!region.isDirty()) {
            return;
        }
        final List<RegionSection> toRemove = region.getSections().values().stream()
            .filter(RegionSection::isDead)
            .collect(Collectors.toList());
        for (final RegionSection section : toRemove) {
            region.removeSection(section);
            this.sections.remove(section.getPosition());
            LOGGER.debug("Removed dead section {} from region {}", section.getPosition(), region);
        }
    }

    private void splitIfRequired(final ThreadedRegion<D> region) {
        if (!region.isDirty()) {
            return;
        }
        final int sectionCount = region.sectionCount();
        if (sectionCount < this.config.getRecalculationSectionCount()) {
            return;
        }
        final long deadCount = region.getSections().values().stream().filter(RegionSection::isDead).count();
        if (deadCount > 0) {
            return; // dead sections handled before split
        }
        final double emptyRatio = region.getSections().values().stream().filter(s -> !s.hasChunks()).count() / (double) sectionCount;
        if (emptyRatio < this.config.getMaxDeadSectionPercent()) {
            return;
        }
        final List<Set<RegionSection>> components = findConnectedComponents(region);
        if (components.size() <= 1) {
            return;
        }
        LOGGER.debug("Splitting region {} into {} components", region, components.size());
        components.remove(0); // keep the first component in the existing region
        for (final Set<RegionSection> subset : components) {
            final D data = this.dataController.splitData(region, region.getData(), subset);
            final ThreadedRegion<D> newRegion = createRegion(Collections.emptyList(), RegionState.READY, data);
            for (final RegionSection section : subset) {
                region.removeSection(section);
                newRegion.addSection(section);
            }
            newRegion.markDirty();
            LOGGER.debug("Created new region {} from split", newRegion);
        }
        region.clearDirty();
    }

    private List<Set<RegionSection>> findConnectedComponents(final ThreadedRegion<D> region) {
        final Map<RegionSectionPos, RegionSection> sectionMap = new HashMap<>(region.getSections());
        final Set<RegionSectionPos> visited = new HashSet<>();
        final List<Set<RegionSection>> components = new ArrayList<>();

        for (final RegionSection section : sectionMap.values()) {
            if (visited.contains(section.getPosition())) {
                continue;
            }
            final Set<RegionSection> component = new LinkedHashSet<>();
            final Deque<RegionSection> stack = new ArrayDeque<>();
            stack.push(section);
            visited.add(section.getPosition());
            while (!stack.isEmpty()) {
                final RegionSection current = stack.pop();
                component.add(current);
                for (final RegionSection neighbor : getNeighborSections(sectionMap, current)) {
                    if (visited.add(neighbor.getPosition())) {
                        stack.push(neighbor);
                    }
                }
            }
            components.add(component);
        }
        return components;
    }

    private Collection<RegionSection> getNeighborSections(final Map<RegionSectionPos, RegionSection> sectionMap, final RegionSection section) {
        final Set<RegionSection> neighbors = new LinkedHashSet<>();
        final RegionSectionPos pos = section.getPosition();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                final RegionSection neighbor = sectionMap.get(new RegionSectionPos(pos.x() + dx, pos.z() + dz));
                if (neighbor != null) {
                    neighbors.add(neighbor);
                }
            }
        }
        return neighbors;
    }

    private List<RegionSection> ensureBufferSections(final RegionSectionPos center) {
        final int radius = this.config.getEmptySectionCreationRadius();
        final List<RegionSection> created = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                final RegionSectionPos pos = new RegionSectionPos(center.x() + dx, center.z() + dz);
                RegionSection section = this.sections.get(pos);
                if (section == null) {
                    section = new RegionSection(pos);
                    this.sections.put(pos, section);
                    created.add(section);
                } else if (section.getOwner() == null) {
                    created.add(section);
                }
            }
        }
        if (!created.contains(this.sections.get(center))) {
            created.add(this.sections.get(center));
        }
        return created;
    }

    private Set<ThreadedRegion<D>> collectRegionsAround(final RegionSectionPos center) {
        final int radius = this.config.getEmptySectionCreationRadius() + this.config.getMergeRadius();
        final Set<ThreadedRegion<D>> regions = new LinkedHashSet<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                final RegionSectionPos pos = new RegionSectionPos(center.x() + dx, center.z() + dz);
                final RegionSection section = this.sections.get(pos);
                if (section != null && section.getOwner() != null) {
                    regions.add(castOwner(section.getOwner()));
                }
            }
        }
        return regions;
    }

    private void assignSections(final ThreadedRegion<D> region, final Collection<RegionSection> newSections) {
        for (final RegionSection section : newSections) {
            final ThreadedRegion<?> owner = section.getOwner();
            if (owner == region) {
                continue;
            }
            moveSectionToRegion(section, region);
        }
        if (region.getData() == null) {
            region.setData(this.dataController.createData(region));
        }
        this.regions.add(region);
        this.regionIndex.put(region.getId(), region);
    }

    private ThreadedRegion<D> createRegion(final Collection<RegionSection> sections, final RegionState state, final D data) {
        final ThreadedRegion<D> region = new ThreadedRegion<>(this, this.idGenerator.incrementAndGet(), state);
        if (sections != null) {
            for (final RegionSection section : sections) {
                moveSectionToRegion(section, region);
            }
        }
        if (data != null) {
            region.setData(data);
        } else {
            region.setData(this.dataController.createData(region));
        }
        this.regions.add(region);
        this.regionIndex.put(region.getId(), region);
        LOGGER.debug("Created region {} with {} sections", region, region.sectionCount());
        return region;
    }

    private void reviveBufferAround(final ThreadedRegion<D> region, final RegionSectionPos pos) {
        final int radius = this.config.getEmptySectionCreationRadius();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                final RegionSection section = this.sections.get(new RegionSectionPos(pos.x() + dx, pos.z() + dz));
                if (section != null && section.getOwner() == region) {
                    section.setDead(false);
                }
            }
        }
    }

    private void refreshEmptyNeighbors(final ThreadedRegion<D> region, final RegionSection section) {
        final int radius = this.config.getEmptySectionCreationRadius();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                final RegionSection neighbor = this.sections.get(new RegionSectionPos(section.getPosition().x() + dx, section.getPosition().z() + dz));
                if (neighbor != null && neighbor.getOwner() == region && !neighbor.hasChunks()) {
                    updateSectionDeathState(region, neighbor);
                }
            }
        }
    }

    private void updateSectionDeathState(final ThreadedRegion<D> region, final RegionSection section) {
        if (section.hasChunks()) {
            section.setDead(false);
            return;
        }
        final int radius = this.config.getEmptySectionCreationRadius();
        boolean keepAlive = false;
        for (int dx = -radius; dx <= radius && !keepAlive; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                final RegionSection neighbor = this.sections.get(new RegionSectionPos(section.getPosition().x() + dx, section.getPosition().z() + dz));
                if (neighbor != null && neighbor.getOwner() == region && neighbor.hasChunks()) {
                    keepAlive = true;
                    break;
                }
            }
        }
        section.setDead(!keepAlive);
    }

    @SuppressWarnings("unchecked")
    private ThreadedRegion<D> castOwner(final ThreadedRegion<?> region) {
        return (ThreadedRegion<D>) region;
    }

    private void moveSectionToRegion(final RegionSection section, final ThreadedRegion<D> target) {
        final ThreadedRegion<?> currentOwner = section.getOwner();
        if (currentOwner != null && currentOwner != target) {
            final ThreadedRegion<D> owner = castOwner(currentOwner);
            owner.removeSection(section);
            owner.markDirty();
        }
        if (section.getOwner() != target) {
            target.addSection(section);
        }
    }
}
