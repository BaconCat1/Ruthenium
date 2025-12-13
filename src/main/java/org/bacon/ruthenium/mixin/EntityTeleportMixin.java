package org.bacon.ruthenium.mixin;

import net.minecraft.entity.Entity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.TeleportTarget;
import org.bacon.ruthenium.world.RegionTaskDispatcher;
import org.bacon.ruthenium.world.RegionizedServer;
import org.bacon.ruthenium.world.RegionizedServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Ensures same-dimension teleports execute on the owning region thread.
 */
@Mixin(Entity.class)
public abstract class EntityTeleportMixin {

    @Inject(method = "teleportTo", at = @At("HEAD"), cancellable = true)
    private void ruthenium$transferSameDimensionTeleport(final TeleportTarget teleportTarget,
                                                         final CallbackInfoReturnable<Entity> cir) {
        if (teleportTarget == null) {
            return;
        }

        final Entity self = (Entity)(Object)this;
        if (!(self.getEntityWorld() instanceof ServerWorld fromWorld)) {
            return;
        }

        final ServerWorld toWorld = teleportTarget.world();
        if (toWorld == null) {
            return;
        }

        if (toWorld.getRegistryKey() != fromWorld.getRegistryKey()) {
            return;
        }

        if (!(fromWorld instanceof RegionizedServerWorld regionized)) {
            return;
        }

        if (!RegionizedServer.isOnRegionThread()) {
            return;
        }

        final Vec3d pos = teleportTarget.position();
        final int chunkX = ((int)Math.floor(pos.x)) >> 4;
        final int chunkZ = ((int)Math.floor(pos.z)) >> 4;
        if (regionized.ruthenium$isOwnedByCurrentRegion(chunkX, chunkZ)) {
            return;
        }

        RegionTaskDispatcher.runOnChunk(toWorld, chunkX, chunkZ, () -> self.teleportTo(teleportTarget));
        cir.setReturnValue(self);
    }
}

