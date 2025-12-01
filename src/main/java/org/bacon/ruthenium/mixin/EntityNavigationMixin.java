package org.bacon.ruthenium.mixin;

import net.minecraft.entity.ai.pathing.EntityNavigation;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Ensures navigation recompute throttling uses the region thread's tick counter instead
 * of the global world time so pathfinding works when worlds tick asynchronously.
 */
@Mixin(EntityNavigation.class)
public abstract class EntityNavigationMixin {

    @Shadow
    protected int tickCount;

    @Redirect(method = "recalculatePath",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/World;getTime()J"))
    private long ruthenium$useRegionNavigationTick(final World world) {
        return this.tickCount;
    }

    @Redirect(method = "checkTimeouts",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/World;getTime()J"))
    private long ruthenium$useRegionNavigationTimeoutTick(final World world) {
        return this.tickCount;
    }
}
