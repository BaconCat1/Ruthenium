package org.bacon.ruthenium.mixin.accessor;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.chunk.WorldChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ServerWorld.class)
public interface ServerWorldAccessor {

    @Invoker("tickChunk")
    void ruthenium$invokeTickChunk(WorldChunk chunk, int randomTickSpeed);
}
