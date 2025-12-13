package org.bacon.ruthenium.mixin;

import it.unimi.dsi.fastutil.longs.Long2ObjectFunction;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSortedSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import net.minecraft.util.annotation.Debug;
import net.minecraft.util.function.LazyIterationConsumer;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.world.entity.EntityLike;
import net.minecraft.world.entity.EntityTrackingSection;
import net.minecraft.world.entity.EntityTrackingStatus;
import net.minecraft.world.entity.SectionedEntityCache;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

@Mixin(SectionedEntityCache.class)
public abstract class SectionedEntityCacheMixin<T extends EntityLike> {

    @Shadow
    @Final
    private Class<T> entityClass;

    @Shadow
    @Final
    private Long2ObjectFunction<EntityTrackingStatus> posToStatus;

    @Shadow
    @Final
    private Long2ObjectMap<EntityTrackingSection<T>> trackingSections;

    @Shadow
    @Final
    private LongSortedSet trackedPositions;

    @Unique
    private final Object ruthenium$lock = new Object();

    @Shadow
    private static long chunkPosFromSectionPos(long sectionPos) {
        throw new AssertionError();
    }

    @Overwrite
    public void forEachInBox(final Box box, final LazyIterationConsumer<EntityTrackingSection<T>> consumer) {
        final int minSectionX = ChunkSectionPos.getSectionCoord(box.minX - 2.0);
        final int minSectionY = ChunkSectionPos.getSectionCoord(box.minY - 4.0);
        final int minSectionZ = ChunkSectionPos.getSectionCoord(box.minZ - 2.0);
        final int maxSectionX = ChunkSectionPos.getSectionCoord(box.maxX + 2.0);
        final int maxSectionY = ChunkSectionPos.getSectionCoord(box.maxY + 0.0);
        final int maxSectionZ = ChunkSectionPos.getSectionCoord(box.maxZ + 2.0);

        for (int sectionX = minSectionX; sectionX <= maxSectionX; sectionX++) {
            final long start = ChunkSectionPos.asLong(sectionX, 0, 0);
            final long end = ChunkSectionPos.asLong(sectionX, -1, -1);
            final List<Long> sectionPositions = new ArrayList<>();
            synchronized (this.ruthenium$lock) {
                final LongIterator iterator = this.trackedPositions.subSet(start, end + 1L).iterator();
                while (iterator.hasNext()) {
                    sectionPositions.add(iterator.nextLong());
                }
            }

            for (final long sectionPos : sectionPositions) {
                final int sectionY = ChunkSectionPos.unpackY(sectionPos);
                final int sectionZ = ChunkSectionPos.unpackZ(sectionPos);
                if (sectionY < minSectionY || sectionY > maxSectionY || sectionZ < minSectionZ || sectionZ > maxSectionZ) {
                    continue;
                }

                final EntityTrackingSection<T> trackingSection;
                synchronized (this.ruthenium$lock) {
                    trackingSection = this.trackingSections.get(sectionPos);
                }

                if (trackingSection != null
                    && !trackingSection.isEmpty()
                    && trackingSection.getStatus().shouldTrack()
                    && consumer.accept(trackingSection).shouldAbort()) {
                    return;
                }
            }
        }
    }

    @Overwrite
    public Stream<EntityTrackingSection<T>> getTrackingSections(final long chunkPos) {
        final int chunkX = ChunkPos.getPackedX(chunkPos);
        final int chunkZ = ChunkPos.getPackedZ(chunkPos);

        final long start = ChunkSectionPos.asLong(chunkX, 0, chunkZ);
        final long end = ChunkSectionPos.asLong(chunkX, -1, chunkZ);

        final List<EntityTrackingSection<T>> snapshot = new ArrayList<>();
        synchronized (this.ruthenium$lock) {
            final LongSortedSet sectionPositions = this.trackedPositions.subSet(start, end + 1L);
            if (sectionPositions.isEmpty()) {
                return Stream.empty();
            }
            final LongIterator iterator = sectionPositions.iterator();
            while (iterator.hasNext()) {
                final long sectionPos = iterator.nextLong();
                final EntityTrackingSection<T> section = this.trackingSections.get(sectionPos);
                if (section != null) {
                    snapshot.add(section);
                }
            }
        }

        return snapshot.stream().filter(Objects::nonNull);
    }

    @Overwrite
    public EntityTrackingSection<T> getTrackingSection(final long sectionPos) {
        synchronized (this.ruthenium$lock) {
            final EntityTrackingSection<T> existing = this.trackingSections.get(sectionPos);
            if (existing != null) {
                return existing;
            }

            final long chunkPos = chunkPosFromSectionPos(sectionPos);
            final EntityTrackingStatus trackingStatus = this.posToStatus.get(chunkPos);
            this.trackedPositions.add(sectionPos);
            final EntityTrackingSection<T> created = new EntityTrackingSection<>(this.entityClass, trackingStatus);
            this.trackingSections.put(sectionPos, created);
            return created;
        }
    }

    @Overwrite
    public @Nullable EntityTrackingSection<T> findTrackingSection(final long sectionPos) {
        synchronized (this.ruthenium$lock) {
            return this.trackingSections.get(sectionPos);
        }
    }

    @Overwrite
    public void removeSection(final long sectionPos) {
        synchronized (this.ruthenium$lock) {
            this.trackingSections.remove(sectionPos);
            this.trackedPositions.remove(sectionPos);
        }
    }

    @Overwrite
    public LongSet getChunkPositions() {
        final LongSet positions = new LongOpenHashSet();
        synchronized (this.ruthenium$lock) {
            this.trackingSections.keySet().forEach(sectionPos -> positions.add(chunkPosFromSectionPos(sectionPos)));
        }
        return positions;
    }

    @Debug
    @Overwrite
    public int sectionCount() {
        synchronized (this.ruthenium$lock) {
            return this.trackedPositions.size();
        }
    }
}
