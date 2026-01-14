package org.bacon.ruthenium.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.bacon.ruthenium.world.RegionThreadUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(net.minecraft.world.RedstoneController.class)
public abstract class RedstoneControllerMixin {

    @WrapOperation(
            method = "calculateWirePowerAt",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/World;getBlockState(Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/block/BlockState;"
            )
    )
    private BlockState ruthenium$guardCalculateWirePowerAtGetBlockState(final World world,
                                                                        final BlockPos pos,
                                                                        final Operation<BlockState> original) {
        if (world instanceof net.minecraft.server.world.ServerWorld serverWorld) {
            if (RegionThreadUtil.isRegionThread() && !RegionThreadUtil.canAccessBlock(serverWorld, pos)) {
                // If we can't access it safely (not owned), check if it's at least loaded.
                // If loaded, we allow reading to support cross-region redstone sensing.
                // This might be slightly unsafe if the other region is modifying the chunk,
                // but returning AIR breaks connectivity.
                int x = pos.getX() >> 4;
                int z = pos.getZ() >> 4;
                if (serverWorld.getChunkManager().isChunkLoaded(x, z)) {
                    return original.call(world, pos);
                }
                return Blocks.AIR.getDefaultState();
            }
        }
        return original.call(world, pos);
    }
}
