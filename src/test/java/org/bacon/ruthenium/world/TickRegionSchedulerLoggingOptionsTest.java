package org.bacon.ruthenium.world;

import org.bacon.ruthenium.config.RutheniumConfig;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class TickRegionSchedulerLoggingOptionsTest {

    @Test
    void defaultsEnableFallbackLoggingOnly() {
        final TickRegionScheduler.LoggingOptions options =
            TickRegionScheduler.LoggingOptions.fromConfig(RutheniumConfig.defaults());

        Assertions.assertTrue(options.logFallbacks(), "Fallback logging should be enabled by default");
        Assertions.assertFalse(options.logFallbackStacks(), "Fallback stack trace logging should default to disabled");
        Assertions.assertFalse(options.logRegionSummaries(), "Region summaries should default to disabled");
        Assertions.assertFalse(options.logTaskQueueProcessing(), "Task queue logging should default to disabled");
    }

    @Test
    void configOverridesAreRespected() {
        final RutheniumConfig config = RutheniumConfig.defaults();
        config.logging.schedulerLogFallbacks = false;
        config.logging.schedulerLogFallbackStackTraces = true;
        config.logging.schedulerLogRegionSummaries = true;
        config.logging.schedulerLogTaskQueueProcessing = true;

        final TickRegionScheduler.LoggingOptions options =
            TickRegionScheduler.LoggingOptions.fromConfig(config);

        Assertions.assertFalse(options.logFallbacks(), "Fallback logging should reflect override");
        Assertions.assertTrue(options.logFallbackStacks(), "Fallback stack trace logging should reflect override");
        Assertions.assertTrue(options.logRegionSummaries(), "Region summary logging should reflect override");
        Assertions.assertTrue(options.logTaskQueueProcessing(), "Task queue logging should reflect override");
    }

    @Test
    void nullLoggingSectionFallsBackToDefaults() {
        final RutheniumConfig config = new RutheniumConfig();
        config.logging = null;

        final TickRegionScheduler.LoggingOptions options = TickRegionScheduler.LoggingOptions.fromConfig(config);

        Assertions.assertTrue(options.logFallbacks(), "Fallback logging should retain default when logging section missing");
        Assertions.assertFalse(options.logFallbackStacks(), "Fallback stack logging should retain default when logging section missing");
        Assertions.assertFalse(options.logRegionSummaries(), "Region summary logging should retain default when logging section missing");
    }
}
