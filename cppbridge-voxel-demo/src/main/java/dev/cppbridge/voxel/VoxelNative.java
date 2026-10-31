package dev.cppbridge.voxel;

import dev.cppbridge.annotations.CppFunction;
import dev.cppbridge.annotations.CppModule;
import dev.cppbridge.memory.NativeIntArray;
import dev.cppbridge.memory.NativeFloatArray;

/** Batch-oriented Java/C++ API: no per-block Java/FFM crossings. */
@CppModule(libraryName = "stratawild")
public interface VoxelNative {
    @CppFunction("voxel_init")
    void init(long worldSeed);

    @CppFunction("voxel_tick")
    void tick(int keys, float yaw, float pitch, float dtSeconds);

    @CppFunction("voxel_render")
    void render(NativeIntArray pixels, int width, int height);

    @CppFunction("voxel_interact")
    int interact(int action, int selectedBlock);

    @CppFunction("voxel_save")
    int save();

    @CppFunction("voxel_loaded_chunks")
    int loadedChunks();

    /** Ten counters in one FFM call; see RELEASE_NOTES_0.8.md. */
    @CppFunction("voxel_stream_stats")
    void streamStats(NativeIntArray counters);

    @CppFunction("voxel_mesh_list")
    int meshList(NativeIntArray entries);

    @CppFunction("voxel_mesh_copy")
    int meshCopy(int chunkX,int chunkZ,NativeIntArray packedQuads);

    @CppFunction("voxel_mesh_copy_far")
    int meshCopyFar(int chunkX,int chunkZ,NativeIntArray packedQuads);

    @CppFunction("voxel_mesh_water_words")
    int waterWords(int chunkX,int chunkZ);

    @CppFunction("voxel_mesh_copy_water")
    int meshCopyWater(int chunkX,int chunkZ,NativeIntArray packedQuads);

    @CppFunction("voxel_inventory")
    void inventory(NativeIntArray counts);

    @CppFunction("voxel_camera")
    void camera(NativeFloatArray eyeYawPitch);
}
