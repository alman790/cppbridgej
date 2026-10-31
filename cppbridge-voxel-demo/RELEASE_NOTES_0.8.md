# Stratawild 0.8 — cancellable streaming and F3 diagnostics

C++20/Java/CppBridgeJ continuation of the original voxel sandbox. No Minecraft
code, assets, character designs, sounds, or copied game content are included.

## What changed

1. **No persistent holes from stale terrain requests.** After canceled/in-flight
   generation finishes, `stream()` re-evaluates the desired 13×13 region even
   if the player stays in the same chunk. It re-enqueues a missing request
   rather than waiting for the next movement. Finished results are evaluated
   against the *new* view center after a border crossing.
2. **Cancellable terrain generation.** A monotonic atomic region ticket lets
   the generation thread skip work that has not yet begun after the player
   changes regions. Any canceled job still sends a lightweight completion
   record, so the simulation releases its scheduling key and can retry if
   the target remains within range.
3. **Cancellable asynchronous mesh jobs.** Each loaded chunk has its own
   atomic revision ticket with shared lifetime. Every immutable 3×3 snapshot
   captures its original token and revision. On subsequent edits/fluid changes,
   stale *queued* mesh jobs are discarded and their staging memory released;
   a worker also checks before and after expensive meshing. On unload the
   chunk's ticket is invalidated, preventing use of a matching coordinate
   from an older chunk lifetime. Existing generation/epoch guards remain.
4. **F3 live diagnostics.** The OpenGL window title can display terrain queue,
   mesh queue, queued/in-flight mesh count, total accepted mesh results, rejected
   results, and canceled terrain/mesh jobs. All 10 counters are transferred
   through a single new `voxel_stream_stats` native API call once per second
   while F3 diagnostics is enabled (not every frame). F3 toggles this view.
5. **Automated regression tests.** `world08.cpp` exercises standing-still
   retries, re-centering, pre-generation cancel, snapshot queue pruning,
   unload/reload token uniqueness, mesh flight limits, and the diagnostics ABI.

## Runtime contracts and compatibility

- World persistence remains `SVXL0002`; back up `stratawild.svxl` before upgrade.
- Quad representation remains two 32-bit words, as in 0.7; no GPU shader changes.
- New optional C ABI: `void voxel_stream_stats(int32_t* out, int32_t length)`;
  only writes when `length >= 10`, in this order:
  `[loaded, terrainPending, terrainReady, meshPending, meshReady,
    meshScheduled, meshCommitted, meshDiscarded, meshCanceled, terrainCanceled]`.
- Mesh in-flight scheduling limit remains **4**. This is an upper bound on
  scheduled per-chunk work, not a total process memory ceiling.
- C++20 compiler, JDK 22+ and Maven required for the full Java desktop build.
  GPU path still needs OpenGL 3.3 Core and LWJGL.
- Light propagation, water simulation, and save semantics are unchanged.

## Performance expectations and limitations

0.8 primarily avoids wasted or stalled work during rapid player movement or
heavy editing. This release does **not** claim a numerical FPS increase. The
native regression tests can run without a GPU; actual p95/p99 frame impact and
OpenGL/FFM integration should be measured on target Windows/Linux/Mac devices.
A mesh job already running when invalidated can still finish the current
meshing pass before being discarded; queued jobs, however, are removed early.

## Upgrade from 0.7

After backing up your world, from the CppBridgeJ repository root containing
Stratawild 0.7:

```bash
git apply --check /path/to/stratawild-0.8-upgrade-from-0.7.patch
git apply /path/to/stratawild-0.8-upgrade-from-0.7.patch
bash cppbridge-voxel-demo/test-native.sh
bash cppbridge-voxel-demo/run-gpu.sh
```

For a clean checkout, use either the ZIP and `parent-module.patch` or the full
0.8 patch, never both. The main CppBridgeJ library stays unchanged.
