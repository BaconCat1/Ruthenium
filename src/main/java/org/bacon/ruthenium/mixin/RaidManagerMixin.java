package org.bacon.ruthenium.mixin;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMaps;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.village.raid.Raid;
import net.minecraft.village.raid.RaidManager;
import net.minecraft.world.GameRules;
import net.minecraft.world.dimension.DimensionType;
import net.minecraft.world.poi.PointOfInterest;
import net.minecraft.world.poi.PointOfInterestStorage;
import org.bacon.ruthenium.world.RegionThreadUtil;
import org.bacon.ruthenium.world.TickRegionScheduler;
import org.bacon.ruthenium.world.raid.RaidManagerThreadSafe;
import org.bacon.ruthenium.world.raid.RaidThreadSafe;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.Overwrite;

/**
 * Provides region thread aware behaviour for raid management, ensuring that raid state mutation
 * happens on the owning region thread while preserving vanilla semantics.
 */
@Mixin(RaidManager.class)
public abstract class RaidManagerMixin implements RaidManagerThreadSafe {

    /**
     * Required mixin constructor.
     */
    protected RaidManagerMixin() {
    }

    @Shadow @Final @Mutable private Int2ObjectMap<Raid> raids;
    @Shadow private int nextAvailableId;
    @Shadow private int currentTime;

    /**
     * Shadowed persistence hook from the vanilla raid manager.
     */
    @Shadow protected abstract void markDirty();

    /**
     * Shadowed raid factory, invoked to obtain or create a raid centered on the supplied position.
     */
    @Shadow protected abstract Raid getOrCreateRaid(ServerWorld world, BlockPos pos);

    @Unique
    private final AtomicInteger ruthenium$nextAvailable = new AtomicInteger();

    @Unique
    private static Int2ObjectMap<Raid> ruthenium$wrap(final Int2ObjectMap<Raid> source) {
        final Int2ObjectOpenHashMap<Raid> copy = new Int2ObjectOpenHashMap<>();
        copy.putAll(source);
        return Int2ObjectMaps.synchronize(copy);
    }

    @Inject(method = "<init>()V", at = @At("TAIL"))
    private void ruthenium$wrapEmpty(final CallbackInfo ci) {
        this.raids = ruthenium$wrap(this.raids);
        this.ruthenium$nextAvailable.set(Math.max(1, this.nextAvailableId));
    }

    @Inject(method = "<init>(Ljava/util/List;II)V", at = @At("TAIL"))
    private void ruthenium$wrapLoaded(final List<?> raids, final int nextId, final int tick, final CallbackInfo ci) {
        this.raids = ruthenium$wrap(this.raids);
        this.ruthenium$nextAvailable.set(Math.max(1, this.nextAvailableId));
    }

    /**
     * Advances bookkeeping that must remain on the orchestrator thread, mirroring the vanilla raid
     * manager's periodic {@code tick()} side effects.
     */
    @Override
    public void ruthenium$globalTick() {
        this.currentTime++;
        if (this.currentTime % 200 == 0) {
            this.markDirty();
        }
    }

    /**
     * Locates the raid identifier associated with the provided raid instance.
     *
     * @param raid raid whose identifier should be resolved
     * @return identifier or empty when the raid is not tracked
     */
    @Overwrite
    public OptionalInt getRaidId(final Raid raid) {
        synchronized (this.raids) {
            final Iterator<Int2ObjectMap.Entry<Raid>> iterator = this.raids.int2ObjectEntrySet().iterator();
            while (iterator.hasNext()) {
                final Int2ObjectMap.Entry<Raid> entry = iterator.next();
                if (entry.getValue() == raid) {
                    return OptionalInt.of(entry.getIntKey());
                }
            }
        }
        return OptionalInt.empty();
    }

    /**
     * Ticks active raids, skipping those owned by other region threads and cleaning up completed
     * raids in a thread-safe manner.
     *
     * @param world world executing the raid update
     */
    @Overwrite
    public void tick(final ServerWorld world) {
        final boolean disableRaids = world.getGameRules().getBoolean(GameRules.DISABLE_RAIDS);
        synchronized (this.raids) {
            final Iterator<Raid> iterator = this.raids.values().iterator();
            while (iterator.hasNext()) {
                final Raid raid = iterator.next();
                if (raid instanceof RaidThreadSafe threadSafe && !threadSafe.ruthenium$ownsRaid(world)) {
                    continue;
                }
                if (disableRaids) {
                    raid.invalidate();
                }
                if (raid.hasStopped()) {
                    iterator.remove();
                    this.markDirty();
                    continue;
                }
                raid.tick(world);
            }
        }
    }

    /**
     * Starts a raid centered at the supplied position when the current region thread owns all
     * relevant chunks and players.
     *
     * @param player player triggering the raid
     * @param pos    raid origin position
     * @return raid instance or {@code null} when the raid could not start
     */
    @Overwrite
    public Raid startRaid(final ServerPlayerEntity player, final BlockPos pos) {
        if (player.isSpectator()) {
            return null;
        }
        final ServerWorld world = TickRegionScheduler.getCurrentWorld();
        if (world == null) {
            return null;
        }
        if (world.getGameRules().getBoolean(GameRules.DISABLE_RAIDS)) {
            return null;
        }
        final DimensionType dimensionType = world.getDimension();
        if (!dimensionType.hasRaids()) {
            return null;
        }
        if (!RegionThreadUtil.ownsPlayer(player, 8) || !RegionThreadUtil.ownsPosition(world, pos, 8)) {
            return null;
        }

        final List<PointOfInterest> pois = world.getPointOfInterestStorage()
            .getInCircle(point -> true, pos, 64, PointOfInterestStorage.OccupationStatus.IS_OCCUPIED)
            .toList();
        int total = 0;
        Vec3d accumulated = Vec3d.ZERO;
        for (final PointOfInterest poi : pois) {
            final BlockPos poiPos = poi.getPos();
            accumulated = accumulated.add(poiPos.getX(), poiPos.getY(), poiPos.getZ());
            total++;
        }
        final BlockPos center = total > 0 ? BlockPos.ofFloored(accumulated.multiply(1.0 / total)) : pos;
        final Raid raid = this.getOrCreateRaid(world, center);
        synchronized (this.raids) {
            if (!raid.hasStarted() && !this.raids.containsValue(raid)) {
                final int id = this.nextId();
                this.raids.put(id, raid);
            }
        }
        if (!raid.hasStarted() || raid.getBadOmenLevel() < raid.getMaxAcceptableBadOmenLevel()) {
            raid.start(player);
        }
        this.markDirty();
        return raid;
    }

    // shadow present earlier in the file; do not duplicate

    /**
     * Provides thread-safe raid identifiers by backing the vanilla counter with an atomic integer.
     *
     * @return next raid identifier
     */
    @Overwrite
    protected int nextId() {
        final int id = this.ruthenium$nextAvailable.incrementAndGet();
        this.nextAvailableId = Math.max(this.nextAvailableId, id);
        return id;
    }

    /**
     * Retrieves the closest raid to the supplied position, respecting region ownership checks.
     *
     * @param pos            search origin
     * @param searchDistance maximum squared distance to consider
     * @return raid instance or {@code null} when none are close enough
     */
    @Overwrite
    public Raid getRaidAt(final BlockPos pos, final int searchDistance) {
        final ServerWorld current = TickRegionScheduler.getCurrentWorld();
        return this.ruthenium$getRaidFor(current, pos, searchDistance);
    }

    /**
     * Finds a raid near the provided position that is owned by the specified world, if any.
     *
     * @param world          world that must own the raid
     * @param pos            position near which to search
     * @param searchDistance maximum squared distance to consider
     * @return matching raid or {@code null} when none match the criteria
     */
    @Override
    public Raid ruthenium$getRaidFor(final ServerWorld world, final BlockPos pos, final int searchDistance) {
        double bestDistance = searchDistance;
        Raid closest = null;
        synchronized (this.raids) {
            for (final Raid raid : this.raids.values()) {
                if (!raid.isActive()) {
                    continue;
                }
                if (world != null && raid instanceof RaidThreadSafe threadSafe && !threadSafe.ruthenium$ownsRaid(world)) {
                    continue;
                }
                final BlockPos center = raid.getCenter();
                if (center == null) {
                    continue;
                }
                final double distance = center.getSquaredDistance(pos);
                if (distance < bestDistance) {
                    closest = raid;
                    bestDistance = distance;
                }
            }
        }
        return closest;
    }
}
