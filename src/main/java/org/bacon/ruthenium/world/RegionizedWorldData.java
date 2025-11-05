package org.bacon.ruthenium.world;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.world.ServerWorld;

/**
 * Minimal backing store for world-scoped data needed by the region scheduler. The implementation
 * mirrors the structure of Folia's {@code RegionizedWorldData} but intentionally limits the
 * maintained state to the pieces currently consumed by Ruthenium.
 */
public final class RegionizedWorldData {

    private final ServerWorld world;
    private volatile boolean handlingTick;
    private volatile long lagCompensationTick;

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
    }

    /**
     * Ticks pending world services that are still expected to run on the main orchestrator thread.
     * For now this only advances player network handlers so disconnects and heartbeat packets are
     * not starved while the per-region ticks execute on background threads.
     */
    public void tickGlobalServices(final BooleanSupplier shouldKeepTicking) {
        this.tickConnections(shouldKeepTicking);
    }

    private void tickConnections(final BooleanSupplier shouldKeepTicking) {
        if (!shouldKeepTicking.getAsBoolean()) {
            return;
        }
        for (final ServerPlayerEntity player : this.world.getPlayers()) {
            final ServerPlayNetworkHandler networkHandler = player.networkHandler;
            if (networkHandler != null) {
                networkHandler.tick();
            }
        }
    }
}
