package org.bacon.ruthenium.mixin.accessor;

import net.minecraft.server.world.ServerChunkLoadingManager;
import net.minecraft.server.world.ServerChunkManager;
import net.minecraft.server.world.ChunkTicketManager;
import net.minecraft.util.profiler.Profiler;
import net.minecraft.world.SpawnHelper;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.world.chunk.WorldChunk;
import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ServerChunkManager.class)
public interface ServerChunkManagerAccessor {

    @Accessor("chunkLoadingManager")
    ServerChunkLoadingManager ruthenium$getChunkLoadingManager();

    @Accessor("ticketManager")
    ChunkTicketManager ruthenium$getTicketManager();

    @Invoker("updateChunks")
    boolean ruthenium$invokeUpdateChunks();

    @Invoker("initChunkCaches")
    void ruthenium$invokeInitChunkCaches();

    @Invoker("broadcastUpdates")
    void ruthenium$invokeBroadcastUpdates(Profiler profiler);

    @Invoker("tickSpawningChunk")
    void ruthenium$invokeTickSpawningChunk(WorldChunk chunk, long timeDelta, List<SpawnGroup> spawnableGroups, SpawnHelper.Info info);

    @Invoker("executeQueuedTasks")
    boolean ruthenium$invokeExecuteQueuedTasks();

    @Accessor("spawnMonsters")
    boolean ruthenium$getSpawnMonsters();
}
