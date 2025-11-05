# Ruthenium Regionizer

Ruthenium ships a standalone re-implementation of Folia's regionizing algorithm that is
compatible with Fabric environments. The implementation is located under `org.bacon.ruthenium.region` and mirrors the concepts described in the upstream design
notes:

* **Region sections** group `2^N × 2^N` chunks into a coarse grid.
* **Regions** own a mutable set of region sections together with a region-local data object.
* **Region states** (`READY`, `TICKING`, `TRANSIENT`, `DEAD`) encode lifecycle transitions.
* **Regionizer maintenance** guarantees the invariants required for fully parallel ticking.

## Key components

| Class | Responsibility |
| --- | --- |
| `RegionSectionPos` | Value object describing the coordinates of a region section. |
| `RegionSection` | Stores chunk counts and liveness information for a section. |
| `ThreadedRegion` | Represents a region with bookkeeping for pending merges. |
| `ThreadedRegionizer` | Coordinates add/remove chunk operations, merging, splitting and maintenance. |
| `RegionDataController` | SPI for managing the region-local data object. |
| `RegionTickData` / `RegionTickDataController` | Default implementation that tracks tick counters and handles merge/split offsets. |

The `ThreadedRegionizer` exposes `addChunk`, `removeChunk`, `tryMarkTicking` and
`markNotTicking` so callers can fully manage the lifecycle of their region graph. Every
method includes extensive logging (via Log4j) to assist debugging during mod development.

## Guarantees

The implementation enforces the four invariants described by Folia:

1. Every section belongs to at most one non-dead region. Empty buffer sections are created eagerly and attached to the owning region to ensure uniqueness.
2. Regions own a merge-radius worth of sections, enforced by buffer creation and merge-later scheduling when a ticking region is nearby.
3. Ticking regions never expand while ticking. New sections created near a ticking region are owned by transient regions that merge back once the ticking region completes.
4. A region is always in one of the four canonical states. State transitions are centralized in `ThreadedRegionizer`.

Regions track pending merges in both directions so that merge-later operations are safe and fully deterministic. When a ticking region completes, `markNotTicking` processes pending merges, prunes dead sections, and performs connected-component splitting so that the number of concurrent regions remains high.

## Using the regionizer

The mod entrypoint (`org.bacon.ruthenium.Ruthenium`) exposes a singleton
`ThreadedRegionizer<RegionTickData>` instance. External systems (chunk loaders, schedulers, entity managers) can interact with the regionizer through this singleton to integrate with Ruthenium's parallel tick model.

Unit tests (`ThreadedRegionizerTest`) cover the most important invariants: region creation, immediate merging of adjacent regions, and deferred merge handling for ticking regions.
