package org.bacon.ruthenium.world;

import org.bacon.ruthenium.region.RegionTickData;
import org.bacon.ruthenium.region.ThreadedRegionizer;

/**
 * Marker interface implemented via mixin to expose Ruthenium regionizer state on server worlds.
 */
public interface RegionizedServerWorld {

    /**
     * Retrieves or creates the regionizer responsible for this world.
     *
     * @return the world-local regionizer
     */
    ThreadedRegionizer<RegionTickData, RegionTickData.RegionSectionData> ruthenium$getRegionizer();

    /**
     * Retrieves the regionized world data backing global world services.
     *
     * @return the world-level regionized data snapshot
     */
    RegionizedWorldData ruthenium$getWorldRegionData();
}
