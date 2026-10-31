#include <cassert>
#include <cstdint>
#include <cstdio>
#include <vector>
extern "C" {
void voxel_init(std::int64_t);
void voxel_tick(std::int32_t,float,float,float);
void voxel_render(std::int32_t*,std::int32_t,std::int32_t,std::int32_t);
std::int32_t voxel_interact(std::int32_t,std::int32_t);
std::int32_t voxel_save();
std::int32_t voxel_loaded_chunks();
}
int main() {
    std::vector<int32_t> a(160*90),b(160*90);
    voxel_init(12345);
    assert(voxel_loaded_chunks()>=9 && voxel_loaded_chunks()<=121);
    voxel_render(a.data(),int(a.size()),160,90);
    voxel_init(12345);
    voxel_render(b.data(),int(b.size()),160,90);
    assert(a==b);
    int sky=0,ground=0;
    for(auto color:a) {
        assert((uint32_t(color)>>24)==255u);
        if(color==a[0]) ++sky;
        if(color==a.back()) ++ground;
    }
    assert(sky>0 && ground>0);
    voxel_tick(1,0,0,0.016f);
    voxel_tick(16,0,0,0.016f);
    voxel_render(b.data(),int(b.size()),160,90);
    assert(voxel_loaded_chunks()>0);
    // Confirm that save/load restores the original procedural seed.
    assert(voxel_save()==1);
    voxel_init(9999);
    voxel_render(b.data(),int(b.size()),160,90);
    assert(a==b); // first rendered view matches after reloading the saved world
    std::remove("stratawild.svxl");
    std::puts("stratawild native smoke: OK");
}
