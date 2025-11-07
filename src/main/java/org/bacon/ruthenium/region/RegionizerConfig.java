package org.bacon.ruthenium.region;

/**
 * Configuration values controlling how aggressively the {@link ThreadedRegionizer} performs
 * maintenance operations.
 */
public final class RegionizerConfig {

    private final int recalculationSectionCount;
    private final double maxDeadSectionPercent;
    private final int emptySectionCreationRadius;
    private final int mergeRadius;
    private final int sectionChunkShift;

    private RegionizerConfig(final Builder builder) {
        this.recalculationSectionCount = builder.recalculationSectionCount;
        this.maxDeadSectionPercent = builder.maxDeadSectionPercent;
        this.emptySectionCreationRadius = builder.emptySectionCreationRadius;
        this.mergeRadius = builder.mergeRadius;
        this.sectionChunkShift = builder.sectionChunkShift;
    }

    /**
     * Returns how many sections the regionizer should process before recalculating ownership.
     *
     * @return section recalculation batch size
     */
    public int getRecalculationSectionCount() {
        return this.recalculationSectionCount;
    }

    /**
     * Returns the maximum percentage of dead sections tolerated before triggering compaction.
     *
     * @return allowed dead section ratio
     */
    public double getMaxDeadSectionPercent() {
        return this.maxDeadSectionPercent;
    }

    /**
     * Returns the radius used when creating empty sections around new region centers.
     *
     * @return section creation radius in sections
     */
    public int getEmptySectionCreationRadius() {
        return this.emptySectionCreationRadius;
    }

    /**
     * Returns the radius used when searching for candidate regions to merge.
     *
     * @return merge radius in sections
     */
    public int getMergeRadius() {
        return this.mergeRadius;
    }

    /**
     * Returns the log2 chunk shift applied when mapping chunks to sections.
     *
     * @return chunk shift magnitude
     */
    public int getSectionChunkShift() {
        return this.sectionChunkShift;
    }

    /**
     * Creates a builder pre-configured with reasonable defaults.
     *
     * @return builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Fluent builder for {@link RegionizerConfig}.
     */
    public static final class Builder {

        private int recalculationSectionCount = 8;
        private double maxDeadSectionPercent = 0.25D;
        private int emptySectionCreationRadius = 1;
        private int mergeRadius = 1;
        private int sectionChunkShift = 4;

        private Builder() {
        }

        /**
         * Sets the number of sections processed between recalculations.
         *
         * @param value new section count
         * @return {@code this} for chaining
         */
        public Builder recalculationSectionCount(final int value) {
            if (value < 1) {
                throw new IllegalArgumentException("Recalculation section count must be positive");
            }
            this.recalculationSectionCount = value;
            return this;
        }

        /**
         * Sets the threshold of dead sections tolerated before cleanup.
         *
         * @param value dead section ratio in the range [0, 1]
         * @return {@code this} for chaining
         */
        public Builder maxDeadSectionPercent(final double value) {
            if (value < 0.0D || value > 1.0D) {
                throw new IllegalArgumentException("Dead section percent must be in [0, 1]");
            }
            this.maxDeadSectionPercent = value;
            return this;
        }

        /**
         * Sets the radius used when provisioning new sections in empty areas.
         *
         * @param value section radius
         * @return {@code this} for chaining
         */
        public Builder emptySectionCreationRadius(final int value) {
            if (value < 0) {
                throw new IllegalArgumentException("Empty section creation radius must be non-negative");
            }
            this.emptySectionCreationRadius = value;
            return this;
        }

        /**
         * Sets the radius used when locating regions to merge.
         *
         * @param value merge radius
         * @return {@code this} for chaining
         */
        public Builder mergeRadius(final int value) {
            if (value < 0) {
                throw new IllegalArgumentException("Merge radius must be non-negative");
            }
            this.mergeRadius = value;
            return this;
        }

        /**
         * Sets how many chunk bits to shift when mapping chunk coordinates to section coordinates.
         *
         * @param value chunk shift magnitude
         * @return {@code this} for chaining
         */
        public Builder sectionChunkShift(final int value) {
            if (value < 0 || value > 10) {
                throw new IllegalArgumentException("Section chunk shift must be between 0 and 10");
            }
            this.sectionChunkShift = value;
            return this;
        }

        /**
         * Builds an immutable configuration instance.
         *
         * @return new configuration instance
         */
        public RegionizerConfig build() {
            return new RegionizerConfig(this);
        }
    }
}
