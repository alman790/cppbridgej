#include "../../main/cpp/stratawild.cpp"
#include <cassert>
#include <chrono>
#include <cstdio>
#include <thread>
using namespace std::chrono_literals;

static void solid(World& w,int cx,int cz) {
    auto ch=std::make_unique<Chunk>();
    ch->blocks.fill(STONE);
    w.integrate(cx,cz,std::move(ch));
}
static bool wait_mesh(World& w,int cx,int cz,uint64_t generation,int max_ms=7000) {
    auto until=std::chrono::steady_clock::now()+std::chrono::milliseconds(max_ms);
    while(std::chrono::steady_clock::now()<until) {
        w.update_mesh_async(2);
        auto it=w.chunks.find(pair_key(cx,cz));
        if(it!=w.chunks.end() && it->second->generation==generation &&
                it->second->revision>0 && !it->second->dirty) return true;
        std::this_thread::sleep_for(2ms);
    }
    return false;
}
int main() {
    World w;
    w.center_x=w.center_z=0;
    solid(w,0,0);
    solid(w,1,0);
    // Tunnel near but NOT touching the X border. This light change must
    // still dirty a neighboring mesh: a 1-voxel halo is insufficient.
    for(int x=3;x<=18;++x) w.set(x,20,8,AIR);
    w.set(3,20,8,LAMP);
    auto right=pair_key(1,0);
    auto left=pair_key(0,0);
    w.build_mesh(1,0,*w.chunks.at(right));
    assert(w.chunks.at(right)->light[World::index(0,20,8)]>=1);
    w.set(3,20,8,STONE);
    assert(w.chunks.at(right)->dirty); // 13 cells away from the chunk border.
    w.build_mesh(1,0,*w.chunks.at(right));
    assert(w.chunks.at(right)->light[World::index(0,20,8)]==0);

    // Snapshot followed by an edit: stale outputs must not overwrite current
    // mesh geometry, regardless of how the operating system schedules worker.
    w.set(15,20,8,LAMP);
    w.update_mesh_async(2);
    w.set(15,20,8,STONE);
    auto generation=w.chunks.at(right)->generation;
    assert(wait_mesh(w,1,0,generation));
    assert(w.chunks.at(right)->light[World::index(0,20,8)]==0);
    assert(w.chunks.at(right)->revision>0);
    assert(w.chunks.at(left)->generation!=0);

    // Reusing the same coordinate must not accept an old in-flight mesh, even
    // if the newly integrated chunk starts with the same epoch counter.
    w.set(15,20,8,AIR);
    w.set(16,20,8,STONE); // Explicitly override the persisted tunnel edit.
    w.update_mesh_async(2);
    const auto old_generation=w.chunks.at(right)->generation;
    w.chunks.erase(right);
    solid(w,1,0);
    const auto new_generation=w.chunks.at(right)->generation;
    assert(new_generation!=old_generation);
    assert(wait_mesh(w,1,0,new_generation));
    assert(w.chunks.at(right)->blocks[World::index(0,20,8)]==STONE);

    // New bulk ABI contract: a just-integrated chunk is immediately visible
    // in the list with a coarse mesh, before the detailed worker is finished.
    engine.world.reset(0xCAFEu);
    engine.world.integrate(0,0,engine.world.generate(0,0));
    int32_t entries[4]{};
    assert(voxel_mesh_list(entries,4)==1);
    assert(entries[2]==0 && entries[3]==0);
    int32_t coarse[512]{};
    const int coarse_words=voxel_mesh_copy_far(0,0,coarse,512);
    assert(coarse_words>0 && coarse_words<=512 && coarse_words%2==0);
    assert(voxel_mesh_copy(0,0,coarse,512)==-1); // Detailed mesh not built yet.
    engine.world.center_x=engine.world.center_z=0;
    engine.world.rebuild_meshes(1);
    assert(voxel_mesh_list(entries,4)==1);
    assert(entries[2]>0 && entries[3]>0);

    // Async workers can be stopped, reset and started again without data races.
    w.reset(0xDEADBEEFu);
    w.center_x=w.center_z=0;
    solid(w,0,0);
    generation=w.chunks.at(left)->generation;
    assert(wait_mesh(w,0,0,generation));
    assert(w.chunks.at(left)->revision>0);
    std::puts("Stratawild 0.6: async snapshots, stale rejection, reload, halo invalidation: OK");
}
