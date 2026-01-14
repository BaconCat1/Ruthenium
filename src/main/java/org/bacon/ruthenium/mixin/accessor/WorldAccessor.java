package org.bacon.ruthenium.mixin.accessor;

import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(World.class)
public interface WorldAccessor {

    @Invoker("calculateAmbientDarkness")
    void ruthenium$invokeCalculateAmbientDarkness();
}
