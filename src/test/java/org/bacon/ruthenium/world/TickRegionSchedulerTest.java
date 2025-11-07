package org.bacon.ruthenium.world;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Regression coverage for {@link TickRegionScheduler#tickWorld(ServerWorld, BooleanSupplier)}.
 */
class TickRegionSchedulerTest {

    private static final Identifier TEST_WORLD_ID = Identifier.of("ruthenium", "test_world");

    private TickRegionScheduler scheduler;
    private AtomicBoolean haltedFlag;

    @BeforeEach
    void setUp() throws Exception {
        this.scheduler = TickRegionScheduler.getInstance();
        this.haltedFlag = extractHaltedFlag(this.scheduler);
        this.haltedFlag.set(false);
    }

    @AfterEach
    void tearDown() {
        this.haltedFlag.set(false);
    }

    @Test
    void tickWorldFallsBackWhenSchedulerHalted() {
        final ServerWorld world = createStubWorld();
        this.haltedFlag.set(true);

        final boolean skipped = this.scheduler.tickWorld(world, () -> true);

        Assertions.assertFalse(skipped, "tickWorld should fall back to vanilla ticking when halted");
    }

    @Test
    void tickWorldFallsBackWhenServerRequestsStop() {
        final ServerWorld world = createStubWorld();
        this.haltedFlag.set(false);

        final boolean skipped = this.scheduler.tickWorld(world, () -> false);

        Assertions.assertTrue(skipped, "tickWorld should skip vanilla ticking when shouldKeepTicking is false");
    }

    private static AtomicBoolean extractHaltedFlag(final TickRegionScheduler scheduler) throws Exception {
        final Field field = TickRegionScheduler.class.getDeclaredField("halted");
        field.setAccessible(true);
        return (AtomicBoolean)field.get(scheduler);
    }

    private static ServerWorld createStubWorld() {
        final RegistryKey<World> key = RegistryKey.of(RegistryKeys.WORLD, TEST_WORLD_ID);
        return new ServerWorld(key);
    }
}
