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
            ),
            require = 0
    )
    private BlockState ruthenium$guardPistonHandlerGetBlockState(final World world,
                                                                 final BlockPos pos,
                                                                 final Operation<BlockState> original) {
        if (!RegionThreadUtil.canAccessBlock(world, pos)) {
            // Treat non-owned/non-loaded chunks as a hard barrier so pistons do not
            // attempt to move blocks across region boundaries.
            return Blocks.BEDROCK.getDefaultState();
        }
        return original.call(world, pos);
    }
}
