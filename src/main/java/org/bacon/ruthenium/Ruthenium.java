package org.bacon.ruthenium;

import net.fabricmc.api.ModInitializer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bacon.ruthenium.region.RegionTickData;
import org.bacon.ruthenium.region.RegionTickDataController;
import org.bacon.ruthenium.region.RegionizerConfig;
import org.bacon.ruthenium.region.ThreadedRegionizer;

/**
 * Fabric entrypoint that exposes the regionizer implementation for use by the rest of the mod.
 */
public final class Ruthenium implements ModInitializer {

    /** Identifier used by log messages. */
    public static final String MOD_ID = "ruthenium";
    private static final Logger LOGGER = LogManager.getLogger(MOD_ID);

    private static final ThreadedRegionizer<RegionTickData> REGIONIZER =
        new ThreadedRegionizer<>(
            RegionizerConfig.builder()
                .emptySectionCreationRadius(2)
                .mergeRadius(2)
                .recalculationSectionCount(16)
                .maxDeadSectionPercent(0.20D)
                .sectionChunkShift(4)
                .build(),
            new RegionTickDataController()
        );

    @Override
    public void onInitialize() {
        LOGGER.info("Initializing Ruthenium regionizer (section shift={}, merge radius={})",
            REGIONIZER.getConfig().getSectionChunkShift(), REGIONIZER.getConfig().getMergeRadius());
    }

    /**
     * Provides access to the global regionizer instance.
     *
     * @return the regionizer
     */
    public static ThreadedRegionizer<RegionTickData> getRegionizer() {
        return REGIONIZER;
    }
}
