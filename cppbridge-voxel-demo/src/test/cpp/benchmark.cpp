#include "../../main/cpp/stratawild.cpp"
#include <chrono>
#include <cstdio>
int main() {
    using clock=std::chrono::steady_clock;
    World world;
    constexpr int r=LOAD_RADIUS;
    world.center_x=world.center_z=0;
    auto start=clock::now();
    for(int z=-r;z<=r;++z) for(int x=-r;x<=r;++x)
        world.integrate(x,z,world.generate(x,z));
    auto gen=clock::now();
    world.rebuild_meshes((2*r+1)*(2*r+1));
    auto mesh=clock::now();
    size_t nearQuads=0,farQuads=0,selectedQuads=0;
    for(auto& [key,ch]:world.chunks) {
        int x=int32_t(key>>32),z=int32_t(uint32_t(key));
        bool far=(x*16.0f*x*16.0f+z*16.0f*z*16.0f)>85.f*85.f;
        nearQuads+=ch->mesh.size()/2;
        farQuads+=ch->far_mesh.size()/2;
        selectedQuads+=(far?ch->far_mesh:ch->mesh).size()/2;
    }
    double genMs=std::chrono::duration<double,std::milli>(gen-start).count();
    double meshMs=std::chrono::duration<double,std::milli>(mesh-gen).count();
    std::printf("Stratawild 0.8 (synchronous reference) radius=%d chunks=%zu generated=%.1fms meshed=%.1fms\n",
               r,world.chunks.size(),genMs,meshMs);
    std::printf("near_quads=%zu all_far_quads=%zu distance_selected_quads=%zu\n",
               nearQuads,farQuads,selectedQuads);
}
