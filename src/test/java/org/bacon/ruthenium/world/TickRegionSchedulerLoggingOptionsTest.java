package org.bacon.ruthenium.world;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class TickRegionSchedulerLoggingOptionsTest {

    @Test
    void defaultsEnableFallbackLoggingOnly() {
        final TickRegionScheduler.LoggingOptions options =
            TickRegionScheduler.LoggingOptions.load(key -> null);

        Assertions.assertTrue(options.logFallbacks(), "Fallback logging should be enabled by default");
        Assertions.assertFalse(options.logFallbackStacks(), "Fallback stack trace logging should default to disabled");
        Assertions.assertFalse(options.logRegionSummaries(), "Region summaries should default to disabled");
        Assertions.assertFalse(options.logTaskQueueProcessing(), "Task queue logging should default to disabled");
    }

    @Test
    void propertyOverridesAreRespected() {
        final Map<String, String> values = new HashMap<>();
        values.put("ruthenium.scheduler.logFallback", "false");
        values.put("ruthenium.scheduler.logFallbackStackTraces", "true");
        values.put("ruthenium.scheduler.logDrainedTasks", "true");
        values.put("ruthenium.scheduler.logRegionSummaries", "true");
        values.put("ruthenium.scheduler.logTaskQueueProcessing", "true");

        final TickRegionScheduler.LoggingOptions options =
            TickRegionScheduler.LoggingOptions.load(values::get);

        Assertions.assertFalse(options.logFallbacks(), "Fallback logging should reflect system property override");
        Assertions.assertTrue(options.logFallbackStacks(), "Fallback stack trace logging should reflect override");
        Assertions.assertTrue(options.logTaskQueueProcessing(), "Task queue logging should reflect override");
    }

    @Test
    void invalidValuesFallBackToDefaults() {
        final Function<String, String> provider = key -> "invalid";

        final TickRegionScheduler.LoggingOptions options =
            TickRegionScheduler.LoggingOptions.load(provider);

        Assertions.assertTrue(options.logFallbacks(), "Fallback logging should retain default when value invalid");
        Assertions.assertFalse(options.logFallbackStacks(), "Fallback stack logging should retain default when value invalid");
        Assertions.assertFalse(options.logRegionSummaries(), "Region summary logging should retain default when value invalid");
    }
}
