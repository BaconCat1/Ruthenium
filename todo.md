# Ruthenium TODO

- [x] Audit existing simplified region/threading code to identify divergence from Folia reference.
- [x] Catalogue Folia classes (regionizer, schedulers, task queues, world data integrations) needed for parity.
- [x] Update build configuration to pull required Folia dependencies (concurrentutil, moonrise, etc.) or add equivalents.
- [x] Port Folia `ThreadedRegionizer` and supporting data structures into the Fabric mod package layout.
	- [x] Recreate Folia `ThreadedRegionizer` APIs, nested `ThreadedRegion`, and `ThreadedRegionSection` logic with minimal adaptation (naming + Yarn types only).
	- [x] Retrofit world bootstrap (`Ruthenium`, `ServerWorldMixin`) to construct the new regionizer signature per-world.
	- [x] Replace `RegionTickDataController` scaffolding with Folia `TickRegions` equivalent once scheduler code lands.
	- [x] Reimplement scheduler lifecycle hooks (`RegionTickScheduler` → Folia `TickRegionScheduler`) to match Folia's multithreaded model.
	- [x] Audit `RegionTickData`/region data plumbing for parity with Folia merge/split semantics (queued tasks, tick handles, stats).
	- [x] Bridge Ruthenium `RegionizerConfig` into Folia constructor requirements (section sizing, radii, thresholds).
	- [x] Update existing references (`RegionTickScheduler`, mixins, etc.) to the new nested region types.
- [x] Finish porting Folia `TickRegionScheduler` internals (full tick statistics, watchdog integration) and adapt to Fabric lifecycle.
	- [x] Flesh out `tickWorld` orchestration on the main thread (pump scheduler, sync world services, respect shouldKeepTicking).
	- [x] Port watchdog/time-budget handling and crash reporting parity from Folia.
	- [x] Track per-region tick duration statistics and expose debug hooks (command/log surface).
		- [x] Capture rolling tick duration metrics per region schedule handle.
		- [x] Expose metrics via debug commands/logging surfaces.
	- [ ] Implement RegionizedWorldData-backed world tick pump (connection ticks, mob/time state, chunk tick lists).
		- [x] Instantiate and cache `RegionizedWorldData` from `ServerWorld` mixin.
		- [x] Tick player connection services via `tickGlobalServices` during world orchestration.
		- [x] Mirror global world services (weather, raids, world border, time) through `RegionizedWorldData`.
		- [x] Port Folia's thread-safe raid manager/global tick flow and swap `RegionizedWorldData.tickRaids` to the new entry point.
		- [x] Populate mob/chunk tracking lists and redstone timers to back per-region ticks.
	- [ ] Mirror Folia RegionShutdownThread + failure escalation so scheduler halt propagates cleanly to the server watchdog.
		- [x] Introduce shutdown thread scaffold and trigger it from scheduler failure paths.
		- [ ] Halt chunk systems and finish pending teleports before final stop.
		- [ ] Save player inventories, chunks, and level data during shutdown pass.
	- [ ] Bring over RegionScheduleHandle backlog controls (`TimeUtil`, `updateTickStartToMax`) to handle long ticks and rescheduling.
		- [x] Integrate `Schedule` helper to bound tick deadlines and expose `updateTickStartToMax`.
		- [ ] Port Folia `TickData` backlog reporting structures.
		- [ ] Surface scheduler backlog metrics via commands/logging.
- [x] Replace simplified region tick scheduler (`RegionTickScheduler`, `RegionTaskDispatcher`, etc.) with Folia versions.
- [ ] Integrate scheduler entry points via mixins into server/world tick lifecycle, ensuring per-region threading mirrors Folia.
	- [ ] Reconcile `ServerWorldMixin` tick head injection with the completed `TickRegionScheduler.tickWorld` flow (respect fallback boolean).
	- [ ] Ensure vanilla chunk ticking resumes when scheduler reports false or worlds pause (reset skip flag appropriately).
	- [ ] Audit additional server tick entry points (`MinecraftServerMixin`, dimension iteration) for scheduler bootstrap/shutdown hooks.
- [ ] Port regionized world data holders (player/chunk/entity tracking) and ensure chunk load/unload hooks follow Folia logic.
	- [ ] Port Folia `RegionizedWorldData` (entity lists, block events, redstone timers, spawn state, nearby player tracker).
		- [x] Add baseline `RegionizedWorldData` class with tick metadata and connection helpers.
		- [ ] Implement entity/connection split & merge callbacks.
		- [ ] Track block events, tick lists, and mob spawning windows.
		- [ ] Mirror nearby player tracker and scheduler tick lists.
	- [x] Store `RegionizedWorldData` on the `ServerWorld` mixin and proxy key queries (`getRegionizedData`, `getNearbyPlayers`).
	- [ ] Replace Fabric chunk event handlers with regionized data-aware registration to avoid duplication and align with Folia merge/split callbacks.
- [ ] Port task queues, scheduling helpers, and teleport utilities required by Folia threading.
	- [ ] Port Folia `Schedule`, `TickData`, and related helpers that back `RegionScheduleHandle` timing APIs.
		- [x] Port `Schedule` helper and integrate with region schedule handles.
		- [ ] Port Folia `TickData`/`TickTime` backlog tracking types.
		- [ ] Ensure scheduler exposes backlog inspector APIs equivalent to Folia.
	- [ ] Implement `RegionizedData` scaffolding for per-region state transfer across merges/splits.
	- [ ] Port `TeleportUtils` and integrate with Fabric-friendly entity move handling.
- [ ] Adapt entity/chunk managers and mixins to enforce thread ownership checks as Folia does.
	- [ ] Port `RegionizedServer` thread ownership helpers and expose on server/world mixins.
	- [ ] Patch chunk/entity managers to assert current region/thread before mutating shared state.
	- [ ] Audit remaining mixins for synchronous assumptions and gate them behind region thread checks.
- [x] Configure Gradle/tooling to default to Java 21 so builds do not require manual `JAVA_HOME` overrides in the container.
- [x] Validate compilation, resolve mixin target signatures, and address refmap generation.
- [ ] Smoke-test dedicated server launch for regressions in region handling and general tick stability.

- [ ] Port per-region networking loop (connection tick, disconnect handling, broadcast queue) and integrate with Fabric network events.

- [ ] Add regression tests covering RegionTickData task queue migration across merges/splits and schedule handle reuse.
- [ ] Add integration coverage for RegionTaskDispatcher current-region scheduling fallbacks.

## Folia Reference Components

- ThreadedRegionizer & nested ThreadedRegion/ThreadedRegionSection types
- TickRegionScheduler, TickRegionScheduler.RegionScheduleHandle, TickRegionScheduler.TickTime
- TickRegions (region callbacks & region data plumbing)
- RegionizedServer utilities (tick counter, thread ownership checks)
- RegionizedWorldData and RegionStats (per-region world state tracking)
- RegionShutdownThread (graceful shutdown path)
- TeleportUtils, Schedule, TickData helpers
- Region task/queue infrastructure (chunk unload queue, holder manager hooks)
- Coordinate/TickThread utility dependencies from Moonrise/concurrentutil


Flesh out `RegionizedWorldData.tickGlobalServices` with world-border/weather/time plumbing once accessor mixins are ready.
