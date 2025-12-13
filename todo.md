# Ruthenium TODO

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

- [ ] **Implement complete shutdown sequence**
  - [ ] Complete `RegionShutdownThread` implementation:
    - [ ] Halt all region schedulers and wait for in-flight ticks to complete
    - [ ] Process pending cross-region teleports before final stop
    - [ ] Save all player inventories, ender chests, and advancements
    - [ ] Force-save all dirty chunks across all regions
    - [ ] Save level data (world border, game rules, time, weather)
    - [ ] Integrate with server watchdog for escalation on hang
  - [ ] Add scheduler failure detection and auto-recovery
  - [ ] Implement graceful degradation (fallback to main thread on critical failure)

### 2. Complete RegionizedWorldData - World State Decoupling
- [x] **Finish world data holder implementation**
  - [x] Implement full entity/connection split & merge callbacks in `RegionizedWorldData`
  - [ ] Track per-region block events queue (note blocks, comparator updates, etc.)
  - [ ] Mirror chunk tick lists (random ticks, scheduled ticks, fluid ticks)
  - [ ] Port mob spawning windows and per-region spawn caps
  - [ ] Implement nearby player tracker with chunk-distance bucketing
  - [ ] Add region-local scheduled tick lists with merge/split migration

- [ ] **Implement Folia global tick services**
  - [x] Tick world border on the orchestrator thread.
  - [x] Advance weather cycle on the orchestrator thread.
  - [x] Tick sleeping/night-skip state on the orchestrator thread.
  - [x] Tick raids using thread-safe raid manager integration.
  - [x] Tick time progression (game time + daylight time).
  - [x] Update world tick snapshots (`updateTickData` / cached tick data).
  - [ ] Drain global chunk tasks (global task queue) before region ticks.
  - [ ] Update sky brightness on the orchestrator thread.
  - [ ] Ensure chunk ticket updates are processed (Moonrise parity).

### 3. Eliminate Main Thread Ticking Paths
- [ ] **Audit and block all vanilla tick entry points**
  - [ ] Add assertions in `ServerWorld.tick()` to fail if regions are active
  - [ ] Add assertions in `ServerChunkManager.tick()` for region-owned chunks
  - [ ] Block `World.tickEntity()` when entity is region-owned
  - [ ] Block `World.tickBlockEntities()` when chunks are region-owned
  - [ ] Add logging when any vanilla tick method is called inappropriately

- [ ] **Force scheduler orchestration only**
  - [ ] Ensure `MinecraftServer.tickWorlds()` ONLY calls `TickRegionScheduler.tickWorld()`
  - [ ] Remove or guard all vanilla ticking fallbacks
  - [ ] Make fallback behavior explicitly opt-in via system property
  - [ ] Add metrics for vanilla fallback frequency (should be zero in production)

- [ ] **Validate main thread responsibilities**
  - [ ] Main thread ONLY orchestrates scheduler
  - [ ] Main thread ONLY ticks global services (weather, time, raids at world level)
  - [ ] Main thread ONLY handles chunk loading/unloading coordination
  - [ ] Main thread NEVER directly ticks chunks/entities/blocks
  - [ ] Add thread assertions to verify these constraints

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
- [ ] **Implement region-local connection management**
  - [ ] Port per-region network tick loop from Folia
  - [ ] Move player connection ticking to region threads
  - [ ] Implement per-region packet broadcast queue
  - [ ] Handle player disconnect from region thread context
  - [ ] Integrate with Fabric network events (packet send/receive)
  - [ ] Implement cross-region packet routing for entity tracking

- [ ] **Handle player movement across regions**
  - [ ] Detect when player crosses region boundary
  - [ ] Queue connection transfer to target region thread
  - [ ] Migrate player state (inventory, effects, statistics)
  - [ ] Update entity tracking for region transition
  - [ ] Handle client-side chunk loading during transfer

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
    - [ ] Transfer block event queues
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
  - [x] Comprehensive exception catching in region tick loop
  - [x] Error tracking per region in `RegionTickMonitor`
  - [x] Stall detection and automatic recovery attempts
  - [x] Detailed scheduling state logging
  - [x] Thread dump command for deadlock diagnosis

- [x] **Improved tick monitoring**
  - [x] Enhanced `/ruthenium tickreport` with status indicators (OK/SLOW/STALLED)
  - [x] Min/max/average duration tracking per region
  - [x] Error count and last error age tracking
  - [x] Consecutive stall counter

- [x] **Scheduler diagnostics**
  - [x] Enhanced `/ruthenium dump` with thread pool state
  - [x] Proactive stall detection with detailed warnings
  - [x] Rescheduling attempt logging
  - [x] Region lifecycle state tracking

- [ ] **Remaining diagnostic improvements**
  - [ ] Add thread-specific performance counters
  - [ ] Track region merge/split frequency and duration
  - [ ] Add histogram for tick duration distribution
  - [ ] Implement tick timeline visualization command
  - [ ] Add automatic thread dump on stall detection

### 9. Testing & Validation
- [ ] **Add comprehensive tests**
  - [ ] Test region merge/split task queue migration
  - [ ] Validate schedule handle reuse after region changes
  - [ ] Test cross-region entity teleport consistency
  - [ ] Verify thread ownership enforcement catches violations
  - [ ] Test concurrent chunk loading in adjacent regions
  - [ ] Validate graceful shutdown with active regions

- [ ] **Integration testing**
  - [ ] Smoke test dedicated server startup with regionizer active
  - [ ] Test player join/leave across multiple regions
  - [ ] Validate entity AI behavior across region boundaries
  - [ ] Test redstone contraptions spanning multiple regions
  - [ ] Verify chunk generation load distribution
  - [ ] Profile memory usage under high region count

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
