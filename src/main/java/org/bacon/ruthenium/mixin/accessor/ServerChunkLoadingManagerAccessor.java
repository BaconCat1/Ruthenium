package org.bacon.ruthenium.mixin.accessor;

import java.util.function.Consumer;
import net.minecraft.server.world.ChunkHolder;
import net.minecraft.server.world.ServerChunkLoadingManager;
import net.minecraft.world.chunk.WorldChunk;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ServerChunkLoadingManager.class)
public interface ServerChunkLoadingManagerAccessor {

    @Invoker("forEachBlockTickingChunk")
    void ruthenium$forEachBlockTickingChunk(Consumer<WorldChunk> consumer);

    @Invoker("tickEntityMovement")
    void ruthenium$invokeTickEntityMovement();

    @Nullable
    @Invoker("getChunkHolder")
    ChunkHolder ruthenium$getChunkHolder(long pos);
}
