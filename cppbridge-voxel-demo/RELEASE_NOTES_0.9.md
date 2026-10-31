# Stratawild 0.9 — shared snapshots, budgeted GPU uploads, GitHub integration

## Changes
- Mesh jobs retain shared immutable 16×16×80 block and fluid snapshots.
  A snapshot is created once per chunk data version and shared across jobs;
  neighbor-only invalidations keep their snapshot. Simulation-thread edits or
  fluid movement discard that chunk's cache. The background worker still copies
  snapshot arrays into its private mutable staging World for lighting/meshing.
- Java OpenGL client ranks pending updates by distance and forward direction.
  It schedules at most **two** changed GPU chunk uploads per frame, targeting
  roughly **1 MiB of opaque quad data**; single oversized meshes are allowed
  through to prevent starvation. Fluid bytes and GPU synchronization costs are
  not covered by the byte estimate.
- Pure-Java deterministic scheduler test and native snapshot invalidation test.
- Stratawild is committed into CppBridgeJ as a Maven module. No ZIPs or manual
  patches are required when checking out the feature branch.

## Compatibility
- C++20 and JDK 22+ required. No C ABI or save format changes (SVXL0002).
- No borrowed Minecraft code, assets, sounds, or proprietary game data.
- Existing native regression suites 0.3–0.8 and 0.9 targeted tests pass in
  their Linux test environment; full Java Maven/OpenGL runtime is not yet
  verified on macOS/Windows/Linux GPUs.
- Limitation: this is a best-effort **upload budget**, not a hard per-frame
  byte deadline. The initial coarse upload can be delayed by the scheduler,
  and a fluid-heavy chunk can exceed the estimate. No FPS or p99 improvement
  is claimed without a target-device profiler.
