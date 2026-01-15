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

@Mixin(net.minecraft.block.piston.PistonHandler.class)
public abstract class PistonHandlerMixin {

    @WrapOperation(
            method = {
                    "calculatePush",
                    "tryMoveAdjacentBlock",
                    "tryMove"
            },
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/World;getBlockState(Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/block/BlockState;"
            )
    )
    private BlockState ruthenium$guardPistonHandlerGetBlockState(final World world,
                                                                 final BlockPos pos,
                                                                 final Operation<BlockState> original) {
        if (world instanceof ServerWorld serverWorld) {
            if (RegionThreadUtil.isRegionThread() && !RegionThreadUtil.canAccessBlock(serverWorld, pos)) {
                // For pistons specifically, we want to block pushing into non-owned regions.
                // However, if the chunk is loaded we can read the state to determine if
                // the piston CAN push (even though we'll block it with BEDROCK for unowned chunks).
                int x = pos.getX() >> 4;
                int z = pos.getZ() >> 4;
                if (!serverWorld.getChunkManager().isChunkLoaded(x, z)) {
                    // Not loaded at all - treat as barrier
                    return Blocks.BEDROCK.getDefaultState();
                }
                // Loaded but not owned - still treat as barrier to prevent cross-region block movement
                // This is intentional: pistons should not push blocks into other regions
                return Blocks.BEDROCK.getDefaultState();
            }
        }
        return original.call(world, pos);
    }
}
