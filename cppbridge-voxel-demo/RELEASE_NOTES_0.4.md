# Stratawild 0.4 — Aquifers, Cave Light and Inventory

This is an original prototype, **not** a completed commercial-quality game. No
Minecraft source, artwork, sound, creatures, or protected branding is included.

## New features

- **Stationary water:** deterministic lakes in low valleys and rare underground
  pockets. Water is walkable/swimmable, not mined, and has a separate GPU buffer
  with approximate transparent rendering (alpha blend, depth write disabled,
  per-chunk back-to-front order). Water does **not** flow or spread.
- **Eight placeable materials:** slot 8 places an original glowing block.
  Building consumes finite native inventory, mining replenishes supplies; the
  native array is fetched in a batch for the hotbars. Each save stores supplies.
- **Chunk-local cave lighting:** direct skylight propagates vertically and through
  open cells, a source light propagates up to 14 steps; water attenuates it more.
  Four light values occupy the unused high byte in the same 8-byte merged quad
  as ambient occlusion. Remeshing automatically refreshes light after edits.
- **LOD dither transition:** replaces hard ~85m switch with a stable 4x4 ordered
  dither over 80–103m. No external shaders/textures.
- **SVXL0002 saves:** edits, seed and 10 inventory counts; older `SVXL0001`
  saves can be read. Truncated files fail without mutating the current world.

## What is verified

Native C++ tests pass with GCC and Clang; the 0.4 test covers liquid mesh
separation, skylight and glowing blocks, editing/stock management, bulk FFM
ABI and old/new save files. A dedicated test passed with ASan/UBSan on the
Linux testing machine. Swing/OpenGL Java source passed a syntax/type check
against test stubs using JDK 21 (not the real JDK 22 FFM runtime). **Full
Maven build and on-device GPU execution have not been verified.**

Native microbenchmark with `-O3`, 169 chunks on the supplied Linux environment:

- Before upgrade (0.3): approximately 92ms terrain generation, 139ms all
  detailed meshes, 52,147 opaque quads.
- After upgrade (0.4): approximately 99ms terrain generation, 168ms all
  detailed+lighting meshes, 49,883 opaque quads (water quads counted separately).

These are individual sample timings affected by host workload, not FPS; lighting
is real work and can increase meshing CPU time. Rebenchmark target hardware.

## Known limitations

- Lighting propagation stops at chunk borders; neighboring chunks can show
  subtle illumination seams. A full global light solver is future work.
- Fluid alpha blending is approximate: chunks are sorted, individual faces
  within chunks are not, and far impostors omit water entirely.
- Dithered LOD reduces abrupt popping but is not seamless geometry stitching;
  holes and canopy/shoreline differences may remain in the transition band.
- Water is static (no fluid flow); underwater view uses a simple fluid material.
- Full game logic, survival mechanics, UI layout and FPS depend on later work.

## Upgrade

Back up `stratawild.svxl` first. From the repository root with 0.3 applied:

```sh
git apply --check /path/to/stratawild-0.4-upgrade-from-0.3.patch
git apply /path/to/stratawild-0.4-upgrade-from-0.3.patch
bash cppbridge-voxel-demo/test-native.sh
bash cppbridge-voxel-demo/run-gpu.sh
```

The new save version is backwards **read** compatible, not backwards **write**
compatible. Existing seed-based terrain now has water at low elevations.
