#include "../../main/cpp/stratawild.cpp"
#include <cassert>
#include <cstdio>
#include <set>
int main() {
    World w;
    w.seed=0xABCD0123u;
    // Reproducible 3D cave field, including positions with negative coordinates.
    for(int z=-83;z<81;z+=7) for(int x=-75;x<91;x+=9) {
        int h=w.terrain_height(x,z);
        assert(h>=7 && h<HEIGHT-8);
        for(int y=0;y<=h;++y) {
            bool cave=w.cave_at(x,y,z,h);
            assert(cave==w.cave_at(x,y,z,h));
            if(y<6) assert(!cave);
        }
    }
    auto a=w.generate(0,0),b=w.generate(0,0);
    assert(a->blocks==b->blocks);
    auto neg=w.generate(-2,-3);
    auto neg2=w.generate(-2,-3);
    assert(neg->blocks==neg2->blocks);
    assert(!a->far_mesh.empty());
    assert(a->far_mesh.size()<=160);
    for(size_t i=0;i<a->far_mesh.size();i+=2) {
        uint32_t packed=uint32_t(a->far_mesh[i]);
        uint32_t dims=uint32_t(a->far_mesh[i+1]);
        assert(((packed>>17)&7)<6);
        assert((packed&31)<=16 && ((packed>>12)&31)<=16);
        assert((dims&255)>=1 && ((dims>>8)&255)>=1);
        assert(((dims>>16)&0xffu)==0); // Far impostors skip AO.
        assert((dims>>24)==255u); // Far terrain stays sunlit.
    }
    std::set<int> biomes;
    for(int z=-600;z<=600;z+=32) for(int x=-600;x<=600;x+=32)
        biomes.insert(int(w.biome_at(x,z)));
    assert(biomes.size()>=3);
    // A cave is a submerged air cell, not an above-ground hole.
    int caveCells=0,surfaceMouths=0;
    for(int z=-48;z<48;++z) for(int x=-48;x<48;++x) {
        int h=w.terrain_height(x,z);
        for(int y=6;y<h-4;++y) caveCells+=int(w.cave_at(x,y,z,h));
        surfaceMouths+=int(w.cave_at(x,h,z,h));
    }
    assert(caveCells>600);
    assert(surfaceMouths>0);
    // Greedy mesh is still 8 bytes per rect; corner AO occupies 2 bits per corner.
    auto test=std::make_unique<Chunk>();
    test->blocks[World::index(4,10,4)]=STONE;
    test->blocks[World::index(3,11,4)]=STONE;
    test->blocks[World::index(4,11,3)]=STONE;
    w.integrate(0,0,std::move(test));
    Chunk &ch=*w.chunks.at(pair_key(0,0));
    w.build_mesh(0,0,ch);
    bool found=false;
    for(size_t i=0;i<ch.mesh.size();i+=2) {
        uint32_t a=uint32_t(ch.mesh[i]),b=uint32_t(ch.mesh[i+1]);
        int dir=(a>>17)&7;
        int x=a&31,y=(a>>5)&127,z=(a>>12)&31;
        if(dir==3 && x==4 && y==11 && z==4) {
            assert(((b>>16)&3)==3); // Both adjacent exterior cells are solid.
            assert((b&255)==1 && ((b>>8)&255)==1);
            found=true;
        }
        // High byte now stores four 2-bit interpolated light samples.
    }
    assert(found);
    // AO at a chunk corner requires diagonal neighbor remeshing.
    w.integrate(1,1,std::make_unique<Chunk>());
    w.build_mesh(1,1,*w.chunks.at(pair_key(1,1)));
    assert(!w.chunks.at(pair_key(1,1))->dirty);
    w.set(15,11,15,STONE);
    assert(w.chunks.at(pair_key(1,1))->dirty);
    // Interior edits leave distant neighbors clean (no wasted remeshes).
    w.build_mesh(1,1,*w.chunks.at(pair_key(1,1)));
    w.set(7,10,7,STONE);
    assert(!w.chunks.at(pair_key(1,1))->dirty);
    std::printf("Stratawild 0.3 biomes=%zu underground_cave_voxels=%d surface_openings=%d; AO: OK\n",
                biomes.size(),caveCells,surfaceMouths);
}
