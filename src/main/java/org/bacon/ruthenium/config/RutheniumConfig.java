package org.bacon.ruthenium.config;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Objects;
import org.bacon.ruthenium.debug.RegionDebug;
import org.bacon.ruthenium.region.RegionizerConfig;

/**
 * Versioned JSON configuration for Ruthenium.
 *
 * <p>The schema is intentionally plain (Gson-serializable) so it can be edited without
 * additional libraries.</p>
 */
public final class RutheniumConfig {

    public static final int CURRENT_VERSION = 1;

    public int version = CURRENT_VERSION;
    public Regionizer regionizer = new Regionizer();
    public Scheduler scheduler = new Scheduler();
    public Logging logging = new Logging();
    public Debug debug = new Debug();
    public Fallback fallback = new Fallback();

    public static RutheniumConfig defaults() {
        return new RutheniumConfig();
    }

    public RutheniumConfig validated() {
        if (this.version <= 0) {
            this.version = CURRENT_VERSION;
        }
        this.regionizer = Objects.requireNonNullElseGet(this.regionizer, Regionizer::new);
        this.scheduler = Objects.requireNonNullElseGet(this.scheduler, Scheduler::new);
        this.logging = Objects.requireNonNullElseGet(this.logging, Logging::new);
        this.debug = Objects.requireNonNullElseGet(this.debug, Debug::new);
        this.fallback = Objects.requireNonNullElseGet(this.fallback, Fallback::new);

        this.regionizer.recalculationSectionCount = clampMin(this.regionizer.recalculationSectionCount, 1);
        this.regionizer.emptySectionCreationRadius = clampMin(this.regionizer.emptySectionCreationRadius, 0);
        this.regionizer.mergeRadius = clampMin(this.regionizer.mergeRadius, 0);
        this.regionizer.sectionChunkShift = clamp(this.regionizer.sectionChunkShift, 0, 10);
        this.regionizer.maxDeadSectionPercent = clampDouble(this.regionizer.maxDeadSectionPercent, 0.0D, 1.0D);

        this.scheduler.threadCount = clampMin(this.scheduler.threadCount, -1);
        this.scheduler.watchdogWarnSeconds = clampMin(this.scheduler.watchdogWarnSeconds, 1L);
        this.scheduler.watchdogCrashSeconds = clampMin(this.scheduler.watchdogCrashSeconds, 1L);
        this.scheduler.watchdogLogIntervalSeconds = clampMin(this.scheduler.watchdogLogIntervalSeconds, 1L);
        this.scheduler.watchdogPollMillis = clampMin(this.scheduler.watchdogPollMillis, 1L);
        this.scheduler.mainThreadWarnMillis = clampMin(this.scheduler.mainThreadWarnMillis, 1L);
        this.scheduler.mainThreadCrashSeconds = clampMin(this.scheduler.mainThreadCrashSeconds, 1L);
        this.scheduler.regionStallSeconds = clampMin(this.scheduler.regionStallSeconds, 1L);

        this.scheduler.maxScheduledTicksPerRegion = clampMin(this.scheduler.maxScheduledTicksPerRegion, 1);

        return this;
    }

    public RegionizerConfig toRegionizerConfig() {
        return RegionizerConfig.builder()
            .emptySectionCreationRadius(this.regionizer.emptySectionCreationRadius)
            .mergeRadius(this.regionizer.mergeRadius)
            .recalculationSectionCount(this.regionizer.recalculationSectionCount)
            .maxDeadSectionPercent(this.regionizer.maxDeadSectionPercent)
            .sectionChunkShift(this.regionizer.sectionChunkShift)
            .build();
    }

    public EnumSet<RegionDebug.LogCategory> enabledDebugCategories() {
        final EnumSet<RegionDebug.LogCategory> set = EnumSet.noneOf(RegionDebug.LogCategory.class);
        if (this.debug.enableLifecycleLogs) {
            set.add(RegionDebug.LogCategory.LIFECYCLE);
        }
        if (this.debug.enableMovementLogs) {
            set.add(RegionDebug.LogCategory.MOVEMENT);
        }
        if (this.debug.enableSchedulerLogs) {
            set.add(RegionDebug.LogCategory.SCHEDULER);
        }
        return set;
    }

    public String describe() {
        return String.format(Locale.ROOT,
            "version=%d regionizer(sectionShift=%d, mergeRadius=%d) scheduler(threadCount=%d, maxScheduledTicksPerRegion=%d)",
            this.version,
            this.regionizer.sectionChunkShift,
            this.regionizer.mergeRadius,
            this.scheduler.threadCount,
            this.scheduler.maxScheduledTicksPerRegion);
    }

    public static final class Regionizer {
        public int recalculationSectionCount = 16;
        public double maxDeadSectionPercent = 0.20D;
        public int emptySectionCreationRadius = 1;
        public int mergeRadius = 1;
        public int sectionChunkShift = 4;
    }

    public static final class Scheduler {
        /**
         * Worker thread count. Use -1 for Ruthenium's auto sizing.
         */
        public int threadCount = -1;

        public long watchdogWarnSeconds = 10L;
        public long watchdogCrashSeconds = 60L;
        public long watchdogLogIntervalSeconds = 5L;
        public long watchdogPollMillis = 1000L;

        public long mainThreadWarnMillis = 200L;
        public long mainThreadCrashSeconds = 60L;

        public long regionStallSeconds = 5L;

        /**
         * Per-region cap when executing scheduled block/fluid ticks.
         */
        public int maxScheduledTicksPerRegion = 65536;
    }

    public static final class Logging {
        public boolean schedulerVerbose = false;
        public boolean schedulerLogFallbacks = true;
        public boolean schedulerLogFallbackStackTraces = false;
        public boolean schedulerLogRegionSummaries = false;
        public boolean schedulerLogTaskQueueProcessing = false;
    }

    public static final class Debug {
        public boolean enableLifecycleLogs = false;
        public boolean enableMovementLogs = false;
        public boolean enableSchedulerLogs = false;
    }

    public static final class Fallback {
        public boolean logFallbacks = false;
        public boolean assertMode = false;
    }

    private static int clampMin(final int value, final int min) {
        return Math.max(min, value);
    }

    private static long clampMin(final long value, final long min) {
        return Math.max(min, value);
    }

    private static int clamp(final int value, final int min, final int max) {
        return Math.min(max, Math.max(min, value));
    }

    private static double clampDouble(final double value, final double min, final double max) {
        return Math.min(max, Math.max(min, value));
    }
}
