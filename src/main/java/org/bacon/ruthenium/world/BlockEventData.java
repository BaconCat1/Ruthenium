package org.bacon.ruthenium.world;

import net.minecraft.block.Block;
import net.minecraft.util.math.BlockPos;
import java.util.Objects;

/**
 * Value class representing a block event to be processed on a region thread.
 * This mirrors Minecraft's internal BlockEventData but is accessible for
 * regionized processing.
 */
public record BlockEventData(BlockPos pos, Block block, int eventId, int eventParam) {

    public BlockEventData {
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(block, "block");
    }

    /**
     * Returns the chunk X coordinate for this block event.
     */
    public int chunkX() {
        return this.pos.getX() >> 4;
    }

    /**
     * Returns the chunk Z coordinate for this block event.
     */
    public int chunkZ() {
        return this.pos.getZ() >> 4;
    }
}

