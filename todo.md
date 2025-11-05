# Ruthenium Folia Port TODO

- [x] Audit existing simplified region/threading code to identify divergence from Folia reference.
- [x] Catalogue Folia classes (regionizer, schedulers, task queues, world data integrations) needed for parity.
- [x] Update build configuration to pull required Folia dependencies (concurrentutil, moonrise, etc.) or add equivalents.
- [ ] Port Folia `ThreadedRegionizer` and supporting data structures into the Fabric mod package layout.
	- [x] Recreate Folia `ThreadedRegionizer` APIs, nested `ThreadedRegion`, and `ThreadedRegionSection` logic with minimal adaptation (naming + Yarn types only).
	- [x] Retrofit world bootstrap (`Ruthenium`, `ServerWorldMixin`) to construct the new regionizer signature per-world.
	- [x] Replace `RegionTickDataController` scaffolding with Folia `TickRegions` equivalent once scheduler code lands.
	- [x] Reimplement scheduler lifecycle hooks (`RegionTickScheduler` → Folia `TickRegionScheduler`) to match Folia's multithreaded model.
	- [x] Audit `RegionTickData`/region data plumbing for parity with Folia merge/split semantics (queued tasks, tick handles, stats).
	- [x] Bridge Ruthenium `RegionizerConfig` into Folia constructor requirements (section sizing, radii, thresholds).
	- [x] Update existing references (`RegionTickScheduler`, mixins, etc.) to the new nested region types.
- [ ] Finish porting Folia `TickRegionScheduler` internals (full tick statistics, watchdog integration) and adapt to Fabric lifecycle.
	- [ ] Flesh out `tickWorld` orchestration on the main thread (pump scheduler, sync world services, respect shouldKeepTicking).
	- [ ] Port watchdog/time-budget handling and crash reporting parity from Folia.
	- [ ] Track per-region tick duration statistics and expose debug hooks (command/log surface).
		- [x] Capture rolling tick duration metrics per region schedule handle.
		- [ ] Expose metrics via debug commands/logging surfaces.
- [x] Replace simplified region tick scheduler (`RegionTickScheduler`, `RegionTaskDispatcher`, etc.) with Folia versions.
- [ ] Integrate scheduler entry points via mixins into server/world tick lifecycle, ensuring per-region threading mirrors Folia.
- [ ] Port regionized world data holders (player/chunk/entity tracking) and ensure chunk load/unload hooks follow Folia logic.
- [ ] Port task queues, scheduling helpers, and teleport utilities required by Folia threading.
- [ ] Adapt entity/chunk managers and mixins to enforce thread ownership checks as Folia does.
- [x] Configure Gradle/tooling to default to Java 21 so builds do not require manual `JAVA_HOME` overrides in the container.
- [x] Validate compilation, resolve mixin target signatures, and address refmap generation.
- [ ] Smoke-test dedicated server launch for regressions in region handling and general tick stability.

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
