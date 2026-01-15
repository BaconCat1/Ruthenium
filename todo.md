# Ruthenium TODO

## Folia Parity Checklist

### Completed
- [x] **Block events region ownership** (note blocks, synced block events): forward to owning region thread.
- [x] **Prevent cross-region neighbor updates (Folia patch 0004, partial):**
  - [x] Guard `World.updateNeighbor` (target position) to block cross-region block modifications.
  - [x] Guard `NeighborUpdater.tryNeighborUpdate` and `NeighborUpdater.replaceWithStateForNeighborUpdate`.
  - [x] Ensure guards do not trigger chunk loads from region threads (only allow if chunk is already loaded).
  - [x] Guard additional direct neighbor reads in vanilla implementations:
    - [x] `DetectorRailBlock.updateNearbyRails` neighbor state reads (AIR fallback, allows loaded non-owned reads).
    - [x] `PoweredRailBlock.isPoweredByOtherRails` recursion base case (AIR fallback, allows loaded non-owned reads).
    - [x] `RedstoneController.calculateWirePowerAt` neighbor state reads (allows loaded non-owned reads for cross-region sensing).
    - [x] `World.updateComparators` neighbor reads (allows loaded non-owned reads).
    - [x] `ComparatorBlock.calculateOutputSignal` / `ComparatorBlock.update` neighbor reads (allows loaded non-owned reads).
    - [x] `PistonHandler` block-state reads during push calculation (BEDROCK barrier for non-owned chunks).
  - [x] `ImposterProtoChunk.getBlockEntity` guarded for thread safety (Folia 0005 parity).
- [x] **Vanilla redstone within regions:** Redstone behaves 100% vanilla within a region's boundaries. Cross-region neighbor updates are blocked at TARGET position only, source position methods run normally.

### Needed (Parity Targets)
- [ ] **Folia patch 0004 remainder:** guard additional redstone/physics call sites that directly read/update neighbors without going through `World`/`NeighborUpdater` entry points.
- [ ] **Folia patch 0005+ series:** audit and port remaining thread-safety / access guards as needed for full parity.

## Main Thread Decoupling - Critical Path

### 0. Foundation (Core Infrastructure)
- [x] **Port Folia regionizer architecture**
  - [x] Port Folia `ThreadedRegionizer` with nested region/section types
  - [x] Implement `TickRegions` lifecycle callbacks and data migration
  - [x] Bridge `RegionizerConfig` into Folia constructor requirements
  - [x] Update references to new nested region types

- [x] **Implement tick scheduler**
  - [x] Port `TickRegionScheduler` with time budgets and statistics
  - [x] Create `RegionTickData` with task queues and tick tracking
  - [x] Port `Schedule` helper for tick deadline management
  - [x] Add per-region tick duration metrics
  - [x] Integrate watchdog and crash reporting

- [x] **Add baseline world data integration**
  - [x] Add baseline `RegionizedWorldData` with world services
  - [x] Implement thread-safe raid manager integration
  - [x] Cache `RegionizedWorldData` from `ServerWorld` mixin
  - [x] Tick player connection services via `tickGlobalServices`

- [x] **Setup development environment**
  - [x] Configure Gradle for Java 21
  - [x] Validate mixin targets and refmap generation
  - [x] Add debug commands (`/region info`, `/region stats`)

### 0.5 Regionalize Every Tick Type (Folia Parity Goal)
- [x] Drive **all** world tick categories through the region scheduler so no vanilla chunk/entity tick paths run on the main thread unless the scheduler falls back intentionally.
  - [x] Regionize block/fluid random ticks via `ServerWorld.tickChunk()`.
  - [x] Regionize scheduled block/fluid ticks (WorldTickScheduler) to region threads.
  - [x] Regionize block entity ticking to region threads.
  - [x] Regionize block event queues (note blocks, comparator updates, etc.) to region threads.
  - [x] Ensure entity AI, vehicle logic, and player interaction packets are processed on the owning region thread.
  - [x] Fix off-thread chunk access needed by pathfinding/ChunkCache (`ServerChunkManager` region-thread reads).
  - [x] When tasks must cross region boundaries (entities moving, block events crossing, portal/teleport), enqueue transfers so the destination region owns the follow-up work.
  - [x] Validate every fallback path so it reports when something ran globally instead of regionally, mirroring Folia’s "no cross-region tick" guarantee.
  - [x] Fix issue involving shouldKeepTicking being completely ignored by vanilla (thanks mojang) causing fallbacks to vanilla ticks.

### 1. Complete Scheduler Lifecycle Integration
- [x] **Reconcile scheduler with vanilla tick flow**
  - [x] Fix `ServerWorldMixin` to properly respect `TickRegionScheduler.tickWorld()` return value
  - [x] Ensure vanilla chunk ticking only runs when scheduler returns false (no regions active)
  - [x] Add scheduler state check to prevent double-ticking when regions are active
  - [x] Audit `MinecraftServerMixin` for proper scheduler bootstrap/shutdown hooks
  - [x] Validate dimension iteration respects per-world scheduler state

- [x] **Implement complete shutdown sequence**
  - [x] Complete `RegionShutdownThread` implementation:
    - [x] Halt all region schedulers and wait for in-flight ticks to complete
    - [x] Process pending cross-region teleports before final stop
    - [x] Save all player inventories, ender chests, and advancements
    - [x] Force-save all dirty chunks across all regions
    - [x] Save level data (world border, game rules, time, weather)
    - [x] Integrate with server watchdog for escalation on hang
  - [x] Add scheduler failure detection and auto-recovery
  - [x] Implement graceful degradation (fallback to main thread on critical failure)

### 2. Complete RegionizedWorldData - World State Decoupling
- [x] **Finish world data holder implementation**
  - [x] Implement full entity/connection split & merge callbacks in `RegionizedWorldData`
  - [x] Mirror chunk tick lists (random ticks, scheduled ticks, fluid ticks)
  - [x] Port mob spawning windows and per-region spawn caps
  - [x] Implement nearby player tracker with chunk-distance bucketing
  - [x] Add region-local scheduled tick lists with merge/split migration

- [x] **Implement Folia global tick services**
  - [x] Tick world border on the orchestrator thread.
  - [x] Advance weather cycle on the orchestrator thread.
  - [x] Tick sleeping/night-skip state on the orchestrator thread.
  - [x] Tick raids using thread-safe raid manager integration.
  - [x] Tick time progression (game time + daylight time).
  - [x] Update world tick snapshots (`updateTickData` / cached tick data).
  - [x] Drain global chunk tasks (global task queue) before region ticks.
  - [x] Update sky brightness on the orchestrator thread.
  - [x] Ensure chunk ticket updates are processed (Moonrise parity).

### 3. Eliminate Main Thread Ticking Paths
- [x] **Audit and block all vanilla tick entry points**
  - [x] Add assertions in `ServerWorld.tick()` to fail if regions are active
  - [x] Add assertions in `ServerChunkManager.tick()` for region-owned chunks
  - [x] Block `World.tickEntity()` when entity is region-owned
  - [x] Block `World.tickBlockEntities()` when chunks are region-owned
  - [x] Add logging when any vanilla tick method is called inappropriately

- [x] **Force scheduler orchestration only**
  - [x] Ensure `MinecraftServer.tickWorlds()` ONLY calls `TickRegionScheduler.tickWorld()`
  - [x] Remove or guard all vanilla ticking fallbacks
  - [x] Make fallback behavior explicitly opt-in via system property
  - [x] Add metrics for vanilla fallback frequency (should be zero in production)

- [x] **Validate main thread responsibilities**
  - [x] Main thread ONLY orchestrates scheduler
  - [x] Main thread ONLY ticks global services (weather, time, raids at world level)
  - [x] Main thread ONLY handles chunk loading/unloading coordination
  - [x] Main thread NEVER directly ticks chunks/entities/blocks
  - [x] Add thread assertions to verify these constraints

### 4. Thread Ownership & Synchronization
- [x] **Port thread ownership validation**
  - [x] Implement `RegionizedServer` thread ownership helpers:
    - [x] `isOwnedByCurrentRegion()` check for chunk/entity access
    - [x] `ensureOnRegionThread()` assertion helper
    - [x] `getCurrentRegion()` thread-local accessor
    - [x] `getRegionTickCount()` for per-region tick tracking
  - [x] Add thread validation to `ServerWorld` mixin accessors
  - [x] Expose ownership checks via `RegionizedServerWorld` interface

- [ ] **Enforce thread safety in critical paths**
  - [x] Patch `ServerChunkManager` to support region-thread chunk reads (pathfinding/ChunkCache).
  - [x] Harden vanilla collections for region-thread access (light engine queue, entity caches, chunk tracking).
  - [ ] Patch `ServerChunkManager` with region thread assertions for unsafe mutations
  - [ ] Add ownership checks to `EntityTrackingManager` mutations
  - [ ] Validate `ServerWorld.spawnEntity()` runs on correct region thread
  - [ ] Audit entity AI/goal updates for synchronous assumptions
  - [ ] Add assertions to block update propagation (redstone, observers)
  - [ ] Validate command execution context (ensure proper region scheduling)

### 5. Per-Region Networking
- [x] **Implement region-local connection management**
  - [x] Port per-region network tick loop from Folia
  - [x] Move player connection ticking to region threads
  - [x] Implement per-region packet broadcast queue
  - [x] Handle player disconnect from region thread context
  - [x] Integrate with Fabric network events (packet send/receive)
  - [x] Implement cross-region packet routing for entity tracking

- [x] **Handle player movement across regions**
  - [x] Detect when player crosses region boundary
  - [x] Queue connection transfer to target region thread
  - [x] Migrate player state (inventory, effects, statistics)
  - [x] Update entity tracking for region transition
  - [x] Handle client-side chunk loading during transfer

### 6. Cross-Region Operations
- [ ] **Implement TeleportUtils for entity movement**
  - [ ] Port Folia `TeleportUtils` with Fabric entity API
  - [ ] Queue entity teleports when crossing region boundaries
  - [ ] Handle player respawn across regions (death, bed, anchor)
  - [ ] Implement portal teleportation with region awareness
  - [ ] Migrate entity state (passengers, leashes, AI state)
  - [ ] Handle vehicle/passenger splits across region boundaries

- [ ] **Implement RegionizedData state transfer**
  - [ ] Create `RegionizedData` interface for serializable region state
  - [ ] Implement state migration during region merges:
    - [ ] Migrate pending task queues
    - [ ] Combine entity/chunk tracking lists
    - [ ] Merge tick schedules and handle conflicts
  - [ ] Implement state splitting during region division:
    - [ ] Partition entities by new region boundaries
    - [ ] Split chunk ownership
    - [ ] Divide scheduled ticks by position
  - [ ] Add validation for state consistency after merge/split

### 7. Scheduler Backlog & Performance
- [ ] **Complete backlog tracking**
  - [ ] Port Folia `TickData` class with tick time tracking
  - [ ] Implement `TickTime` utilities for time budget management
  - [ ] Add per-region backlog accumulation tracking
  - [ ] Implement `updateTickStartToMax()` for backlog recovery
  - [ ] Surface backlog metrics via `/region stats` command
  - [ ] Add warning logs when regions fall behind target TPS

- [ ] **Optimize scheduler performance**
  - [ ] Tune worker thread pool sizing (default to CPU core count)
  - [ ] Implement work stealing between idle region threads
  - [ ] Add metrics for scheduler overhead (merge/split cost)
  - [ ] Profile and optimize hot paths in tick loop
  - [ ] Add config option for max tick time budget per region

### 8. Debugging & Diagnostics
- [x] **Enhanced error handling and logging**
- [x] **Improved tick monitoring**
- [x] **Scheduler diagnostics**

- [ ] **Remaining diagnostic improvements**
  - [ ] Add thread-specific performance counters
  - [ ] Track region merge/split frequency and duration
  - [ ] Add histogram for tick duration distribution
  - [ ] Implement tick timeline visualization command
  - [ ] Add automatic thread dump on stall detection

### 9. Testing & Validation
- [ ] **Add comprehensive tests**
- [ ] **Integration testing**


---

## Folia Reference Components

**Core Infrastructure:**
- `ThreadedRegionizer` - Spatial partitioning and dynamic region management
- `TickRegionScheduler` - Parallel tick orchestration with time budgets
- `RegionScheduleHandle` - Per-region task queue and scheduling
- `TickRegions` - Region lifecycle callbacks and data migration

**State Management:**
- `RegionizedWorldData` - Per-region world services and state
- `RegionizedData` - Interface for merge/split state transfer
- `RegionStats` - Tick performance metrics and monitoring

**Thread Safety:**
- `RegionizedServer` - Thread ownership validation helpers
- `TickThread` - Thread-local region context tracking
- `RegionShutdownThread` - Graceful scheduler shutdown sequence

**Utilities:**
- `TeleportUtils` - Cross-region entity movement coordination
- `Schedule` / `TickTime` - Time budget and backlog management
- `TickData` - Tick performance tracking structures

**Dependencies:**
- Moonrise chunk system integration
- ConcurrentUtil lock-free data structures
