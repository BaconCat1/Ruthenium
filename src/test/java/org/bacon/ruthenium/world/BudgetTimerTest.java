package org.bacon.ruthenium.world;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class BudgetTimerTest {

    @Test
    void pauseExcludesWaitTimeFromBudget() throws InterruptedException {
        final TickRegionScheduler.BudgetTimer timer = new TickRegionScheduler.BudgetTimer();
        final long start = timer.nanoTime();
        final long deadline = start + TimeUnit.MILLISECONDS.toNanos(5L);

        timer.pause();
        Thread.sleep(15L);
        timer.resume();

        Assertions.assertTrue(timer.nanoTime() < deadline,
            "Paused time should not be charged against the budget clock");
    }

    @Test
    void resumeReenablesTimeProgress() throws InterruptedException {
        final TickRegionScheduler.BudgetTimer timer = new TickRegionScheduler.BudgetTimer();
        timer.pause();
        Thread.sleep(10L);
        timer.resume();

        final long sampleStart = timer.nanoTime();
        Thread.sleep(10L);
        final long sampleEnd = timer.nanoTime();

        Assertions.assertTrue(sampleEnd > sampleStart,
            "Budget clock should advance again after resuming");
    }
}

