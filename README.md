# Ruthenium

Ruthenium is a Fabric mod library that replaces the vanilla chunk tick loop with a Folia‑inspired, region‑based scheduler. It partitions the world into independent ticking regions and runs them on a small pool of threads while preserving Minecraft’s game logic.

# Here be dragons (read before using)

- Experimental and unstable: APIs and behavior may change without notice.
- Multithreaded tick path: many vanilla/Fabric APIs are not thread‑safe. Code that assumes single‑threaded world access can crash, deadlock, or corrupt data when run on region threads.
- For test servers only: back up your worlds. Expect bugs and incompatibilities with mods that touch world state without considering region threading.
- This is not Paper/Folia: no plugin compat layer; Fabric mods must explicitly respect thread ownership and schedule work via region queues.

## What it does

- Builds a per‑world `ThreadedRegionizer` when chunks load/unload, grouping nearby chunks into “regions” that can merge/split as activity changes.
- Replaces `ServerWorld`’s normal chunk ticking with a region scheduler that ticks the chunks owned by each active region at 20 TPS across a few worker threads.
- Keeps region‑local bookkeeping (tick counters, queued per‑chunk tasks, membership of chunks) consistent across merges/splits.
- Exposes lightweight stats for each region’s recent tick durations.

## What it doesn’t do

- It’s not a fork of Paper/Folia, and it doesn’t bring their plugin environment. This is a Fabric‑first library.
- It doesn’t change gameplay rules; it changes how chunk ticks are scheduled and isolated.
- It doesn’t attempt to parallelize everything in Minecraft—only region chunk ticking and region‑queued tasks.

## How it works (high level)

- Mixins wire into `ServerWorld`:
  - `ServerWorldMixin` marks the world as `RegionizedServerWorld` and guards vanilla `tickChunk` so Ruthenium can own chunk ticking.
  - On each world tick, `TickRegionScheduler` decides which regions to tick and runs them on a scheduler thread pool.
- Chunk lifecycle hooks:
  - Ruthenium registers Fabric `ServerChunkEvents` to feed the `ThreadedRegionizer` with add/removeChunk events as chunks load/unload.
- Per‑region tick:
  - For each region, `RegionTickData` provides: current tick counters, a set of owned chunks, and a `RegionTaskQueue`.
  - During a tick, the scheduler runs queued per‑chunk tasks, then calls the world’s internal `tickChunk` for each owned `WorldChunk`, then advances counters.
- Safety and determinism:
  - Regions have lifecycle states (`READY`, `TICKING`, `TRANSIENT`, `DEAD`). While a region ticks, merges are deferred; on release, merges/splits are applied consistently.

## Key classes to look at

- `ThreadedRegionizer` and `ThreadedRegion`: region graph management, merges/splits, invariants.
- `RegionizerConfig`: knobs for section sizing, merge radius, maintenance thresholds.
- `RegionTickData` and `RegionTaskQueue`: region‑local data, per‑chunk task queue, tick counters, stats.
- `TickRegionScheduler`: worker threads, 20 TPS cadence, region tick loop and context.
- `RegionizedServerWorld` and `RegionChunkTickAccess`: mixin interfaces added to `ServerWorld` so Ruthenium can manage world/region state and guard vanilla ticking.

## Supported versions

- Minecraft: 1.21.10
- Fabric Loader: see `gradle.properties` (loader_version)
- Fabric API: see `gradle.properties` (fabric_version)
- Java: 21+

## Getting started

### Prerequisites

- Java 21 or newer.
- A Fabric mod development environment.

### Build

```bash
./gradlew build
```

### Run tests

```bash
./gradlew test
```

### Optional dev run (standard Loom tasks; this project focuses on server‑side behavior)

```bash
./gradlew runServer
```

## Using from your mod (quick pointers)

- Get the world’s regionizer (ServerWorld is extended by a mixin):
  - `((RegionizedServerWorld) world).ruthenium$getRegionizer()`
- Map a chunk to its region:
  - `regionizer.getRegionForChunk(chunkX, chunkZ)`
- Queue work to run next time a specific chunk in a region ticks:
  - `region.getData().getTaskQueue().queueChunkTask(chunkX, chunkZ, runnable)`
- Peek at simple per‑region timing stats:
  - `region.getData().getTickStats()`

Note: You don’t need to schedule region ticks yourself—Ruthenium installs a scheduler that replaces vanilla chunk ticking automatically when the mod is present.

## Configuration overview

`RegionizerConfig` provides a fluent builder for tuning parameters:

- `emptySectionCreationRadius`: how many empty buffer sections to maintain around populated sections.
- `mergeRadius`: the neighborhood radius used to decide when regions merge.
- `recalculationSectionCount`, `maxDeadSectionPercent`, `sectionChunkShift`: thresholds for maintenance and section sizing.

See:

- [`src/main/java/org/bacon/ruthenium/region/RegionizerConfig.java`](src/main/java/org/bacon/ruthenium/region/RegionizerConfig.java)
- [`src/main/java/org/bacon/ruthenium/region/ThreadedRegionizer.java`](src/main/java/org/bacon/ruthenium/region/ThreadedRegionizer.java)

## Project structure

```
src/
├── main/java/org/bacon/ruthenium
│   ├── Ruthenium.java                  # Fabric entry point and default wiring
│   ├── region/                         # Regionizer, data, and callbacks
│   ├── world/                          # Scheduler and world integration
│   └── mixin/                          # ServerWorld mixins
└── test/java/org/bacon/ruthenium       # Unit tests
```

## More docs

- `docs/REGIONIZER.md` — deeper dive into the regionizer invariants and design.

## License

- Apache 2.0. See LICENSE.txt.

## Contributing

Issues and pull requests are welcome. For changes to region behavior or scheduler wiring, include targeted tests (happy path plus a couple of edge cases) to keep the invariants intact.

## Not implemented yet

The following pieces are still in progress or intentionally deferred (see `todo.md` for details):

- Full `TickRegionScheduler.tickWorld` orchestration on the main thread (honor `shouldKeepTicking`, coordinate world services, time budgets).
- Watchdog/time‑budget handling and crash diagnostics parity with Folia.
- Surfacing per‑region tick stats via debug/command interfaces.
- Regionized world data holders (player/entity/chunk tracking) and thread‑ownership checks akin to Folia.
- Additional helpers/utilities from Folia (teleport helpers, scheduling wrappers) beyond the basic current‑region/queue APIs.
- Broader integration tests and server smoke tests; more coverage for task‑queue migration across merges/splits and schedule‑handle reuse.
- Thorough audit of third‑party mod compatibility and guidance for thread‑safe integration.
