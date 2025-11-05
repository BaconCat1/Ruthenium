package org.bacon.ruthenium.world;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.server.MinecraftServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

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
        try {
            this.scheduler.shutdown();
        } catch (final Throwable throwable) {
            LOGGER.error("Failure while halting region scheduler during shutdown", throwable);
        }

        try {
            this.server.stop(false);
        } catch (final Throwable throwable) {
            LOGGER.error("Failed to request Minecraft server stop during region shutdown", throwable);
        }
    }
}
