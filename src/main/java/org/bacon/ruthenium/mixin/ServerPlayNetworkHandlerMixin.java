package org.bacon.ruthenium.mixin;

import net.minecraft.entity.Entity;
import net.minecraft.network.NetworkThreadUtils;
import net.minecraft.network.listener.PacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import org.bacon.ruthenium.world.RegionTaskDispatcher;
import org.bacon.ruthenium.world.RegionThreadUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayNetworkHandler.class)
public abstract class ServerPlayNetworkHandlerMixin {

    @Shadow public ServerPlayerEntity player;

    @Unique
    private boolean ruthenium$queueInteraction(final int chunkX, final int chunkZ, final Runnable callback) {
        final ServerWorld world = this.player.getEntityWorld();
        if (RegionThreadUtil.isRegionThreadFor(world)) {
            return false;
        }
        return RegionTaskDispatcher.runOnChunk(world, chunkX, chunkZ, callback);
    }

    @Inject(method = "onPlayerInteractBlock", at = @At("HEAD"), cancellable = true)
    private void ruthenium$scheduleBlockInteraction(final PlayerInteractBlockC2SPacket packet, final CallbackInfo ci) {
        final BlockPos pos = packet.getBlockHitResult().getBlockPos();
        if (this.ruthenium$queueInteraction(pos.getX() >> 4, pos.getZ() >> 4,
            () -> ((ServerPlayNetworkHandler)(Object)this).onPlayerInteractBlock(packet))) {
            ci.cancel();
        }
    }

    @Inject(method = "onPlayerInteractItem", at = @At("HEAD"), cancellable = true)
    private void ruthenium$scheduleItemInteraction(final PlayerInteractItemC2SPacket packet, final CallbackInfo ci) {
        final ChunkPos chunkPos = this.player.getChunkPos();
        if (this.ruthenium$queueInteraction(chunkPos.x, chunkPos.z,
            () -> ((ServerPlayNetworkHandler)(Object)this).onPlayerInteractItem(packet))) {
            ci.cancel();
        }
    }

    @Inject(method = "onPlayerInteractEntity", at = @At("HEAD"), cancellable = true)
    private void ruthenium$scheduleEntityInteraction(final PlayerInteractEntityC2SPacket packet, final CallbackInfo ci) {
        final ServerWorld world = this.player.getEntityWorld();
        final Entity entity = packet.getEntity(world);
        final ChunkPos chunkPos = entity != null ? entity.getChunkPos() : this.player.getChunkPos();
        if (this.ruthenium$queueInteraction(chunkPos.x, chunkPos.z,
            () -> ((ServerPlayNetworkHandler)(Object)this).onPlayerInteractEntity(packet))) {
            ci.cancel();
        }
    }

    @Redirect(method = "onPlayerInteractBlock",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/NetworkThreadUtils;forceMainThread(Lnet/minecraft/network/packet/Packet;Lnet/minecraft/network/listener/PacketListener;Lnet/minecraft/server/world/ServerWorld;)V"))
    private <T extends PacketListener> void ruthenium$allowBlockInteractionOffThread(final Packet<T> packet,
                                                                                     final T handler,
                                                                                     final ServerWorld world) {
        if (!RegionThreadUtil.isRegionThreadFor(world)) {
            NetworkThreadUtils.forceMainThread(packet, handler, world);
        }
    }

    @Redirect(method = "onPlayerInteractItem",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/NetworkThreadUtils;forceMainThread(Lnet/minecraft/network/packet/Packet;Lnet/minecraft/network/listener/PacketListener;Lnet/minecraft/server/world/ServerWorld;)V"))
    private <T extends PacketListener> void ruthenium$allowItemInteractionOffThread(final Packet<T> packet,
                                                                                    final T handler,
                                                                                    final ServerWorld world) {
        if (!RegionThreadUtil.isRegionThreadFor(world)) {
            NetworkThreadUtils.forceMainThread(packet, handler, world);
        }
    }

    @Redirect(method = "onPlayerInteractEntity",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/NetworkThreadUtils;forceMainThread(Lnet/minecraft/network/packet/Packet;Lnet/minecraft/network/listener/PacketListener;Lnet/minecraft/server/world/ServerWorld;)V"))
    private <T extends PacketListener> void ruthenium$allowEntityInteractionOffThread(final Packet<T> packet,
                                                                                      final T handler,
                                                                                      final ServerWorld world) {
        if (!RegionThreadUtil.isRegionThreadFor(world)) {
            NetworkThreadUtils.forceMainThread(packet, handler, world);
        }
    }
}
