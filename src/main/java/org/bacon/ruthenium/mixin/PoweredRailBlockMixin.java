package org.bacon.ruthenium.mixin;

import net.minecraft.block.enums.RailShape;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.bacon.ruthenium.world.RegionThreadUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(net.minecraft.block.PoweredRailBlock.class)
public abstract class PoweredRailBlockMixin {

    @Inject(
            method = "isPoweredByOtherRails(Lnet/minecraft/world/World;Lnet/minecraft/util/math/BlockPos;ZILnet/minecraft/block/enums/RailShape;)Z",
            at = @At("HEAD"),
            cancellable = true
    )
    private void ruthenium$guardIsPoweredByOtherRails(final World world,
                                                     final BlockPos pos,
                                                     final boolean searchForward,
                                                     final int distance,
                                                     final RailShape shape,
                                                     final CallbackInfoReturnable<Boolean> cir) {
        if (RegionThreadUtil.canAccessBlock(world, pos)) {
            return;
        }
        cir.setReturnValue(false);
    }
}
