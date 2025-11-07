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

### 1. Complete Scheduler Lifecycle Integration
- [ ] **Reconcile scheduler with vanilla tick flow**
  - [x] Fix `ServerWorldMixin` to properly respect `TickRegionScheduler.tickWorld()` return value
  - [ ] Ensure vanilla chunk ticking only runs when scheduler returns false (no regions active)
  - [ ] Add scheduler state check to prevent double-ticking when regions are active
  - [ ] Audit `MinecraftServerMixin` for proper scheduler bootstrap/shutdown hooks
  - [ ] Validate dimension iteration respects per-world scheduler state

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
- [ ] **Finish world data holder implementation**
  - [ ] Implement full entity/connection split & merge callbacks in `RegionizedWorldData`
  - [ ] Track per-region block events queue (note blocks, comparator updates, etc.)
  - [ ] Mirror chunk tick lists (random ticks, scheduled ticks, fluid ticks)
  - [ ] Port mob spawning windows and per-region spawn caps
  - [ ] Implement nearby player tracker with chunk-distance bucketing
  - [ ] Add region-local scheduled tick lists with merge/split migration

- [ ] **Decouple global world services**
  - [ ] Move weather state to per-region tracking (precipitation, thunder)
  - [ ] Regionize world time progression (handle sleeping, time skipping)
  - [ ] Split world border into per-region collision checks
  - [ ] Implement per-region day/night cycle tracking
  - [ ] Port raid manager to use regionized player tracking
  - [ ] Handle wandering trader spawning per-region

- [ ] **Replace Fabric event handlers with regionized callbacks**
  - [ ] Remove direct `ServerChunkEvents` registration in `Ruthenium.java`
  - [ ] Route chunk load/unload through `TickRegions` callbacks
  - [ ] Ensure entity load/unload triggers region data updates
  - [ ] Align player join/leave with region connection tracking
  - [ ] Integrate block event registration with region event queues

### 3. Thread Ownership & Synchronization
- [ ] **Port thread ownership validation**
  - [ ] Implement `RegionizedServer` thread ownership helpers:
    - [ ] `isOwnedByCurrentRegion()` check for chunk/entity access
    - [ ] `ensureOnRegionThread()` assertion helper
    - [ ] `getCurrentRegion()` thread-local accessor
    - [ ] `getRegionTickCount()` for per-region tick tracking
  - [ ] Add thread validation to `ServerWorld` mixin accessors
  - [ ] Expose ownership checks via `RegionizedServerWorld` interface

- [ ] **Enforce thread safety in critical paths**
  - [ ] Patch `ServerChunkManager` with region thread assertions
  - [ ] Add ownership checks to `EntityTrackingManager` mutations
  - [ ] Validate `ServerWorld.spawnEntity()` runs on correct region thread
  - [ ] Audit entity AI/goal updates for synchronous assumptions
  - [ ] Add assertions to block update propagation (redstone, observers)
  - [ ] Validate command execution context (ensure proper region scheduling)

### 4. Per-Region Networking
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

### 5. Cross-Region Operations
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

### 6. Scheduler Backlog & Performance
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

### 7. Testing & Validation
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
