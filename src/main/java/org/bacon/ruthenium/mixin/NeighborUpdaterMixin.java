package org.bacon.ruthenium.mixin;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import net.minecraft.world.WorldAccess;
import net.minecraft.world.block.NeighborUpdater;
import net.minecraft.world.block.WireOrientation;
import org.bacon.ruthenium.world.RegionThreadUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(NeighborUpdater.class)
public interface NeighborUpdaterMixin {

    @Inject(
            method = "tryNeighborUpdate(Lnet/minecraft/world/World;Lnet/minecraft/block/BlockState;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/Block;Lnet/minecraft/world/block/WireOrientation;Z)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private static void ruthenium$guardTryNeighborUpdate(final World world,
                                                        final BlockState state,
                                                        final BlockPos pos,
                                                        final Block sourceBlock,
                                                        final WireOrientation orientation,
                                                        final boolean notify,
                                                        final CallbackInfo ci) {
        if (!(world instanceof ServerWorld serverWorld)) {
            return;
        }
        if (RegionThreadUtil.canAccessBlock(serverWorld, pos)) {
            return;
        }
        ci.cancel();
    }

    @Inject(
            method = "replaceWithStateForNeighborUpdate(Lnet/minecraft/world/WorldAccess;Lnet/minecraft/util/math/Direction;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;II)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private static void ruthenium$guardReplaceWithStateForNeighborUpdate(final WorldAccess world,
                                                                         final Direction direction,
                                                                         final BlockPos pos,
                                                                         final BlockPos neighborPos,
                                                                         final BlockState neighborState,
                                                                         final int updateFlags,
                                                                         final int updateLimit,
                                                                         final CallbackInfo ci) {
        if (!(world instanceof ServerWorld serverWorld)) {
            return;
        }
        if (RegionThreadUtil.canAccessBlock(serverWorld, pos)) {
            return;
        }
        ci.cancel();
    }
}
