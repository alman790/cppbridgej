#include "../../main/cpp/stratawild.cpp"
#include <array>
#include <cassert>
#include <cstdint>
#include <cstdio>
#include <vector>

int main() {
    // Exhaustive face-by-face equivalence between greedy output and the
    // unmerged voxel surface, including all orientations and seam borders.
    for(uint32_t trial=0;trial<5;++trial) {
        World w;
        int cx=trial%2? -3:3, cz=trial%3? -2:2;
        auto ch=std::make_unique<Chunk>();
        for(int y=0;y<HEIGHT;++y) for(int z=0;z<SIDE;++z) for(int x=0;x<SIDE;++x) {
            uint32_t h=cell_hash(x+y*139,z+y*17,trial+9876);
            if((h%100)<(trial==0?15u:8u)) ch->blocks[World::index(x,y,z)]=uint8_t(1+h%7);
        }
        w.integrate(cx,cz,std::move(ch));
        auto& chunk=*w.chunks.at(pair_key(cx,cz));
        std::vector<uint8_t> needed(6*CHUNK_VOLUME,0);
        for(int dir=0;dir<6;++dir) {
            int ax=dir/2,sign=(dir&1)?1:-1;
            for(int y=0;y<HEIGHT;++y) for(int z=0;z<SIDE;++z) for(int x=0;x<SIDE;++x) {
                uint8_t block=chunk.blocks[World::index(x,y,z)];
                if(block==AIR) continue;
                int nx=x+(ax==0?sign:0),ny=y+(ax==1?sign:0),nz=z+(ax==2?sign:0);
                uint8_t neighbor=nx>=0&&nx<SIDE&&ny>=0&&ny<HEIGHT&&nz>=0&&nz<SIDE
                    ?chunk.blocks[World::index(nx,ny,nz)]:w.at(cx*SIDE+nx,ny,cz*SIDE+nz);
                if(neighbor==AIR) needed[size_t(dir)*CHUNK_VOLUME+World::index(x,y,z)]=block;
            }
        }
        w.build_mesh(cx,cz,chunk);
        for(size_t i=0;i<chunk.mesh.size();i+=2) {
            uint32_t bits=uint32_t(chunk.mesh[i]),dims=uint32_t(chunk.mesh[i+1]);
            int x=int(bits&31), y=int((bits>>5)&127), z=int((bits>>12)&31);
            int dir=int((bits>>17)&7), mat=int((bits>>20)&7);
            int wu=int(dims&255),hv=int((dims>>8)&255);
            assert(dir>=0&&dir<6&&wu>0&&hv>0&&mat>=1&&mat<=7);
            if(dir&1) {if(dir<2) --x;else if(dir<4) --y;else --z;}
            for(int v=0;v<hv;++v) for(int u=0;u<wu;++u) {
                int xx=x,yy=y,zz=z;
                if(dir<2) {zz+=u;yy+=v;}
                else if(dir<4) {xx+=u;zz+=v;}
                else {xx+=u;yy+=v;}
                assert(xx>=0&&xx<SIDE&&yy>=0&&yy<HEIGHT&&zz>=0&&zz<SIDE);
                uint8_t& slot=needed[size_t(dir)*CHUNK_VOLUME+World::index(xx,yy,zz)];
                assert(slot==mat); // Fails on missing, extra, wrong-material or duplicate face.
                slot=0;
            }
        }
        for(uint8_t slot:needed) assert(slot==0);
    }
    std::puts("stratawild greedy geometry fuzz: OK");
}
