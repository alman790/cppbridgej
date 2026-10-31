#include "../../main/cpp/stratawild.cpp"
#include <cassert>
#include <cstdio>

static void solid(World& w,int cx,int cz) {
    auto chunk=std::make_unique<Chunk>();
    chunk->blocks.fill(STONE);
    w.integrate(cx,cz,std::move(chunk));
}

int main() {
    // A lamp must illuminate the dark tunnel in the next chunk.
    World light;
    solid(light,0,0);
    solid(light,1,0);
    for(int x=13;x<=19;++x) light.set(x,20,8,AIR);
    light.set(15,20,8,LAMP);
    auto& right=*light.chunks.at(pair_key(1,0));
    light.build_mesh(1,0,right);
    assert(right.light[World::index(0,20,8)]>=13);
    assert(right.light[World::index(2,20,8)]>=11);
    // Rebuilding the left chunk must agree on the border's light intensity.
    auto& left=*light.chunks.at(pair_key(0,0));
    light.build_mesh(0,0,left);
    assert(left.light[World::index(14,20,8)]>=13);
    // Removing the lamp drains the incoming illumination across the seam.
    light.set(15,20,8,STONE);
    light.build_mesh(1,0,right);
    assert(right.light[World::index(0,20,8)]==0);

    World water;
    solid(water,0,0);
    solid(water,1,0);
    // Flat, dark gallery spanning chunk boundary, with a solid floor.
    for(int x=9;x<=25;++x) water.set(x,20,8,AIR);
    water.set(15,20,8,WATER); // Persistent source, not an ephemeral flow cell.
    water.tick_water(20000);
    assert(water.at(16,20,8)==WATER);
    assert(water.liquid_at(16,20,8)==7);
    assert(water.liquid_at(17,20,8)==6);
    auto& downstream=*water.chunks.at(pair_key(1,0));
    water.build_mesh(1,0,downstream);
    bool flowing_quad=false;
    for(size_t i=0;i<downstream.water_mesh.size();i+=2) {
        uint32_t word=uint32_t(downstream.water_mesh[i]);
        flowing_quad|=((word>>20)&15u)==WATER && ((word>>24)&15u)<8;
    }
    assert(flowing_quad);
    // No infinite water source creation: removing the source drains the tunnel.
    water.set(15,20,8,AIR);
    water.tick_water(60000);
    for(int x=9;x<=22;++x) assert(water.at(x,20,8)==AIR);

    // Vertical drop is preserved across multiple ticks while bounded by
    // a per-tick budget; the water settles on rock, then spreads sideways.
    World fall;
    solid(fall,0,0);
    for(int y=20;y<=28;++y) fall.set(7,y,7,AIR);
    fall.set(7,28,7,WATER);
    for(int n=0;n<10;++n) fall.tick_water(2);
    for(int n=0;n<60;++n) fall.tick_water(32);
    assert(fall.liquid_at(7,27,7)==7);
    assert(fall.liquid_at(7,20,7)==7);
    std::puts("Stratawild 0.5: chunk seam light, flowing water, reversibility, packed level: OK");
}
