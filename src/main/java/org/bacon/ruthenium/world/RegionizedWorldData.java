package org.bacon.ruthenium.world;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.village.raid.RaidManager;
import org.bacon.ruthenium.mixin.accessor.RaidManagerThreadSafe;
import org.bacon.ruthenium.mixin.accessor.ServerWorldAccessor;
import org.bacon.ruthenium.util.CoordinateUtil;

/**
 * Minimal backing store for world-scoped data needed by the region scheduler. The implementation
 * mirrors the structure of Folia's {@code RegionizedWorldData} but intentionally limits the
 * maintained state to the pieces currently consumed by Ruthenium.
 */
public final class RegionizedWorldData {

    private final ServerWorld world;
    private final LongSet tickingChunks = new LongOpenHashSet();
    private final LongSet entityTickingChunks = new LongOpenHashSet();
    private volatile boolean handlingTick;
    private volatile long lagCompensationTick;
    private long redstoneGameTime = 1L;
    private int wakeupInactiveRemainingAnimals;
    private int wakeupInactiveRemainingFlying;
    private int wakeupInactiveRemainingMonsters;
    private int wakeupInactiveRemainingVillagers;

    public RegionizedWorldData(final ServerWorld world) {
        this.world = Objects.requireNonNull(world, "world");
    }

    public ServerWorld getWorld() {
        return this.world;
    }

    public boolean isHandlingTick() {
        return this.handlingTick;
    }

    public void setHandlingTick(final boolean handlingTick) {
        this.handlingTick = handlingTick;
    }

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
     * Ticks pending world services that are still expected to run on the main orchestrator thread.
     * For now this only advances player network handlers so disconnects and heartbeat packets are
     * not starved while the per-region ticks execute on background threads.
     */
    public long getRedstoneGameTime() {
        return this.redstoneGameTime;
    }

    public void resetMobWakeupBudgets(final int animals, final int monsters, final int flying, final int villagers) {
        this.wakeupInactiveRemainingAnimals = Math.max(0, animals);
        this.wakeupInactiveRemainingMonsters = Math.max(0, monsters);
        this.wakeupInactiveRemainingFlying = Math.max(0, flying);
        this.wakeupInactiveRemainingVillagers = Math.max(0, villagers);
    }

    public boolean tryConsumeAnimalWakeBudget() {
        if (this.wakeupInactiveRemainingAnimals <= 0) {
            return false;
        }
        this.wakeupInactiveRemainingAnimals--;
        return true;
    }

    public boolean tryConsumeMonsterWakeBudget() {
        if (this.wakeupInactiveRemainingMonsters <= 0) {
            return false;
        }
        this.wakeupInactiveRemainingMonsters--;
        return true;
    }

    public boolean tryConsumeFlyingWakeBudget() {
        if (this.wakeupInactiveRemainingFlying <= 0) {
            return false;
        }
        this.wakeupInactiveRemainingFlying--;
        return true;
    }

    public boolean tryConsumeVillagerWakeBudget() {
        if (this.wakeupInactiveRemainingVillagers <= 0) {
            return false;
        }
        this.wakeupInactiveRemainingVillagers--;
        return true;
    }

    public void addChunk(final int chunkX, final int chunkZ) {
        this.tickingChunks.add(CoordinateUtil.getChunkKey(chunkX, chunkZ));
    }

    public void removeChunk(final int chunkX, final int chunkZ) {
        final long key = CoordinateUtil.getChunkKey(chunkX, chunkZ);
        this.tickingChunks.remove(key);
        this.entityTickingChunks.remove(key);
    }

    public void markEntityTickingChunk(final int chunkX, final int chunkZ) {
        this.entityTickingChunks.add(CoordinateUtil.getChunkKey(chunkX, chunkZ));
    }

    public void unmarkEntityTickingChunk(final int chunkX, final int chunkZ) {
        this.entityTickingChunks.remove(CoordinateUtil.getChunkKey(chunkX, chunkZ));
    }

    public long[] snapshotTickingChunks() {
        return this.tickingChunks.toLongArray();
    }

    public long[] snapshotEntityTickingChunks() {
        return this.entityTickingChunks.toLongArray();
    }

    public void tickGlobalServices(final BooleanSupplier shouldKeepTicking) {
        if (!shouldKeepTicking.getAsBoolean()) {
            return;
        }

        this.tickConnections(shouldKeepTicking);
        if (!shouldKeepTicking.getAsBoolean()) {
            return;
        }

        this.tickWorldBorder();
        if (!shouldKeepTicking.getAsBoolean()) {
            return;
        }

        this.tickWeather();
        if (!shouldKeepTicking.getAsBoolean()) {
            return;
        }

        this.updateSleepingPlayers();
        if (!shouldKeepTicking.getAsBoolean()) {
            return;
        }

        this.tickRaids();
        if (!shouldKeepTicking.getAsBoolean()) {
            return;
        }

        this.tickTime();
        this.resetMobWakeupBudgets(4, 8, 2, 4);
    }

    public void beginTick(final BooleanSupplier shouldKeepTicking) {
        this.setHandlingTick(true);
        this.updateTickData();
        this.tickGlobalServices(shouldKeepTicking);
    }

    public void finishTick() {
        this.setHandlingTick(false);
    }

    private void tickConnections(final BooleanSupplier shouldKeepTicking) {
        if (!shouldKeepTicking.getAsBoolean()) {
            return;
        }
    final List<ServerPlayerEntity> players = List.copyOf(this.world.getPlayers()); // avoids CME when handlers disconnect players
        for (final ServerPlayerEntity player : players) {
            final ServerPlayNetworkHandler networkHandler = player.networkHandler;
            if (networkHandler != null) {
                networkHandler.tick();
            }
        }
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

    public void notifyChunkListDrained(final Iterable<ChunkPos> chunks) {
        for (final ChunkPos pos : chunks) {
            this.entityTickingChunks.remove(CoordinateUtil.getChunkKey(pos.x, pos.z));
        }
    }
}
