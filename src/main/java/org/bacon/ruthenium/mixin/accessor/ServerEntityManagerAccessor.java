package org.bacon.ruthenium.mixin.accessor;

import net.minecraft.server.world.ServerEntityManager;
import net.minecraft.entity.Entity;
import net.minecraft.world.entity.SectionedEntityCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ServerEntityManager.class)
public interface ServerEntityManagerAccessor {

    @Accessor("cache")
    SectionedEntityCache<Entity> ruthenium$getEntitySectionCache();
}
