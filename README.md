# Ruthenium

## ⚠️ **EXPERIMENTAL SOFTWARE - USE AT YOUR OWN RISK** ⚠️

> ### 🚧 **THIS MOD IS INCOMPLETE AND UNDER ACTIVE DEVELOPMENT** 🚧
> 
> **WARNING:** Ruthenium is **NOT production-ready**. This software:
> - ❌ **May cause world corruption or data loss**
> - ❌ **Contains incomplete features and known bugs**
> - ❌ **Can crash your server or client unexpectedly**
> - ❌ **Is NOT recommended for use on worlds you care about**
> 
> **Use only for testing and development purposes.** Always backup your worlds before use.

---

A Fabric mod for Minecraft 1.21 that implements Folia-style multithreaded region-based tick scheduling, enabling parallel world processing by dividing the world into independent regions that can tick concurrently on separate threads.

## Overview

Ruthenium ports Paper's [Folia](https://github.com/PaperMC/Folia) regionized tick architecture to Fabric, providing:

- **Multithreaded World Ticking**: Divides worlds into independent regions that tick in parallel
- **Dynamic Region Management**: Automatically merges and splits regions based on chunk load patterns
- **Thread-Safe Scheduling**: Per-region task queues with strict thread ownership enforcement
- **Performance Optimization**: Reduces main thread bottlenecks by distributing work across CPU cores

This is a complete architectural port, not just a simple parallelization layer. The mod fundamentally changes how Minecraft processes world state to enable true multithreaded execution.

## Architecture

### Core Components

- **ThreadedRegionizer**: Manages the spatial partitioning of worlds into regions and handles dynamic region merges/splits
- **TickRegionScheduler**: Coordinates parallel region ticking with time budgets, backlog tracking, and watchdog integration
- **RegionTickData**: Per-region state container tracking chunks, entities, tasks, and tick statistics
- **RegionizedWorldData**: Thread-safe world services (weather, time, raids, world border) accessible from region threads
- **TickRegions**: Lifecycle callbacks for region creation, merge, split, and destruction events

### Threading Model

Each region runs on a dedicated thread from a worker pool. Regions tick independently and cannot directly access state from other regions. Cross-region operations (entity movement, chunk loading) are coordinated through thread-safe task queues.

The main thread orchestrates the scheduler and pumps global world services, but does not block on individual region ticks.

## Features

### Implemented ✅

**Core Infrastructure:**
- ✅ Folia-compatible `ThreadedRegionizer` with nested region/section types
- ✅ Full `TickRegionScheduler` with time budgets and tick statistics
- ✅ `TickRegions` lifecycle callbacks for region creation, merge, split, and destruction
- ✅ Per-region task queues with `RegionScheduleHandle` scheduling
- ✅ Dynamic region merge/split with state migration
- ✅ `Schedule` helper for tick deadline management
- ✅ Per-region tick duration metrics and performance tracking
- ✅ Watchdog integration and crash reporting

**World Data Integration:**
- ✅ Baseline `RegionizedWorldData` with world services accessor
- ✅ Thread-safe raid manager integration
- ✅ Player connection services ticking via `tickGlobalServices`

**Development Tools:**
- ✅ Debug commands (`/region info`, `/region stats`, `/region debug`)
- ✅ Configurable section sizing and merge thresholds via `RegionizerConfig`
- ✅ Gradle configured for Java 21
- ✅ Mixin targets validated with refmap generation

### In Progress 🔄

**Scheduler Lifecycle:**
- 🔄 Fix vanilla tick flow integration (scheduler return values respected)
- 🔄 Complete `RegionShutdownThread` with graceful save sequence
- 🔄 Scheduler failure detection and recovery

**World State Decoupling:**
- 🔄 Full entity/connection split & merge callbacks
- 🔄 Per-region block events, tick lists, and mob spawning
- 🔄 Regionized weather, time, and world border tracking
- 🔄 Replace Fabric event handlers with regionized callbacks

**Thread Safety:**
- 🔄 `RegionizedServer` thread ownership validation helpers
- 🔄 Thread assertions in chunk/entity managers
- 🔄 Command execution context validation

**Networking:**
- 🔄 Per-region connection management and network tick loop
- 🔄 Per-region packet broadcast queues
- 🔄 Player movement across region boundaries

**Cross-Region Operations:**
- 🔄 `TeleportUtils` for cross-region entity movement
- 🔄 `RegionizedData` interface for state transfer during merge/split
- 🔄 Portal teleportation with region awareness

**Performance & Monitoring:**
- 🔄 Complete backlog tracking with `TickData` and `TickTime`
- 🔄 Backlog metrics via `/region stats` command
- 🔄 Worker thread pool optimization and work stealing

**Testing:**
- 🔄 Region merge/split task queue migration tests
- 🔄 Cross-region entity teleport consistency tests
- 🔄 Thread ownership enforcement validation

## Requirements

- **Minecraft**: 1.21
- **Fabric Loader**: Latest
- **Fabric API**: Latest
- **Java**: 21+

## Installation

1. Download the latest release from [Releases](../../releases)
2. Place the JAR in your `mods/` folder
3. Launch Minecraft with Fabric

## Configuration

Regionizer behavior can be tuned via `RegionizerConfig` in `Ruthenium.java`:

```java
RegionizerConfig.builder()
    .emptySectionCreationRadius(2)     // Sections to create around loaded chunks
    .mergeRadius(2)                     // Distance threshold for merging regions
    .recalculationSectionCount(16)     // Sections to recalculate per merge/split
    .maxDeadSectionPercent(0.20)       // % of empty sections before cleanup
    .sectionChunkShift(4)              // Section size (4 = 16 chunks = 256 blocks)
    .build();
```

## Commands

- `/region info` - Display current region statistics (count, threads, chunks)
- `/region stats [<region_id>]` - Show detailed tick statistics for a region
- `/region debug [on|off]` - Toggle verbose region logging

## Development

### Building

```bash
./gradlew build
```

The compiled JAR will be in `build/libs/`.

### Running

```bash
./gradlew runServer  # Dedicated server
./gradlew runClient  # Client
```

### Testing

```bash
./gradlew test
```

## Project Status

Ruthenium is currently in **active development**. Core regionizer and scheduler infrastructure is complete, but full Folia parity requires additional integration work (see [todo.md](todo.md)).

The mod is functional for testing but not yet recommended for production use.

## References

This project is based on:

- **Folia** (PaperMC) - Original regionized threading implementation
- **Moonrise** (Spottedleaf) - High-performance chunk system components
- **ConcurrentUtil** (Spottedleaf) - Lock-free data structures

Reference documentation is in [References/Reference-Folia-ver-1.21.8/](References/Reference-Folia-ver-1.21.8/).

## License

See [LICENSE](LICENSE) for details.

## Contributing

Contributions are welcome! Please ensure:

- Code follows existing style and naming conventions
- Mixins are properly documented with target signatures
- Thread safety is maintained (no shared mutable state without synchronization)
- Changes are tested against both single-player and dedicated server

## Acknowledgments

- **Spottedleaf** for the original Folia implementation and regionizer architecture
- **PaperMC** team for Paper and Folia
- **Fabric** team for the modding framework

