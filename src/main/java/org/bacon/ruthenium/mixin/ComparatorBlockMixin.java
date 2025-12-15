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

@Mixin(net.minecraft.block.ComparatorBlock.class)
public abstract class ComparatorBlockMixin {

    @WrapOperation(
            method = {
                    "calculateOutputSignal",
                    "update"
            },
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/World;getBlockState(Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/block/BlockState;"
            ),
            require = 0
    )
    private BlockState ruthenium$guardComparatorGetBlockState(final World world,
                                                              final BlockPos pos,
                                                              final Operation<BlockState> original) {
        if (!RegionThreadUtil.canAccessBlock(world, pos)) {
            return Blocks.AIR.getDefaultState();
        }
        return original.call(world, pos);
    }
}
