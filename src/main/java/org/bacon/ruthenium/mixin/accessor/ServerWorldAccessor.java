package org.bacon.ruthenium.mixin.accessor;

import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.fluid.Fluid;
import net.minecraft.server.world.ServerEntityManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.profiler.Profiler;
import net.minecraft.world.EntityList;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.tick.TickManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ServerWorld.class)
public interface ServerWorldAccessor {

    @Invoker("tickChunk")
    void ruthenium$invokeTickChunk(WorldChunk chunk, int randomTickSpeed);

    @Invoker("tickTime")
    void ruthenium$invokeTickTime();

    @Invoker("tickWeather")
    void ruthenium$invokeTickWeather();

    @Invoker("updateSleepingPlayers")
    void ruthenium$invokeUpdateSleepingPlayers();

    @Invoker("tickBlock")
    void ruthenium$invokeTickBlock(BlockPos pos, Block block);

    @Invoker("tickFluid")
    void ruthenium$invokeTickFluid(BlockPos pos, Fluid fluid);

    @Invoker("processSyncedBlockEvents")
    void ruthenium$invokeProcessSyncedBlockEvents();

    @Invoker("method_31420")
    void ruthenium$invokeTickEntityLifecycle(TickManager tickManager, Profiler profiler, Entity entity);

    @Accessor("entityList")
    EntityList ruthenium$getEntityList();

    @Accessor("entityManager")
    ServerEntityManager<?> ruthenium$getEntityManager();

    @Accessor("inBlockTick")
    void ruthenium$setInBlockTick(boolean inBlockTick);
}
