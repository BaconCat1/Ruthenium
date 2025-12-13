package org.bacon.ruthenium.mixin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;
import net.minecraft.util.TypeFilter;
import net.minecraft.util.collection.TypeFilterableList;
import net.minecraft.util.function.LazyIterationConsumer;
import net.minecraft.util.math.Box;
import net.minecraft.world.entity.EntityLike;
import net.minecraft.world.entity.EntityTrackingSection;
import net.minecraft.world.entity.EntityTrackingStatus;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(EntityTrackingSection.class)
public abstract class EntityTrackingSectionMixin<T extends EntityLike> {

    @Shadow
    @Final
    private TypeFilterableList<T> collection;

    @Shadow
    private EntityTrackingStatus status;

    @Overwrite
    public void add(final T entity) {
        synchronized (this) {
            this.collection.add(entity);
        }
    }

    @Overwrite
    public boolean remove(final T entity) {
        synchronized (this) {
            return this.collection.remove(entity);
        }
    }

    @Overwrite
    public LazyIterationConsumer.NextIteration forEach(final Box box, final LazyIterationConsumer<T> consumer) {
        final List<T> snapshot;
        synchronized (this) {
            snapshot = new ArrayList<>(this.collection);
        }

        for (final T entityLike : snapshot) {
            if (entityLike.getBoundingBox().intersects(box) && consumer.accept(entityLike).shouldAbort()) {
                return LazyIterationConsumer.NextIteration.ABORT;
            }
        }
        return LazyIterationConsumer.NextIteration.CONTINUE;
    }

    @Overwrite
    @SuppressWarnings("unchecked")
    public <U extends T> LazyIterationConsumer.NextIteration forEach(final TypeFilter<T, U> type, final Box box,
                                                                     final LazyIterationConsumer<? super U> consumer) {
        final List<? extends T> snapshot;
        synchronized (this) {
            final Collection<? extends T> matching = this.collection.getAllOfType(type.getBaseClass());
            if (matching.isEmpty()) {
                return LazyIterationConsumer.NextIteration.CONTINUE;
            }
            snapshot = List.copyOf(matching);
        }

        for (final T entityLike : snapshot) {
            final U downcast = type.downcast(entityLike);
            if (downcast != null && entityLike.getBoundingBox().intersects(box) && consumer.accept(downcast).shouldAbort()) {
                return LazyIterationConsumer.NextIteration.ABORT;
            }
        }

        return LazyIterationConsumer.NextIteration.CONTINUE;
    }

    @Overwrite
    public Stream<T> stream() {
        final List<T> snapshot;
        synchronized (this) {
            snapshot = new ArrayList<>(this.collection);
        }
        return snapshot.stream();
    }

    @Overwrite
    public EntityTrackingStatus getStatus() {
        synchronized (this) {
            return this.status;
        }
    }

    @Overwrite
    public EntityTrackingStatus swapStatus(final EntityTrackingStatus status) {
        synchronized (this) {
            final EntityTrackingStatus previous = this.status;
            this.status = status;
            return previous;
        }
    }

    @Overwrite
    public int size() {
        synchronized (this) {
            return this.collection.size();
        }
    }

    @Overwrite
    public boolean isEmpty() {
        synchronized (this) {
            return this.collection.isEmpty();
        }
    }
}
