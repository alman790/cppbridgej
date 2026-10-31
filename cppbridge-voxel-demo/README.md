# Stratawild 0.9 — an original C++/Java voxel sandbox

A from-scratch procedural voxel game built to exercise [CppBridgeJ](https://github.com/alman790/cppbridgej). It does not include Minecraft code, textures, sounds, characters, names or copied game data. The world generator, physics, greedy mesher, shaders and game logic are original project code.

For new features and compatibility, see [0.9 release notes](RELEASE_NOTES_0.9.md) and [0.8 release notes](RELEASE_NOTES_0.8.md). Earlier releases: [0.7](RELEASE_NOTES_0.7.md), [0.6](RELEASE_NOTES_0.6.md), [0.5](RELEASE_NOTES_0.5.md), [0.4](RELEASE_NOTES_0.4.md), [0.3](RELEASE_NOTES_0.3.md).

## Architecture

- **C++20**: deterministic 4-biome terrain, 3D caves, lakes, bounded flowing water, light-emitting blocks and vegetation, 16×16×80 chunks, ray-picking, swimming/collision, finite building inventory, editing and versioned save/load.
- **Background C++ generation thread**: generation tickets cancel obsolete requests and standing-still retries avoid permanently missing chunks; initial 3×3 collision-safe chunks are produced immediately; the rest of the 13×13 streaming area is generated off-thread and integrated on the game thread.
- **Independent mesh worker (0.6–0.9)**: reuses immutable voxel snapshots for unchanged loaded neighbors, computes full AO and 15-block halo lighting off-thread, and sends packed meshes back through a bounded completion queue. Epoch and generation checks reject results invalidated by edits, flow, unload/reload or reset. Only the first bootstrap mesh is built synchronously. Equal-range jobs prioritize the forward view while the immediate neighborhood remains highest priority. In 0.8, canceled queued snapshots are discarded early, and unloaded chunks invalidate their work tokens.
- **Greedy surface meshing**: only visible faces are emitted; neighboring equal-material faces are merged. Each merged quad is **two packed 32-bit words** (8 bytes) containing local origin, orientation, 4-bit material ID, dimensions, vertex AO and 2-bit-per-vertex propagated light.
- **CppBridgeJ FFM**: transfers entire chunk meshes in one call instead of per-voxel calls.
- **GPU renderer (`StratawildGL`)**: budgeted near/forward-first transfers (up to two mesh revisions per frame, ~1 MiB estimated opaque bytes), OpenGL 3.3 Core with shader-side quad expansion via instancing, baked vertex AO and cave light, screen-door LOD transition (80–103m) with 4-edge far-field skirts, separate alpha-blended variable-height water, reusable revision-aware GPU buffers, frustum and distance culling, depth testing, frame p95/p99 display, optional F3 streaming diagnostics, and procedural per-block colors. LWJGL + GLFW are low-level BSD-3-Clause bindings (not an engine or an asset pack).
- **Software fallback (`Stratawild`)**: original threaded C++ ray traversal into a NativeIntArray, Java Swing window/UI. No graphics runtime beyond Java is used in this fallback.

## Build from GitHub (no archives or patch files)

The game now lives in the `feature/stratawild-0.9` GitHub branch.
Requirements: **JDK 22+**, C++20 compiler, Maven Wrapper, desktop graphics;
the GPU client needs OpenGL 3.3 and LWJGL 3.3.6 from Maven Central.

```bash
git clone --branch feature/stratawild-0.9 https://github.com/alman790/cppbridgej.git
cd cppbridgej
bash cppbridge-voxel-demo/test-native.sh
bash cppbridge-voxel-demo/run-gpu.sh
# Optional: software fallback
bash cppbridge-voxel-demo/run.sh
```

On macOS, `run-gpu.sh` uses `-XstartOnFirstThread` for GLFW. Back up
`stratawild.svxl` before switching versions. Saves still use `SVXL0002`.
GPU and CPU clients use the same world file; do not run them simultaneously.
The project is not merged into `main` until the pull request is reviewed.

## Controls

| Action | Keyboard / mouse |
|---|---|
| Move | W A S D |
| Jump | Space |
| Sprint | Shift |
| Camera | Mouse |
| Break / place | Left click / right click |
| Material | 1–8 (8 = glowing block) |
| F3 streaming statistics | F3 |
| Save | F5 (also saved on clean exit) |
| Release pointer | Esc (press again in GPU client to exit) |
| Software renderer quality | F2 |

## Native C ABI for mesh streaming

```
int32_t voxel_mesh_list(int32_t* out, int32_t capacity);
// Returns count; each chunk: [chunkX, chunkZ, meshRevision, quadCount]
// meshRevision=0 means far LOD is available, detailed mesh is still pending.

int32_t voxel_mesh_copy(int32_t chunkX, int32_t chunkZ,
                        int32_t* out, int32_t capacity);
int32_t voxel_mesh_copy_far(int32_t chunkX, int32_t chunkZ,
                            int32_t* out, int32_t capacity);
// Each copy returns an int32 word count, or the negative required size if too small.
// mesh_copy_far works before the first detailed mesh (revision=0).
// mesh_copy / mesh_copy_water require a positive revision.

int32_t voxel_mesh_water_words(int32_t chunkX, int32_t chunkZ);
int32_t voxel_mesh_copy_water(int32_t chunkX, int32_t chunkZ,
                              int32_t* out, int32_t capacity);
// Water meshes are transparent-only, kept out of the opaque draw pass.

void voxel_inventory(int32_t* out, int32_t capacity);
// 10 items: [air, grass, earth, stone, sand, wood, leaf, crystal, lamp, water]

void voxel_camera(float* out, int32_t capacity);
// [eyeX, eyeY, eyeZ, yaw, pitch]
```

Packing format (little bit-field positions within 32-bit words, independent of CPU endianness):

- word 0: x (bits 0–4), y (5–11), z (12–16), direction (17–19), material (20–23), **fluid level** (24–27, 0–8; zero for solids).
- word 1: spanU (bits 0–7), spanV (8–15), AO corner 0–3 (bits 16–23, two bits each), light corners 0–3 (bits 24–31, two bits each).
- direction: 0=-X, 1=+X, 2=-Y, 3=+Y, 4=-Z, 5=+Z.
- local origin may lie at the positive chunk boundary (x=16 or z=16).

Far LOD is a 4×4-tile heightfield per chunk (~16 top quads plus vertical walls). It does not show individual trees, caves, fluids or edits; the GPU blends geometry via a fixed ordered dither between 80 and 103 m. The 0.7 coarse mesh uses outer perimeter skirts down to y=6 to hide many terrain-height gaps, but this is not exact geometry stitching: visible overhangs, caves, edit discrepancies and dither silhouette changes remain possible.
In 0.6, unmeshed chunks show this heightfield immediately instead of remaining blank.

The OpenGL shader expands each packed record into 6 vertices using `gl_VertexID`; only one 8-byte record is uploaded per merged quad. Flowing water lowers the top vertex surface based on the packed fluid level. The renderer retains GPU buffers and grows their capacity as needed instead of deleting/creating them for every revision. Only the initial bootstrap mesh is generated on the game thread. Further meshes are computed by an independent bounded worker from immutable 3×3 neighborhood snapshots, with generation/epoch checks on completion. OpenGL uploads are still made on the Java graphics thread. Worker threads never access the live chunk map.

## Test coverage and limitations

`test-native.sh` runs native smoke tests, save migration, inventory consumption/mining, deterministic water generation, propagated skylight/lamp lighting, separate liquid mesh ABI, 2×2×2 greedy-merging, shared chunk-seam invalidation, background streaming completion, cross-chunk lamp illumination and its removal, reversible water flow and gravity, packed fluid-height tags, async mesh snapshots, stale-result rejection, unload/reload generation IDs, extended light-dirty radius, and exhaustive per-face validation across random voxel configurations. The native mesh test has also passed with ASan/UBSan in a Linux environment. Java 22+ Maven integration and GPU runtime have **not** been verified on the user's Mac; GPU performance numbers should not be assumed.

This is still an early playable prototype, not a production-ready clone. To reach a production-scale world: add a physically conservative fluid volume solver and water-geometry stitching, seam-stitched multi-level LOD, persistent mesh cache, edit-sensitive coarse mesh rebuilds, stronger queue throttling, occlusion culling, GPU telemetry, a full inventory UI and real device benchmarks. Cross-chunk lighting is limited to a 15-cell halo (not a persistent global lightfield). No third-party game content or proprietary engine is needed.

CppBridgeJ remains MIT licensed. LWJGL is independently licensed under BSD-3-Clause; retain its license notices when distributing binary packages. The source remains original, but that alone is not a legal guarantee against every possible intellectual-property claim.

## 0.7 performance diagnostics

The OpenGL window title reports the last one-second FPS count plus nearest-rank
p95 and p99 **whole-frame duration in milliseconds**, measured over a rolling
240-frame history. Frame duration includes event polling, simulation/FFM,
GPU submissions and buffer swap (which includes vsync wait). It is therefore
**not** a GPU-only time measurement. A bigger sample history is configurable
in `StratawildGL` by editing the `FrameTimeStats(240)` capacity. The
standalone `FrameTimeStatsTest` exercises exact percentiles and circular-buffer
overwrite without requiring GLFW/LWJGL.

An optimized sun/shadow frontier reduces scans over directly sunlit empty voxels
without changing the previous lighting halo propagation result in the tested
cases. Compare `bash cppbridge-voxel-demo/benchmark-native.sh` on identical
hardware, builds and seeds; this benchmark is synchronous and does **not**
measure in-game GPU FPS, p95 or p99.

The coarse 4x4 terrain LOD perimeter geometry adds at most 16 extra skirts per
chunk, with 8-byte packed quads as before. The Java initial far-mesh FFM
buffer was expanded to 512 words to accommodate these extra faces.
