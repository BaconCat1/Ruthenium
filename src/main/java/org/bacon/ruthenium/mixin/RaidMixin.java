package org.bacon.ruthenium.mixin;

import net.minecraft.advancement.criterion.Criteria;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.raid.RaiderEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.stat.Stat;
import net.minecraft.stat.Stats;
import net.minecraft.util.math.BlockPos;
import net.minecraft.village.raid.Raid;
import org.bacon.ruthenium.mixin.accessor.RaidThreadSafe;
import org.bacon.ruthenium.world.RegionThreadUtil;
import org.bacon.ruthenium.world.TickRegionScheduler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.Overwrite;

@Mixin(Raid.class)
public abstract class RaidMixin implements RaidThreadSafe {

    @Shadow public abstract BlockPos getCenter();

    @Overwrite
    private java.util.function.Predicate<ServerPlayerEntity> validPlayer() {
        return player -> {
            if (player == null || !player.isAlive()) {
                return false;
            }
            if (!RegionThreadUtil.ownsPlayer(player, 8)) {
                return false;
            }
            final BlockPos pos = player.getBlockPos();
            final ServerWorld world = TickRegionScheduler.getCurrentWorld();
            if (world == null) return false;
            return world.getRaidAt(pos) == (Raid)(Object)this;
        };
    }

    @Override
    public boolean ruthenium$ownsRaid(final ServerWorld world) {
        if (world == null) {
            return false;
        }
        final BlockPos center = this.getCenter();
        if (center == null) {
            return false;
        }
        return RegionThreadUtil.ownsPosition(world, center, 8);
    }

    @Inject(method = "addRaider", at = @At("HEAD"), cancellable = true)
    private void ruthenium$guardAddRaider(final ServerWorld world, final int wave, final RaiderEntity raider, final BlockPos pos, final boolean existing, final CallbackInfo ci) {
        if (!this.ruthenium$ownsRaid(world)) {
            ci.cancel();
        }
    }

    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/LivingEntity;addStatusEffect(Lnet/minecraft/entity/effect/StatusEffectInstance;)Z"))
    private boolean ruthenium$scheduleHeroEffect(final LivingEntity living, final StatusEffectInstance instance) {
    final StatusEffectInstance copy = new StatusEffectInstance(instance);
            if (living instanceof ServerPlayerEntity player) {
            RegionThreadUtil.scheduleOnPlayer(player, () -> {
                living.addStatusEffect(new StatusEffectInstance(copy));
                player.incrementStat(Stats.RAID_WIN);
                net.minecraft.advancement.criterion.Criteria.HERO_OF_THE_VILLAGE.trigger(player);
            });
        } else {
            final ServerWorld world = TickRegionScheduler.getCurrentWorld();
            if (world != null) {
                final BlockPos pos = living.getBlockPos();
                RegionThreadUtil.scheduleOnChunk(world, pos.getX() >> 4, pos.getZ() >> 4, () -> living.addStatusEffect(new StatusEffectInstance(copy)));
            }
        }
        return true;
    }

    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/network/ServerPlayerEntity;incrementStat(Lnet/minecraft/stat/Stat;)V"))
    private void ruthenium$skipImmediateStat(final ServerPlayerEntity player, final Stat<?> stat) {
        // handled asynchronously in ruthenium$scheduleHeroEffect
    }

    @Redirect(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/advancement/criterion/HeroOfTheVillageCriterion;trigger(Lnet/minecraft/server/network/ServerPlayerEntity;)V"))
    private void ruthenium$skipImmediateCriterion(final Object criterion, final ServerPlayerEntity player) {
        // handled asynchronously in ruthenium$scheduleHeroEffect
    }
}
