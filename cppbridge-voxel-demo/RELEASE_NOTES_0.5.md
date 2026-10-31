# Stratawild 0.5 — Chunk-Seam Light, Flowing Water, Retained GPU Buffers

This is an **original experimental voxel sandbox**, not a production-quality
Minecraft replacement. No third-party game code, textures or assets are used.

## What changed

- **Cross-chunk light:** direct sky and lamp propagation are evaluated with a
  15-voxel neighboring halo, crossing X/Z chunk borders and corners. A light
  or wall edit invalidates neighboring detailed meshes. The flood fill is
  seeded at daylight/shadow boundaries, avoiding scans of the entire sunlit
  volume in its BFS queue.
- **Event-driven fluid:** generated lakes and aquifers are fixed sources (level
  8); water can flow down with a falling level (7) and spread sideways in
  diminishing levels (7..1). Flowing water drains when its source is removed.
  A bounded queue processes up to 1,200 cells per simulation tick. This is
  a simple game-fluid relaxation model, **not** a mass/pressure conserving
  Navier–Stokes solver; flow speed depends on tick rate and queue length.
- **Cross-chunk fluid streaming:** new chunks seed exposed liquid boundaries
  and wake existing adjacent chunks; the worker only generates source water.
  Dynamic flow is performed on the single game thread, with no races in the
  loaded chunk map.
- **Compact fluid geometry:** each merged quad remains **8 bytes**. The unused
  high nibble of packed word 0 records the level for water faces. Water levels
  are respected by the greedy merge key; variable-height water vertices are
  expanded in the existing OpenGL 3.3 vertex shader.
- **Retained OpenGL VBOs:** opaque and water buffer IDs are kept across edits.
  Capacity grows geometrically when necessary, and changed content uses
  `glBufferSubData`; far LOD buffers remain unchanged across local edits.
- **Same save protocol:** `SVXL0002` remains readable/writable. The game stores
  generated seeds, user edits and inventory, not transient water cells. Flow
  is recreated after loading. This keeps existing 0.4 saves readable.

## Tests and performance

- Native smoke, greedy mesh, randomized meshing, terrain/caves and previous
  0.4 water/inventory/save tests still pass on GCC 14.
- New 0.5 test verifies a lamp illuminating a tunnel across a chunk seam,
  light removal, horizontal fluid movement between chunks, receding fluid,
  vertical waterfall relaxation and packed water-level tags.
- 0.5 test passed with GCC 14 AddressSanitizer + UndefinedBehaviorSanitizer.
- GCC 14 `-O3` local benchmark, 169 chunks (`radius=6`, identical terrain):
  baseline 0.4 ~103 ms world generation and ~182 ms detailed meshing;
  0.5 ~149 ms world generation and ~492 ms detailed meshing. These are
  **individual Linux measurements**, not FPS or a representative comparison
  on Apple M1. Added water edge seeding and halo lighting cost CPU time;
  profile before increasing mesh rebuild budgets.
- Full Maven/Java 22+, LWJGL/OpenGL runtime, actual GPU throughput and Apple M1
  performance **have not been verified** in this environment.

## Known limitations

- The halo light solver is correct for locally loaded chunks up to 15 cells
  from the target, but temporary lighting at unloaded chunk borders is an
  approximation and resolves when neighbors stream in.
- The water model does not conserve volume and has no viscosity or pressure;
  falling water can behave differently from natural fluid. Updates of large
  water bodies may lag behind the player due to the per-tick work limit.
- Water quad ordering remains per chunk (transparency artifacts may occur).
  Different height levels meet with simplified side geometry. Far LOD omits
  individual water surfaces, and LOD interfaces are not mesh-stitched.
- OpenGL VBO reuse reduces buffer churn but does not itself prove FPS gains.
- This release deliberately **preserves the 0.4 terrain generator** so existing
  worlds do not change heightfields when upgraded. Wider terrain-generation
  experiments are deferred until world versioning is designed.

## Update from 0.4

Back up `stratawild.svxl` and run from the cppbridgej repository root:

```sh
git apply --check /path/to/stratawild-0.5-upgrade-from-0.4.patch
git apply /path/to/stratawild-0.5-upgrade-from-0.4.patch
bash cppbridge-voxel-demo/test-native.sh
bash cppbridge-voxel-demo/run-gpu.sh
```

For a clean checkout, either extract `stratawild-0.5.zip` and apply its
`cppbridge-voxel-demo/parent-module.patch`, or apply the full 0.5 patch.
Do **not** apply both installation strategies. No GitHub commits are part
of the ZIP or patches; they are local changes only.
