package org.bacon.ruthenium.mixin;

import java.util.concurrent.atomic.AtomicLong;

import net.minecraft.util.math.random.CheckedRandom;
import net.minecraft.util.math.random.GaussianGenerator;
import net.minecraft.util.math.random.Random;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Allows {@link CheckedRandom} to operate on multiple threads by giving each thread an independent
 * pseudo-random state and gaussian generator cache.
 */
@Mixin(CheckedRandom.class)
public abstract class CheckedRandomMixin {

    @Shadow @Final private static long MULTIPLIER;
    @Shadow @Final private static long INCREMENT;
    @Shadow @Final private static long SEED_MASK;

    @Shadow @Final private AtomicLong seed;
    @Shadow @Final private GaussianGenerator gaussianGenerator;

    @Unique
    private ThreadLocal<AtomicLong> ruthenium$threadSeed;

    @Unique
    private ThreadLocal<GaussianGenerator> ruthenium$threadGaussian;

    @Unique
    private ThreadLocal<AtomicLong> ruthenium$getThreadSeed() {
        ThreadLocal<AtomicLong> threadLocal = this.ruthenium$threadSeed;
        if (threadLocal == null) {
            threadLocal = ThreadLocal.withInitial(() -> new AtomicLong(this.seed.get()));
            this.ruthenium$threadSeed = threadLocal;
        }
        return threadLocal;
    }

    @Unique
    private ThreadLocal<GaussianGenerator> ruthenium$getThreadGaussian() {
        ThreadLocal<GaussianGenerator> threadLocal = this.ruthenium$threadGaussian;
        if (threadLocal == null) {
            threadLocal = ThreadLocal.withInitial(() -> new GaussianGenerator((Random)(Object)this));
            this.ruthenium$threadGaussian = threadLocal;
        }
        return threadLocal;
    }

    @Inject(method = "setSeed(J)V", at = @At("HEAD"), cancellable = true)
    private void ruthenium$setSeed(final long seed, final CallbackInfo ci) {
        final long maskedSeed = (seed ^ MULTIPLIER) & SEED_MASK;
        this.seed.set(maskedSeed);
        this.ruthenium$getThreadSeed().get().set(maskedSeed);
        this.gaussianGenerator.reset();
        this.ruthenium$getThreadGaussian().get().reset();
        ci.cancel();
    }

    @Inject(method = "next(I)I", at = @At("HEAD"), cancellable = true)
    private void ruthenium$next(final int bits, final CallbackInfoReturnable<Integer> cir) {
        final AtomicLong threadSeed = this.ruthenium$getThreadSeed().get();
        final long current = threadSeed.get();
        final long next = (current * MULTIPLIER + INCREMENT) & SEED_MASK;
        threadSeed.set(next);
        this.seed.set(next);
        cir.setReturnValue((int)(next >>> (48 - bits)));
    }

    @Inject(method = "nextGaussian()D", at = @At("HEAD"), cancellable = true)
    private void ruthenium$nextGaussian(final CallbackInfoReturnable<Double> cir) {
        cir.setReturnValue(this.ruthenium$getThreadGaussian().get().next());
    }
}
