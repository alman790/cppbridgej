# Stratawild 0.7 — view-aware streaming, sunlight frontier, and LOD skirts

Incremental update for the original voxel sandbox using C++20, Java and the
CppBridgeJ FFM bridge. No third-party game assets or Minecraft code are used.

## Changes

1. **View-aware mesh scheduling**: among similarly distant dirty chunks, favor
   chunks in front of the player's yaw. The 3x3 collision-safe initial load and
   nearest detailed chunks keep priority; the mesh job count remains bounded.
2. **Sunlight-shadow frontier optimization**: a per-column 46x46 `sun_floor`
   skyline replaces scanning six neighbors for every sunlit voxel across the
   46x46x80 lighting halo. Emit frontier seeds only where adjacent columns
   have differing shadow heights. The same six-way flood fill continues for
   emitted light and indirect sun, including attenuation through water.
3. **Coarse LOD edge skirts**: the four perimeter edges of the 4x4-tile far
   heightfield get vertical faces from each tile's coarse height down to y=6.
   This helps hide holes between approximate and detailed terrain meshes.
   Interior neighboring coarse tiles still use clipped walls.
4. **Frame pacing telemetry**: a small Java `FrameTimeStats` circular buffer
   records complete frame durations; the title displays FPS and rolling p95/
   p99 milliseconds. These include vsync and CPU work, not GPU-only timings.
5. **Tests**: `world07.cpp` validates view ordering, the identical result
   from the previous halo flood fill, and skirts on all four sides. Pure Java
   `FrameTimeStatsTest` verifies percentile math and ring overwrite.

## Measured CPU-only benchmark (this build environment)

Same C++20 GCC `-O2` binary configuration, 169 loaded chunks, generated
from the same seed, synchronous meshing; 3 paired local runs:

| Version | Full 169-chunk mesh generation (ms) |
|---|---|
| 0.6 | 587.9, 653.6, 603.6 |
| 0.7 | 257.9, 257.8, 242.2 |

These are local CPU timings and are not graphics frame-rate or render-latency
measurements. The geometry count for detailed meshes is unchanged; the far
heightfield gains perimeter skirt quads. Whole-frame p95/p99 is instrumented,
but actual numbers require testing on desktop OpenGL hardware.

## Compatibility

- **Save format:** unchanged (`SVXL0002`), existing worlds can be opened; keep
  a backup of `stratawild.svxl` before upgrading.
- **C ABI and GPU vertex packing:** unchanged, 2x32-bit words per face.
- **Coarse mesh size:** now includes skirts; consumers must not assume a
  maximum of 160 words. The Java FFM staging buffer was raised to 512 words.
- **Runtime:** JDK 22+, C++20; GPU client needs LWJGL and OpenGL 3.3 Core.
- **Known limitations:** far LOD still omits tree/cave/water/edit details;
  skirts mitigate but do not guarantee crack-free near/far transitions.
  The native benchmark is CPU-side only and frame telemetry needs real GPU
  hardware for performance conclusions. Workers still copy 3x3 chunk data
  into immutable snapshots and may contend for CPU bandwidth.

## Upgrade from 0.6

From the CppBridgeJ repository root **already containing Stratawild 0.6**:

```bash
git apply --check /path/to/stratawild-0.7-upgrade-from-0.6.patch
git apply /path/to/stratawild-0.7-upgrade-from-0.6.patch
bash cppbridge-voxel-demo/test-native.sh
bash cppbridge-voxel-demo/run-gpu.sh
```

For fresh clones, use the 0.7 ZIP and apply the included `parent-module.patch`
from the repository root, **or** apply the full standalone 0.7 patch. Do not
combine both methods.
