#include "../../main/cpp/stratawild.cpp"
#include <cassert>
#include <chrono>
#include <cstdio>
#include <thread>
using namespace std::chrono_literals;

int main() {
    {
        World w;
        auto chunk=std::make_unique<Chunk>();
        // One 2x2x2 solid cube must collapse to exactly six 2x2 quads.
        for(int y=10;y<12;++y) for(int z=5;z<7;++z) for(int x=3;x<5;++x)
            chunk->blocks[World::index(x,y,z)]=GRASS;
        w.chunks.emplace(pair_key(0,0),std::move(chunk));
        auto& ch=*w.chunks.at(pair_key(0,0));
        w.build_mesh(0,0,ch);
        assert(ch.mesh.size()==12);
        int seenDirections=0;
        for(size_t i=0;i<ch.mesh.size();i+=2) {
            uint32_t data=uint32_t(ch.mesh[i]),dim=uint32_t(ch.mesh[i+1]);
            int d=int((data>>17)&7);
            assert(d>=0 && d<6);
            seenDirections|=1<<d;
            assert(((data>>20)&7)==GRASS);
            assert((dim&255)==2 && ((dim>>8)&255)==2);
        }
        assert(seenDirections==63);
    }
    {
        World w;
        auto left=std::make_unique<Chunk>(),right=std::make_unique<Chunk>();
        left->blocks[World::index(15,10,4)]=STONE;
        right->blocks[World::index(0,10,4)]=STONE;
        w.integrate(0,0,std::move(left));w.integrate(1,0,std::move(right));
        auto& a=*w.chunks.at(pair_key(0,0));
        auto& b=*w.chunks.at(pair_key(1,0));
        w.build_mesh(0,0,a);w.build_mesh(1,0,b);
        assert(a.mesh.size()==10 && b.mesh.size()==10); // 5 faces each, shared face hidden.
        w.set(15,10,4,AIR);
        assert(a.dirty && b.dirty); // Seam invalidation crosses chunk boundaries.
        w.build_mesh(0,0,a);w.build_mesh(1,0,b);
        assert(a.mesh.empty() && b.mesh.size()==12);
    }
    {
        World w;
        w.reset(0xC0FFEE);
        w.stream(0,0);
        assert(w.chunks.size()==9);
        constexpr int EXPECTED=(2*LOAD_RADIUS+1)*(2*LOAD_RADIUS+1);
        for(int i=0;i<3000&&w.chunks.size()<EXPECTED;++i) {
            std::this_thread::sleep_for(2ms);
            w.stream(0,0);
        }
        assert(w.chunks.size()==EXPECTED); // Background completion is drained without moving.
        w.rebuild_meshes(EXPECTED);
        assert(w.chunks.at(pair_key(0,0))->revision>0);
        std::int32_t chunks[EXPECTED*4]{};
        // Test the exported native ABI on the separate global engine.
        voxel_init(0xC0FFEE);
        voxel_tick(0,0,0,.016f);
        auto count=voxel_mesh_list(chunks,EXPECTED*4);
        assert(count>=1 && count<=EXPECTED);
        // Revision-zero entries are now exposed early for coarse-only LOD.
        // Choose an already built detailed mesh for the detailed-mesh ABI.
        int detailed=-1;
        for(int i=0;i<count;++i)
            if(chunks[i*4+2]>0 && chunks[i*4+3]>0) {detailed=i;break;}
        assert(detailed>=0);
        auto cx=chunks[detailed*4],cz=chunks[detailed*4+1],quads=chunks[detailed*4+3];
        std::vector<std::int32_t> buffer(size_t(quads)*2);
        assert(voxel_mesh_copy(cx,cz,buffer.data(),int(buffer.size()))==int(buffer.size()));
        assert(voxel_mesh_copy(cx,cz,buffer.data(),1)==-int(buffer.size()));
        std::vector<std::int32_t> distant(256);
        int farWords=voxel_mesh_copy_far(cx,cz,distant.data(),int(distant.size()));
        assert(farWords>0 && farWords<=160 && (farWords%2)==0);
        assert(voxel_mesh_copy_far(cx,cz,distant.data(),1)==-farWords);
        assert(farWords<int(buffer.size())); // Distant terrain is simpler.
        float cam[5]{};
        voxel_camera(cam,5);
        assert(std::isfinite(cam[0]) && std::isfinite(cam[1]));
    }
    std::remove("stratawild.svxl");
    std::puts("stratawild greedy mesh / streaming / ABI: OK");
}
