package org.bacon.ruthenium.world;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import net.minecraft.server.world.ServerWorld;

/**
 * Background watchdog that observes long-running region ticks and reports
 * warnings or crashes when time budgets are exceeded.
 */
public final class RegionWatchdog implements AutoCloseable {

    /** Sentinel indicating that logging has not yet occurred. */
    private static final long NO_LOG = Long.MIN_VALUE;

    /** Encapsulates a single tracked tick execution. */
    public static final class RunningTick {

        private final ServerWorld world;
        private final TickRegionScheduler.RegionScheduleHandle handle;
        private final Thread thread;
        private final long startNanos;
        private final AtomicLong lastLoggedNanos;
        private final AtomicBoolean crashed;

        RunningTick(final ServerWorld world,
                    final TickRegionScheduler.RegionScheduleHandle handle,
                    final Thread thread,
                    final long startNanos) {
            this.world = world;
            this.handle = handle;
            this.thread = thread;
            this.startNanos = startNanos;
            this.lastLoggedNanos = new AtomicLong(NO_LOG);
            this.crashed = new AtomicBoolean(false);
        }

        public ServerWorld world() {
            return this.world;
        }

        public TickRegionScheduler.RegionScheduleHandle handle() {
            return this.handle;
        }

        public Thread thread() {
            return this.thread;
        }

        public long startNanos() {
            return this.startNanos;
        }

        boolean markCrashed() {
            return this.crashed.compareAndSet(false, true);
        }

        boolean isCrashed() {
            return this.crashed.get();
        }

        boolean shouldLog(final long now, final long intervalNanos) {
            if (intervalNanos <= 0L) {
                this.lastLoggedNanos.set(now);
                return true;
            }
            for (;;) {
                final long previous = this.lastLoggedNanos.get();
                if (previous == NO_LOG) {
                    if (this.lastLoggedNanos.compareAndSet(NO_LOG, now)) {
                        return true;
                    }
                    continue;
                }
                if (now - previous < intervalNanos) {
                    return false;
                }
                if (this.lastLoggedNanos.compareAndSet(previous, now)) {
                    return true;
                }
            }
        }
    }

    /**
     * Event emitted when a tracked tick exceeds a configured time budget.
     */
    public static final class Event {

        private final RunningTick tick;
        private final long durationNanos;

        Event(final RunningTick tick, final long durationNanos) {
            this.tick = tick;
            this.durationNanos = durationNanos;
        }

        public RunningTick tick() {
            return this.tick;
        }

        public long durationNanos() {
            return this.durationNanos;
        }
    }

    private final Set<RunningTick> ticks = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final long warnAfterNanos;
    private final long crashAfterNanos;
    private final long logIntervalNanos;
    private final long pollIntervalMillis;
    private final Consumer<Event> warnConsumer;
    private final Consumer<Event> crashConsumer;
    private final Thread thread;
    private final AtomicBoolean running = new AtomicBoolean();

    public RegionWatchdog(final long warnAfterNanos,
                          final long crashAfterNanos,
                          final long logIntervalNanos,
                          final long pollIntervalMillis,
                          final Consumer<Event> warnConsumer,
                          final Consumer<Event> crashConsumer) {
        if (pollIntervalMillis <= 0L) {
            throw new IllegalArgumentException("pollIntervalMillis must be > 0");
        }
        this.warnAfterNanos = warnAfterNanos;
        this.crashAfterNanos = crashAfterNanos;
        this.logIntervalNanos = logIntervalNanos;
        this.pollIntervalMillis = pollIntervalMillis;
        this.warnConsumer = Objects.requireNonNull(warnConsumer, "warnConsumer");
        this.crashConsumer = Objects.requireNonNull(crashConsumer, "crashConsumer");
        this.thread = new Thread(this::runLoop, "Ruthenium-Region-Watchdog");
        this.thread.setDaemon(true);
    }

    /**
     * Starts the watchdog thread if it is not already active.
     */
    public void start() {
        if (this.running.compareAndSet(false, true)) {
            this.thread.start();
        }
    }

    /**
     * Registers a new tick for observation.
     */
    public RunningTick track(final ServerWorld world,
                             final TickRegionScheduler.RegionScheduleHandle handle,
                             final Thread thread,
                             final long startNanos) {
        final RunningTick tick = new RunningTick(world, handle, thread,
            startNanos == 0L ? System.nanoTime() : startNanos);
        this.ticks.add(tick);
        return tick;
    }

    /**
     * Stops observing the supplied tick handle.
     */
    public void untrack(final RunningTick tick) {
        if (tick != null) {
            this.ticks.remove(tick);
        }
    }

    /**
     * Indicates whether the watchdog thread has been shut down.
     */
    public boolean isShutdown() {
        return !this.running.get();
    }

    private void runLoop() {
        while (this.running.get()) {
            try {
                Thread.sleep(this.pollIntervalMillis);
            } catch (final InterruptedException ignored) {
                // Exit promptly when shutdown
                if (!this.running.get()) {
                    break;
                }
            }

            if (this.ticks.isEmpty()) {
                continue;
            }

            final long now = System.nanoTime();
            for (final RunningTick tick : this.ticks) {
                final long duration = now - tick.startNanos();

                if (this.crashAfterNanos > 0L && duration >= this.crashAfterNanos) {
                    if (tick.markCrashed()) {
                        this.ticks.remove(tick);
                        this.crashConsumer.accept(new Event(tick, duration));
                    }
                    continue;
                }

                if (this.warnAfterNanos > 0L && duration >= this.warnAfterNanos && tick.shouldLog(now, this.logIntervalNanos)) {
                    this.warnConsumer.accept(new Event(tick, duration));
                }
            }
        }
    }

    @Override
    public void close() {
        this.shutdown();
    }

    /**
     * Terminates the watchdog thread.
     */
    public void shutdown() {
        if (this.running.compareAndSet(true, false)) {
            this.thread.interrupt();
            try {
                this.thread.join(TimeUnit.SECONDS.toMillis(5L));
            } catch (final InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
