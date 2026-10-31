#include "../../main/cpp/stratawild.cpp"
#include <algorithm>
#include <cassert>
#include <cstdio>
#include <deque>
#include <vector>

// Independent implementation of the *previous* sunlight frontier algorithm.
// Compare the entire halo, not only 3 or 4 hand-picked surface samples.
static std::vector<uint8_t> old_halo_reference(World& w,int cx,int cz) {
    constexpr int stride=LIGHT_SPAN;
    const size_t layer=size_t(stride)*stride, volume=layer*HEIGHT;
    std::vector<uint8_t> light(volume,0), blocks(volume,AIR);
    std::deque<int> queue;
    for(int z=0;z<stride;++z) for(int x=0;x<stride;++x) {
        const int wx=cx*SIDE+x-LIGHT_HALO,wz=cz*SIDE+z-LIGHT_HALO;
        const auto it=w.chunks.find(pair_key(floor_div(wx,SIDE),floor_div(wz,SIDE)));
        const Chunk* c=it==w.chunks.end()?nullptr:it->second.get();
        const int lx=positive_mod(wx,SIDE),lz=positive_mod(wz,SIDE);
        bool sky=true;
        for(int y=HEIGHT-1;y>=0;--y) {
            size_t i=size_t(y)*layer+size_t(z)*stride+size_t(x);
            uint8_t block=c?c->blocks[World::index(lx,y,lz)]:uint8_t(AIR);
            blocks[i]=block;
            if(block==LAMP) {light[i]=14;queue.push_back(int(i));sky=false;}
            else if(opaque(block)) sky=false;
            else if(sky) light[i]=15;
        }
    }
    constexpr int delta[6][3]={{-1,0,0},{1,0,0},{0,-1,0},{0,1,0},{0,0,-1},{0,0,1}};
    for(int y=0;y<HEIGHT;++y) for(int z=0;z<stride;++z) for(int x=0;x<stride;++x) {
        const size_t i=size_t(y)*layer+size_t(z)*stride+size_t(x);
        if(light[i]!=15) continue;
        for(const auto& d:delta) {
            int nx=x+d[0],ny=y+d[1],nz=z+d[2];
            if(nx<0||nx>=stride||ny<0||ny>=HEIGHT||nz<0||nz>=stride) continue;
            size_t ni=size_t(ny)*layer+size_t(nz)*stride+size_t(nx);
            if(!opaque(blocks[ni]) && light[ni]<14-(blocks[ni]==WATER?1:0)) {
                queue.push_back(int(i));break;
            }
        }
    }
    while(!queue.empty()) {
        int i=queue.front();queue.pop_front();
        int x=i%stride,z=(i/stride)%stride,y=int(size_t(i)/layer);
        int next=int(light[size_t(i)])-1;
        if(next<=0) continue;
        for(const auto& d:delta) {
            int nx=x+d[0],ny=y+d[1],nz=z+d[2];
            if(nx<0||nx>=stride||ny<0||ny>=HEIGHT||nz<0||nz>=stride) continue;
            size_t ni=size_t(ny)*layer+size_t(nz)*stride+size_t(nx);
            if(opaque(blocks[ni])) continue;
            int strength=next-(blocks[ni]==WATER?1:0);
            if(strength>light[ni]) {light[ni]=uint8_t(strength);queue.push_back(int(ni));}
        }
    }
    return light;
}

static void check_halo(World& w,int cx,int cz) {
    Chunk dest;
    w.rebuild_light_halo(cx,cz,dest);
    const auto reference=old_halo_reference(w,cx,cz);
    if(w.light_halo!=reference) {
        for(size_t i=0;i<reference.size();++i) if(w.light_halo[i]!=reference[i]) {
            std::fprintf(stderr,"halo mismatch index %zu, new=%u, previous=%u\n",i,
                         unsigned(w.light_halo[i]),unsigned(reference[i]));
            break;
        }
        assert(false && "optimized lighting differs from old lighting");
    }
}

int main() {
    // Favor forward-facing mesh jobs at equal distances, but never allow a
    // distant chunk to outrank the immediate collision neighborhood.
    assert(mesh_priority(0,4,0,1)<mesh_priority(0,-4,0,1));
    assert(mesh_priority(0,-4,0,-1)<mesh_priority(0,4,0,-1));
    assert(mesh_priority(1,0,0,1)<mesh_priority(0,3,0,1));
    assert(mesh_priority(0,0,0,1)==0);

    World w;
    w.center_x=w.center_z=0;
    w.seed=0xA7C0241u;
    for(int cz=-1;cz<=1;++cz) for(int cx=-1;cx<=1;++cx)
        w.integrate(cx,cz,w.generate(cx,cz));
    check_halo(w,0,0);
    // Several light emitters, caves and water at chunk seams.
    for(int x=7;x<26;++x) {
        w.set(x,29,12,AIR);
        w.set(x,30,12,AIR);
    }
    w.set(15,29,12,LAMP);
    w.set(16,29,12,WATER);
    check_halo(w,0,0);
    check_halo(w,1,0);
    w.set(15,29,12,STONE);
    check_halo(w,0,0);

    // All 4 far-mesh borders must have a vertical skirt to y=6, preserving
    // the 2-word-per-quad binary format for the existing GPU shader.
    auto ch=w.generate(3,3);
    int skirt[4]={};
    for(size_t i=0;i<ch->far_mesh.size();i+=2) {
        const uint32_t a=uint32_t(ch->far_mesh[i]);
        const uint32_t b=uint32_t(ch->far_mesh[i+1]);
        const int x=int(a&31u), y=int((a>>5)&127u), z=int((a>>12)&31u), dir=int((a>>17)&7u);
        const int height=int((b>>8)&255u);
        if(y!=6 || height<=0) continue;
        if(dir==0&&x==0) skirt[0]++;
        if(dir==1&&x==SIDE) skirt[1]++;
        if(dir==4&&z==0) skirt[2]++;
        if(dir==5&&z==SIDE) skirt[3]++;
    }
    for(int count:skirt) assert(count==SIDE/4);
    assert(ch->far_mesh.size()%2==0 && ch->far_mesh.size()<=512);
    std::puts("Stratawild 0.7: priority, light reference parity, LOD perimeter skirts: OK");
}
