# Stratawild 0.3 — Terrain, Cave Lighting & Far LOD

**This is an original voxel-sandbox prototype, not Minecraft.** No original assets,
textures, character designs, music or copyrighted game data from other games are included.

## Implemented

- **Four deterministic biomes:** grove, prairie, barren and highland, derived from
  continuous humidity/elevation noise. Existing surface height formula remains unchanged.
- **3D caves:** world-space trilinear noise carves chambers, passages, some surface
  openings and occasional underground crystals. Solid 6-block foundation prevents
  falls into an infinite void. Chunk generation is independent of scheduling.
- **Vertex ambient occlusion:** four two-bit occlusion values packed into the
  *already existing* two-word quad record. The GPU interpolates corner shadows;
  packed mesh size stays **8 bytes per quad**. Diagonal border-chunk invalidation
  handles cross-chunk AO; individual edits invalidate only affected neighbors.
- **Far-distance LOD:** each generated chunk also carries up to 80 large terrain
  quads using a 4×4 heightfield approximation, packed into the same format. At
  ~85m the GPU client swaps detailed geometry for far terrain. The renderer
  preserves far GPU buffers across near-mesh revisions; mesh uploads occur per
  changed chunk, not per block. A distance and view-volume test avoids faraway
  draws. Streaming area increased from 11×11 to **13×13 chunks**.
- **Extra tests:** reproducibility, biome coverage, cave openings, negative world
  coordinates, AO corner packing, border edit invalidation, packed LOD limits and
  the `voxel_mesh_copy_far` ABI. `benchmark-native.sh` reports raw geometry counts
  and native generation/meshing timings.

## Reproducible native measurement

On the test Linux container using `c++ -O3` and the default seed:

| Metric | Measured result |
| --- | ---: |
| Loaded chunks in benchmark | 169 |
| Detailed quads for full area | 52,147 |
| Quads if every chunk uses coarse LOD | 4,659 |
| Quads selected by ~85m threshold, camera at origin | 26,899 |
| Synchronous generation of all 169 chunks | ~96 ms |
| Meshing all 169 chunks | ~145 ms |

**These are native microbenchmark measurements**, not the FPS of the Java/OpenGL
client. On-device frame times, GPU memory, gameplay jitter, and Mac performance
have not been measured. Normal gameplay streams background chunks and rebuilds
up to three detailed meshes per tick instead of running the benchmark synchronously.

## Limitations

Far LOD does not show caves, individual trees or user edits; appearance can pop at
transition boundaries. The current OpenGL client requires a real 3.3 Core GPU
and needs testing on macOS. LODs are simplified terrain shells, not progressive
voxel LOD with stitched seams. Caves are currently unlit except for vertex AO,
with no torch or dynamic light sources. `stratawild.svxl` save-file *format* from
0.2 remains readable and preserves block edits and seed, but existing landscapes
will adopt the new cave/biome generation. Back up worlds before upgrading.

## Upgrading from 0.2

If you extracted Stratawild 0.2 into a CppBridgeJ clone, apply the separate
`stratawild-0.3-upgrade-from-0.2.patch` from the **CppBridgeJ repo root**:

```sh
git apply --check /path/to/stratawild-0.3-upgrade-from-0.2.patch
git apply /path/to/stratawild-0.3-upgrade-from-0.2.patch
bash cppbridge-voxel-demo/test-native.sh
bash cppbridge-voxel-demo/run-gpu.sh
```

For a clean checkout, extract the 0.3 ZIP and apply
`cppbridge-voxel-demo/parent-module.patch` once, or use the full new-file patch.
Do not reapply the parent-module patch if the parent POM already lists the module.
