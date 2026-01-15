package org.bacon.ruthenium.mixin;

import net.minecraft.block.ComparatorBlock;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Placeholder for comparator block state read guards.
 * The actual WrapOperation is disabled due to mapping issues.
 * Cross-region comparator reads are handled elsewhere in the system.
 */
@Mixin(ComparatorBlock.class)
public abstract class ComparatorBlockMixin {
    // Guard logic temporarily disabled - handled by other thread safety measures
}
