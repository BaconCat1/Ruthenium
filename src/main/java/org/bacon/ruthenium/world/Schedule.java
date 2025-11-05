package org.bacon.ruthenium.world;

/**
 * Port of Folia's {@code Schedule} helper used to track periodic deadlines with bounded drift.
 */
public final class Schedule {

    private long lastPeriod;

    public Schedule(final long firstPeriod) {
        this.lastPeriod = firstPeriod;
    }

    public void setLastPeriod(final long value) {
        this.lastPeriod = value;
    }

    public long getLastPeriod() {
        return this.lastPeriod;
    }

    public int getPeriodsAhead(final long periodLength, final long time) {
        final long difference = time - this.lastPeriod;
        final int ret = (int)(Math.abs(difference) / periodLength);
        return difference >= 0L ? ret : -ret;
    }

    public long getDeadline(final long periodLength) {
        return this.lastPeriod + periodLength;
    }

    public void setNextPeriod(final long nextPeriod, final long periodLength) {
        this.lastPeriod = nextPeriod - periodLength;
    }

    public void advanceBy(final int periods, final long periodLength) {
        this.lastPeriod += (long)periods * periodLength;
    }

    public void setPeriodsAhead(final int periodsToBeAhead, final long periodLength, final long time) {
        final int periodsAhead = this.getPeriodsAhead(periodLength, time);
        final int periodsToAdd = periodsToBeAhead - periodsAhead;
        this.lastPeriod -= (long)periodsToAdd * periodLength;
    }
}
