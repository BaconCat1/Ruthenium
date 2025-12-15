package org.bacon.ruthenium.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.WorldView;
import org.bacon.ruthenium.world.RegionThreadUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(net.minecraft.block.AbstractRedstoneGateBlock.class)
public abstract class AbstractRedstoneGateBlockMixin {

    @WrapOperation(
            method = {
                    "getOutputLevel",
                    "hasPower",
                    "canPlaceAbove",
                    "getMaxInputLevelSides",
                    "getSideInputFromGatesOnly",
                    "isTargetNotAligned",
                    "getPower",
                    "isLocked",
                    "updatePowered",
                    "updateTarget"
            },
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/World;getBlockState(Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/block/BlockState;"
            ),
            require = 0
    )
    private BlockState ruthenium$guardGateWorldGetBlockState(final World world,
                                                             final BlockPos pos,
                                                             final Operation<BlockState> original) {
        if (!RegionThreadUtil.canAccessBlock(world, pos)) {
            return Blocks.AIR.getDefaultState();
        }
        return original.call(world, pos);
    }

    @WrapOperation(
            method = {
                    "getOutputLevel",
                    "hasPower",
                    "canPlaceAbove",
                    "getMaxInputLevelSides",
                    "getSideInputFromGatesOnly",
                    "isTargetNotAligned",
                    "getPower",
                    "isLocked",
                    "updatePowered",
                    "updateTarget"
            },
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/WorldView;getBlockState(Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/block/BlockState;"
            ),
            require = 0
    )
    private BlockState ruthenium$guardGateWorldViewGetBlockState(final WorldView world,
                                                                 final BlockPos pos,
                                                                 final Operation<BlockState> original) {
        if (world instanceof World castWorld && !RegionThreadUtil.canAccessBlock(castWorld, pos)) {
            return Blocks.AIR.getDefaultState();
        }
        return original.call(world, pos);
    }

    @WrapOperation(
            method = {
                    "getOutputLevel",
                    "hasPower",
                    "canPlaceAbove",
                    "getMaxInputLevelSides",
                    "getSideInputFromGatesOnly",
                    "isTargetNotAligned",
                    "getPower",
                    "isLocked",
                    "updatePowered",
                    "updateTarget"
            },
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/BlockView;getBlockState(Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/block/BlockState;"
            ),
            require = 0
    )
    private BlockState ruthenium$guardGateBlockViewGetBlockState(final BlockView world,
                                                                 final BlockPos pos,
                                                                 final Operation<BlockState> original) {
        if (world instanceof World castWorld && !RegionThreadUtil.canAccessBlock(castWorld, pos)) {
            return Blocks.AIR.getDefaultState();
        }
        return original.call(world, pos);
    }
}
