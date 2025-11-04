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

    public int getRecalculationSectionCount() {
        return this.recalculationSectionCount;
    }

    public double getMaxDeadSectionPercent() {
        return this.maxDeadSectionPercent;
    }

    public int getEmptySectionCreationRadius() {
        return this.emptySectionCreationRadius;
    }

    public int getMergeRadius() {
        return this.mergeRadius;
    }

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

        public Builder recalculationSectionCount(final int value) {
            if (value < 1) {
                throw new IllegalArgumentException("Recalculation section count must be positive");
            }
            this.recalculationSectionCount = value;
            return this;
        }

        public Builder maxDeadSectionPercent(final double value) {
            if (value < 0.0D || value > 1.0D) {
                throw new IllegalArgumentException("Dead section percent must be in [0, 1]");
            }
            this.maxDeadSectionPercent = value;
            return this;
        }

        public Builder emptySectionCreationRadius(final int value) {
            if (value < 0) {
                throw new IllegalArgumentException("Empty section creation radius must be non-negative");
            }
            this.emptySectionCreationRadius = value;
            return this;
        }

        public Builder mergeRadius(final int value) {
            if (value < 0) {
                throw new IllegalArgumentException("Merge radius must be non-negative");
            }
            this.mergeRadius = value;
            return this;
        }

        public Builder sectionChunkShift(final int value) {
            if (value < 0 || value > 10) {
                throw new IllegalArgumentException("Section chunk shift must be between 0 and 10");
            }
            this.sectionChunkShift = value;
            return this;
        }

        public RegionizerConfig build() {
            return new RegionizerConfig(this);
        }
    }
}
