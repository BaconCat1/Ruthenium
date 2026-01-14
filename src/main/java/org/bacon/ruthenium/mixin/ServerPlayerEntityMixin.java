package org.bacon.ruthenium.mixin;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.ChunkPos;
import org.bacon.ruthenium.util.CoordinateUtil;
import org.bacon.ruthenium.world.RegionizedWorldData;
import org.bacon.ruthenium.world.TickRegionScheduler;
import org.bacon.ruthenium.world.RegionizedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayerEntity.class)
public abstract class ServerPlayerEntityMixin {

    @Unique
    private long ruthenium$lastTrackedChunk = Long.MIN_VALUE;

    @Unique
    private RegionizedWorldData ruthenium$lastTrackedWorldData;

    @Inject(method = "tick", at = @At("TAIL"))
    private void ruthenium$updateRegionTracking(final CallbackInfo ci) {
        if (!RegionizedServer.isOnRegionThread()) {
            return;
        }
        final RegionizedWorldData worldData = TickRegionScheduler.getCurrentWorldData();
        if (worldData == null) {
            return;
        }
        final ServerPlayerEntity player = (ServerPlayerEntity)(Object)this;
        final ChunkPos chunkPos = player.getChunkPos();
        final long chunkKey = CoordinateUtil.getChunkKey(chunkPos.x, chunkPos.z);

        if (this.ruthenium$lastTrackedWorldData != worldData) {
            if (this.ruthenium$lastTrackedWorldData != null) {
                this.ruthenium$lastTrackedWorldData.removePlayer(player);
            }
            worldData.addPlayer(player);
            worldData.updatePlayerTrackingPosition(player);
            this.ruthenium$lastTrackedWorldData = worldData;
            this.ruthenium$lastTrackedChunk = chunkKey;
            return;
        }

        if (this.ruthenium$lastTrackedChunk != chunkKey) {
            worldData.updatePlayerTrackingPosition(player);
            this.ruthenium$lastTrackedChunk = chunkKey;
        }
    }
}
