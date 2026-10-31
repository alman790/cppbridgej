#include "../../main/cpp/stratawild.cpp"
#include <cassert>
#include <chrono>
#include <cstdio>
#include <thread>
using namespace std::chrono_literals;

static void chunk(World& world,int cx,int cz) {
    auto item=std::make_unique<Chunk>();
    item->blocks.fill(STONE);
    world.integrate(cx,cz,std::move(item));
}

int main() {
    World w;
    w.center_x=w.center_z=0;
    chunk(w,0,0);
    chunk(w,1,0);
    w.update_mesh_async(1);
    auto& left=*w.chunks.at(pair_key(0,0));
    auto& right=*w.chunks.at(pair_key(1,0));
    assert(left.snapshot);
    assert(right.snapshot);
    auto unchanged=right.snapshot;
    auto old=left.snapshot;
    w.set(7,20,7,AIR);
    assert(!left.snapshot);
    assert(right.snapshot==unchanged);
    assert(old->blocks[World::index(7,20,7)]==STONE);
    assert(left.blocks[World::index(7,20,7)]==AIR);
    auto end=std::chrono::steady_clock::now()+8s;
    while((!left.snapshot || left.dirty) && std::chrono::steady_clock::now()<end) {
        w.update_mesh_async(2);
        std::this_thread::sleep_for(1ms);
    }
    assert(left.snapshot && !left.dirty);
    assert(left.snapshot!=old);
    assert(left.snapshot->blocks[World::index(7,20,7)]==AIR);
    assert(right.snapshot==unchanged);
    w.chunks.erase(pair_key(0,0));
    assert(old->blocks[World::index(7,20,7)]==STONE);
    std::puts("Stratawild 0.9: immutable snapshot reuse and invalidation: OK");
}
