package org.bacon.ruthenium.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.bacon.ruthenium.world.RegionThreadUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(net.minecraft.block.PoweredRailBlock.class)
public abstract class PoweredRailBlockMixin {

    /**
     * Guard block state reads in isPoweredByOtherRails to prevent cross-region violations.
     * Allow reads from loaded chunks even if not owned for cross-region sensing.
     */
    @WrapOperation(
            method = "isPoweredByOtherRails(Lnet/minecraft/world/World;Lnet/minecraft/util/math/BlockPos;ZILnet/minecraft/block/enums/RailShape;)Z",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/World;getBlockState(Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/block/BlockState;"
            )
    )
    private BlockState ruthenium$guardIsPoweredByOtherRailsGetBlockState(final World world,
                                                                         final BlockPos pos,
                                                                         final Operation<BlockState> original) {
        if (world instanceof ServerWorld serverWorld) {
            if (RegionThreadUtil.isRegionThread() && !RegionThreadUtil.canAccessBlock(serverWorld, pos)) {
                // Can't access safely (not owned), but allow reads from loaded chunks
                int x = pos.getX() >> 4;
                int z = pos.getZ() >> 4;
                if (serverWorld.getChunkManager().isChunkLoaded(x, z)) {
                    return original.call(world, pos);
                }
                // Return AIR to make the rail check fail gracefully
                return Blocks.AIR.getDefaultState();
            }
        }
        return original.call(world, pos);
    }
}
