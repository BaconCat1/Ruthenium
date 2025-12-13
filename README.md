# Ruthenium

## ⚠️ **EXPERIMENTAL SOFTWARE – USE AT YOUR OWN RISK** ⚠️

> ### 🚧 **THIS MOD IS INCOMPLETE AND UNDER ACTIVE DEVELOPMENT** 🚧
>
> **WARNING:** Ruthenium is **NOT production-ready**. This software:
> - ❌ May cause world corruption or data loss
> - ❌ Contains incomplete features and known bugs
> - ❌ Can crash your server or client unexpectedly
> - ❌ Is NOT recommended for use on worlds you care about
>
> **Use only for testing and development purposes.** Always back up your worlds.

---

Ruthenium is a **Fabric mod for Minecraft 1.21** that ports **Paper’s Folia** regionized, multithreaded tick architecture to Fabric.

It enables **true parallel world ticking** by dividing the world into independent regions, each ticked concurrently on worker threads, while strictly enforcing thread ownership and eliminating unsafe main-thread ticking paths.

This is a **deep architectural port**, not a lightweight async layer.

---

## Overview

Ruthenium provides:

- **Multithreaded World Ticking**  
  Worlds are divided into independent regions that tick concurrently on worker threads.

- **Dynamic Region Management**  
  Regions automatically merge and split based on chunk load patterns.

- **Strict Thread Ownership**  
  Chunks, entities, and block state are only accessed from their owning region thread.

- **Main Thread Decoupling**  
  The main thread orchestrates scheduling and global services but does not tick chunks, entities, or blocks.

---

## Architecture

### Core Components

- **ThreadedRegionizer**  
  Spatial partitioning with dynamic region merge/split and nested region/section types.

- **TickRegionScheduler**  
  Parallel region tick orchestration with time budgets, backlog tracking, and watchdog integration.

- **RegionTickData**  
  Per-region state container for tick queues, deadlines, and performance metrics.

- **RegionizedWorldData**  
  Thread-safe access to world-level services (time, weather, raids, world border).

- **TickRegions**  
  Lifecycle callbacks for region creation, merge, split, and destruction with state migration.

---

## Threading Model

- Each region is owned by exactly one worker thread at a time.
- Region threads **must not** access data owned by other regions.
- Cross-region operations (teleports, movement, block events, transfers) are queued and executed on the destination region.
- The main thread:
    - Orchestrates the scheduler
    - Ticks global world services
    - Coordinates chunk loading/unloading
    - **Never directly ticks chunks, entities, or block entities**

---

## Feature Status

### Implemented ✅

#### Core Infrastructure
- ✅ Folia-compatible `ThreadedRegionizer` with nested region/section types
- ✅ Full `TickRegionScheduler` with time budgets and statistics
- ✅ `TickRegions` lifecycle callbacks with data migration
- ✅ Per-region task queues and scheduling (`RegionScheduleHandle`)
- ✅ Dynamic region merge/split logic
- ✅ `Schedule` helper for tick deadline management
- ✅ Per-region tick duration metrics
- ✅ Watchdog integration and crash reporting

#### World Tick Regionalization (Folia Parity Baseline)
- ✅ All world tick categories routed through region scheduler
- ✅ Block & fluid random ticks on region threads
- ✅ Scheduled block & fluid ticks on region threads
- ✅ Block entity ticking on region threads
- ✅ Block event queues (note blocks, comparators, etc.) on region threads
- ✅ Entity AI, vehicles, and player interactions on owning region thread
- ✅ Safe region-thread chunk reads for pathfinding / `ChunkCache`
- ✅ Cross-region task transfer for movement, events, and teleports
- ✅ Explicit reporting when fallback to vanilla ticking occurs

#### World Data & Global Services
- ✅ Baseline `RegionizedWorldData`
- ✅ Thread-safe raid manager integration
- ✅ Global services ticked on orchestrator thread:
    - World border
    - Weather
    - Sleeping / night skip
    - Raids
    - Game time & daylight time
- ✅ Cached world tick snapshots

#### Thread Ownership & Validation
- ✅ `RegionizedServer` ownership helpers:
    - `isOwnedByCurrentRegion`
    - `ensureOnRegionThread`
    - `getCurrentRegion`
    - `getRegionTickCount`
- ✅ Thread assertions in `ServerWorld` mixins
- ✅ Ownership checks exposed via `RegionizedServerWorld`
- ✅ Hardened vanilla collections for region-thread access

#### Diagnostics & Tooling
- ✅ `/region info`, `/region stats`, `/region debug`
- ✅ `/ruthenium tickreport` and scheduler diagnostics
- ✅ Stall detection and recovery attempts
- ✅ Detailed scheduling and region lifecycle logging
- ✅ Java 21 Gradle setup and validated mixins

---

### In Progress 🔄

#### Scheduler Lifecycle
- 🔄 Complete `RegionShutdownThread`:
    - Graceful region shutdown
    - Cross-region teleport drain
    - Player, chunk, and world saves
    - Watchdog escalation
- 🔄 Scheduler failure detection and auto-recovery
- 🔄 Graceful fallback to main thread on critical failure

#### World State Decoupling
- 🔄 Per-region scheduled tick list mirroring and migration
- 🔄 Mob spawning windows and per-region spawn caps
- 🔄 Nearby player tracker with chunk-distance bucketing
- 🔄 Global chunk task draining and sky brightness updates
- 🔄 Moonrise-parity chunk ticket processing

#### Main Thread Elimination
- 🔄 Assertions blocking vanilla ticking paths when regions are active
- 🔄 Removal or hard-guarding of vanilla tick fallbacks
- 🔄 Metrics for fallback frequency (target: zero)

#### Thread Safety Enforcement
- 🔄 Region thread mutation assertions in `ServerChunkManager`
- 🔄 Ownership checks in `EntityTrackingManager`
- 🔄 Entity spawn context validation
- 🔄 Redstone / observer propagation safety checks
- 🔄 Command execution region scheduling validation

#### Networking
- 🔄 Per-region network tick loop
- 🔄 Region-local packet broadcast queues
- 🔄 Player connection migration across regions
- 🔄 Cross-region packet routing

#### Cross-Region Operations
- 🔄 `TeleportUtils` port
- 🔄 Region-aware portal and respawn handling
- 🔄 `RegionizedData` interface for merge/split state transfer

#### Performance & Monitoring
- 🔄 Backlog tracking with `TickData` and `TickTime`
- 🔄 Per-region backlog metrics in `/region stats`
- 🔄 Worker pool tuning and work stealing
- 🔄 Scheduler overhead profiling

#### Testing
- 🔄 Region merge/split task migration tests
- 🔄 Cross-region teleport consistency tests
- 🔄 Thread ownership violation tests
- 🔄 Graceful shutdown validation

---

## Requirements

- **Minecraft**: 1.21
- **Fabric Loader**: Latest
- **Fabric API**: Latest
- **Java**: 21+

---

## Installation

1. Download the latest release from **Releases**
2. Place the JAR in your `mods/` folder
3. Launch Minecraft with Fabric

---

## Configuration

Regionizer behavior is configured via `RegionizerConfig`:

```java
RegionizerConfig.builder()
    .emptySectionCreationRadius(2)
    .mergeRadius(2)
    .recalculationSectionCount(16)
    .maxDeadSectionPercent(0.20)
    .sectionChunkShift(4) // 4 = 16 chunks = 256 blocks
    .build();
```
It can also be configured in the config file inside fabric's config folder.

