package org.bacon.ruthenium.world;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.server.MinecraftServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bacon.ruthenium.world.RegionizedServerWorld;
import org.bacon.ruthenium.world.RegionizedWorldData;

/**
 * Minimal approximation of Folia's RegionShutdownThread that coordinates a graceful halt when the
 * region scheduler trips a fatal condition. The implementation intentionally focuses on ensuring
 * the scheduler halts and the dedicated server shutdown sequence is triggered exactly once.
 */
final class RegionShutdownThread extends Thread {

    private static final Logger LOGGER = LogManager.getLogger(RegionShutdownThread.class);
    private static final AtomicBoolean STARTED = new AtomicBoolean();

    private final MinecraftServer server;
    private final TickRegionScheduler scheduler;

    private RegionShutdownThread(final MinecraftServer server, final TickRegionScheduler scheduler) {
        super("Ruthenium-RegionShutdown");
        this.server = server;
        this.scheduler = scheduler;
    }

    static void requestShutdown(final MinecraftServer server, final TickRegionScheduler scheduler) {
        Objects.requireNonNull(scheduler, "scheduler");
        if (server == null) {
            LOGGER.warn("Region shutdown requested without a MinecraftServer instance; ignoring request");
            return;
        }
        if (!STARTED.compareAndSet(false, true)) {
            return;
        }
        final RegionShutdownThread thread = new RegionShutdownThread(server, scheduler);
        thread.setUncaughtExceptionHandler((t, throwable) ->
            LOGGER.error("Uncaught exception in region shutdown thread", throwable)
        );
        thread.start();
    }

    @Override
    public void run() {
        LOGGER.error("Region scheduler failure detected; attempting coordinated shutdown");
        final long startNanos = System.nanoTime();
        boolean escalate = false;
        try {
            this.scheduler.shutdown();
        } catch (final Throwable throwable) {
            LOGGER.error("Failure while halting region scheduler during shutdown", throwable);
            escalate = true;
        }

        try {
            LOGGER.info("Waiting for active region threads to complete before shutdown");
            for (final net.minecraft.server.world.ServerWorld world : this.server.getWorlds()) {
                if (!(world instanceof RegionizedServerWorld regionized)) {
                    continue;
                }
                final RegionizedWorldData worldData = regionized.ruthenium$getWorldRegionData();
                final boolean completed = worldData.waitForActiveRegionThreads(5000L);
                if (!completed) {
                    LOGGER.warn("Timed out waiting for region threads in world {}", world.getRegistryKey().getValue());
                    escalate = true;
                }
            }
        } catch (final Throwable throwable) {
            LOGGER.error("Failure while waiting for region threads to complete during shutdown", throwable);
            escalate = true;
        }

        try {
            LOGGER.info("Draining pending cross-region tasks before shutdown");
            int drained = 0;
            for (final net.minecraft.server.world.ServerWorld world : this.server.getWorlds()) {
                drained += RegionTaskDispatcher.drainPendingChunkTasks(world);
            }
            LOGGER.info("Drained {} pending chunk tasks during shutdown", drained);
        } catch (final Throwable throwable) {
            LOGGER.error("Failure while draining pending tasks during shutdown", throwable);
            escalate = true;
        }

        try {
            LOGGER.info("Saving player data before shutdown");
            this.server.getPlayerManager().saveAllPlayerData();
        } catch (final Throwable throwable) {
            LOGGER.error("Failed to save player data during shutdown", throwable);
            escalate = true;
        }

        try {
            LOGGER.info("Saving worlds before shutdown");
            final boolean saved = this.server.saveAll(true, true, true);
            if (!saved) {
                LOGGER.warn("Server saveAll reported incomplete save during shutdown");
            }
        } catch (final Throwable throwable) {
            LOGGER.error("Failed to save worlds during shutdown", throwable);
            escalate = true;
        }

        final long elapsedMillis = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
        LOGGER.info("Shutdown preparation complete in {}ms; requesting server stop (escalate={})",
            elapsedMillis, escalate);

        try {
            this.server.stop(escalate);
        } catch (final Throwable throwable) {
            LOGGER.error("Failed to request Minecraft server stop during region shutdown", throwable);
        }
    }
}
