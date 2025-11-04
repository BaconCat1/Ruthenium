# Ruthenium

Ruthenium is a Fabric mod library that implements a high-level regionizer inspired by Folia's
approach to dynamically partitioning the world into independent ticking regions. The codebase
focuses on concurrency-safe bookkeeping for groups of chunks ("sections") that can be ticked
independently, merged, or split as world activity changes.

## Key features

- **Thread-aware region lifecycle** &mdash; `ThreadedRegionizer` orchestrates how regions are created,
  merged, and retired while coordinating with worker threads through lifecycle states such as
  `READY`, `TICKING`, and `TRANSIENT`.
- **Configurable behavior** &mdash; `RegionizerConfig` exposes knobs for merge radii, section sizing,
  and maintenance thresholds so servers can tune responsiveness for their workloads.
- **Pluggable region data** &mdash; `RegionDataController` abstracts the life-cycle of region-local
  data. The provided `RegionTickDataController` tracks logical tick counters for each region and
  keeps them consistent when regions merge or split.
- **Deterministic section mapping** &mdash; `RegionSectionPos` converts chunk coordinates into section
  coordinates using the configured bit-shift and offers helpers for reasoning about neighborhood
  relationships.

## Getting started

### Prerequisites

- Java 21 or newer (Gradle and Fabric Loom use the toolchain configured in `build.gradle`).
- A compatible Fabric development environment if you plan to integrate the library into a mod.

### Building

Use Gradle to compile the project:

```bash
./gradlew build
```

### Running tests

The repository includes JUnit tests that exercise the regionizer. Execute them with:

```bash
./gradlew test
```

The tests cover region creation, eager and deferred merges, and ensure region tick counters always
preserve the most advanced values during merges.

## Configuration overview

`RegionizerConfig` provides a fluent builder for the most important tuning parameters:

- `emptySectionCreationRadius` controls how many empty buffer sections are provisioned around each
  populated section.
- `mergeRadius` determines how far the regionizer scans for neighboring regions when a new chunk is
  registered, directly influencing when merges occur.
- `recalculationSectionCount`, `maxDeadSectionPercent`, and `sectionChunkShift` together decide how
  aggressively regions are split or purged during maintenance cycles.

Refer to [`RegionizerConfig`](src/main/java/org/bacon/ruthenium/region/RegionizerConfig.java) and
[`ThreadedRegionizer`](src/main/java/org/bacon/ruthenium/region/ThreadedRegionizer.java) for the full
set of behaviors.

## Project structure

```
src/
├── main/java/org/bacon/ruthenium
│   ├── Ruthenium.java                  # Fabric entry point exposing the regionizer singleton
│   └── region/                         # Regionizer implementation and support classes
└── test/java/org/bacon/ruthenium       # JUnit tests validating the regionizer contract
```

## Contributing

Issues and pull requests are welcome! Please include relevant test coverage when changing the
regionizer logic so that concurrency and mapping invariants remain protected.
