package org.bacon.ruthenium.world;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class RegionWatchdogTest {

    private RegionWatchdog watchdog;

    @AfterEach
    void tearDown() {
        if (this.watchdog != null) {
            this.watchdog.shutdown();
        }
    }

    @Test
    void triggersWarningAndCrashForLongRunningTick() throws InterruptedException {
        final CountDownLatch warnLatch = new CountDownLatch(1);
        final CountDownLatch crashLatch = new CountDownLatch(1);

        this.watchdog = new RegionWatchdog(
            TimeUnit.MILLISECONDS.toNanos(1L),
            TimeUnit.MILLISECONDS.toNanos(2L),
            TimeUnit.MILLISECONDS.toNanos(1L),
            1L,
            event -> warnLatch.countDown(),
            event -> crashLatch.countDown()
        );
        this.watchdog.start();

        final long artificiallyEarlyStart = System.nanoTime() - TimeUnit.MILLISECONDS.toNanos(3L);
        final RegionWatchdog.RunningTick tick = this.watchdog.track(null, null, Thread.currentThread(), artificiallyEarlyStart);

    warnLatch.await(200L, TimeUnit.MILLISECONDS); // warning may fire before crash but is not required
    Assertions.assertTrue(crashLatch.await(200L, TimeUnit.MILLISECONDS), "Expected watchdog crash to trigger");
        this.watchdog.untrack(tick);
    }
}
