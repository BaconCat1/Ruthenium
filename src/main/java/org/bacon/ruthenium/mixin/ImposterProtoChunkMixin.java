package org.bacon.ruthenium.mixin;

import net.minecraft.block.entity.BlockEntity;
import net.minecraft.util.math.BlockPos;
import org.bacon.ruthenium.world.RegionThreadUtil;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Guard ImposterProtoChunk block entity reads on region threads to avoid accessing
 * wrapped chunks without ownership. Mirrors Folia's thread-safety patch: when writes
 * are disallowed, surface no block entity instead of touching the wrapped chunk.
 */
@Mixin(targets = "net.minecraft.world.level.chunk.ImposterProtoChunk")
public abstract class ImposterProtoChunkMixin {

    @Shadow @Final private boolean allowWrites;

    @Inject(method = "getBlockEntity", at = @At("HEAD"), cancellable = true)
    private void ruthenium$guardGetBlockEntity(final BlockPos pos,
                                               final CallbackInfoReturnable<BlockEntity> cir) {
        if (!this.allowWrites && RegionThreadUtil.isRegionThread()) {
            cir.setReturnValue(null);
        }
    }
}
