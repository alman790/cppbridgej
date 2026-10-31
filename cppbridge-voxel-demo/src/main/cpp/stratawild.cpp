#include <algorithm>
#include <atomic>
#include <array>
#include <condition_variable>
#include <cstring>
#include <deque>
#include <mutex>
#include <unordered_set>
#include <utility>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <fstream>
#include <limits>
#include <memory>
#include <stdexcept>
#include <string>
#include <thread>
#include <unordered_map>
#include <vector>

#ifdef _WIN32
#define EXPORT extern "C" __declspec(dllexport)
#else
#define EXPORT extern "C" __attribute__((visibility("default")))
#endif

namespace {
constexpr int SIDE = 16;
constexpr int HEIGHT = 80;
constexpr int CHUNK_VOLUME = SIDE * SIDE * HEIGHT;
constexpr int LOAD_RADIUS = 6;
constexpr int UNLOAD_RADIUS = 8;
constexpr float MAX_VIEW = 56.0f;
constexpr float EYE = 1.62f;
constexpr float PLAYER_HEIGHT = 1.75f;
constexpr float PLAYER_RADIUS = 0.29f;
constexpr int WATER_LEVEL = 31;
constexpr int MATERIAL_COUNT = 10;
constexpr int LIGHT_HALO = 15; // Max propagation distance for a 15-level light source.
constexpr int LIGHT_SPAN = SIDE + 2 * LIGHT_HALO;
constexpr int LIQUID_SOURCE = 8;
constexpr int LIQUID_FALL = 7;

enum Block : uint8_t { AIR, GRASS, EARTH, STONE, SAND, WOOD, LEAF, CRYSTAL, LAMP, WATER };
bool opaque(uint8_t b) { return b != AIR && b != WATER; }
bool walkable(uint8_t b) { return b == AIR || b == WATER; }
constexpr std::array<int32_t,MATERIAL_COUNT> STARTING_ITEMS{0,24,24,32,24,24,24,16,12,0};

struct V3 { float x, y, z; };
V3 operator+(V3 a, V3 b) { return {a.x+b.x,a.y+b.y,a.z+b.z}; }
V3 operator*(V3 a, float b) { return {a.x*b,a.y*b,a.z*b}; }
float dot(V3 a, V3 b) { return a.x*b.x+a.y*b.y+a.z*b.z; }
V3 normalize(V3 v) {
    float inv = 1.0f / std::sqrt(std::max(1e-12f, dot(v,v)));
    return v * inv;
}
int floor_div(int a, int b) { int q=a/b, r=a%b; return q - (r<0 ? 1 : 0); }
int positive_mod(int a, int b) { int r=a%b; return r<0 ? r+b : r; }
uint64_t pair_key(int x, int z) {
    return (uint64_t(uint32_t(x)) << 32) | uint32_t(z);
}
// Distance dominates; for comparable distances prefer chunks inside the
// forward view cone. Near/player-adjacent meshes remain highest priority.
int mesh_priority(int dx,int dz,float forward_x,float forward_z) {
    const int distance_sq=dx*dx+dz*dz;
    if(distance_sq<=2) return distance_sq*1024;
    const float dot_forward=float(dx)*forward_x+float(dz)*forward_z;
    const int view_bonus=int(std::clamp(dot_forward, -8.0f, 8.0f)*64.0f);
    return distance_sq*1024 - view_bonus;
}

uint32_t mix(uint32_t n) {
    n ^= n >> 16; n *= 0x7feb352dU; n ^= n >> 15;
    n *= 0x846ca68bU; n ^= n >> 16; return n;
}
uint32_t cell_hash(int x, int z, uint32_t seed) {
    return mix(uint32_t(x)*0x9e3779b9U ^ uint32_t(z)*0x85ebca6bU ^ seed);
}
float fade(float t) { return t*t*(3.0f-2.0f*t); }
float noise(float x, float z, uint32_t seed) {
    int ix=static_cast<int>(std::floor(x)), iz=static_cast<int>(std::floor(z));
    float fx=fade(x-ix), fz=fade(z-iz);
    auto n=[seed](int xx,int zz) { return float(cell_hash(xx,zz,seed)&0xffffU)/65535.0f; };
    float a=n(ix,iz)*(1-fx)+n(ix+1,iz)*fx;
    float b=n(ix,iz+1)*(1-fx)+n(ix+1,iz+1)*fx;
    return a*(1-fz)+b*fz;
}

// Trilinear value noise, entirely determined by world-space integer cells.
// No mutable random state: results are identical across chunk boundaries.
float noise3(float x,float y,float z,uint32_t seed) {
    const int ix=int(std::floor(x)),iy=int(std::floor(y)),iz=int(std::floor(z));
    const float fx=fade(x-ix),fy=fade(y-iy),fz=fade(z-iz);
    const auto v=[seed](int xx,int yy,int zz) -> float {
        uint32_t h=mix(uint32_t(xx)*0x9e3779b9U ^ uint32_t(yy)*0x85ebca6bU
                     ^ uint32_t(zz)*0xc2b2ae35U ^ seed);
        return float(h&65535u)*(1.0f/65535.0f);
    };
    auto lerp=[](float a,float b,float t) {return a+(b-a)*t;};
    float a=lerp(v(ix,iy,iz),v(ix+1,iy,iz),fx);
    float b=lerp(v(ix,iy+1,iz),v(ix+1,iy+1,iz),fx);
    float c=lerp(v(ix,iy,iz+1),v(ix+1,iy,iz+1),fx);
    float d=lerp(v(ix,iy+1,iz+1),v(ix+1,iy+1,iz+1),fx);
    return lerp(lerp(a,b,fy),lerp(c,d,fy),fz);
}

enum class Biome : uint8_t { GROVE, PRAIRIE, BARRENS, HIGHLAND };

struct Position {
    int32_t x, z;
    uint8_t y;
    bool operator==(const Position& o) const noexcept { return x==o.x && z==o.z && y==o.y; }
};
struct PositionHash {
    size_t operator()(Position p) const noexcept {
        uint64_t k=pair_key(p.x,p.z) ^ (uint64_t(p.y)*0x9e3779b97f4a7c15ULL);
        k ^= k>>30; k *= 0xbf58476d1ce4e5b9ULL;
        k ^= k>>27; k *= 0x94d049bb133111ebULL;
        return size_t(k^(k>>31));
    }
};
// Each mesh face is represented by two 32-bit words; GPU expands a quad into triangles.
// word0: x:5 y:7 z:5 direction:3 material:4;
// word1: width:8 height:8 AO at bits 16..23 and cave light at 24..31.
// Faces are generated ONLY for air/solid boundaries, then merged greedily.
// Immutable voxel snapshots are reused until THIS chunk's block data changes.
// Neighbor remeshing no longer clones unchanged voxel arrays on the game thread.
struct VoxelSnapshot {
    std::array<uint8_t,CHUNK_VOLUME> blocks;
    std::array<uint8_t,CHUNK_VOLUME> fluid;
};
struct Chunk {
    std::shared_ptr<const VoxelSnapshot> snapshot;
    std::array<uint8_t,CHUNK_VOLUME> blocks{};
    std::vector<std::int32_t> mesh;
    std::vector<std::int32_t> water_mesh;
    // 1 byte / voxel, rebuilt only for edited or newly loaded chunks.
    std::array<uint8_t,CHUNK_VOLUME> light{};
    // Zero for dry cells; 1..7 flowing water; 8 marks persistent source water.
    std::array<uint8_t,CHUNK_VOLUME> fluid{};
    // 4x4 terrain-tile heightfield impostor; generated once on the worker.
    std::vector<std::int32_t> far_mesh;
    std::int32_t revision=0;
    // A mesh result is valid only for this specific loaded chunk instance
    // and all its sampled neighboring blocks. No worker mutates live chunks.
    std::uint64_t generation=0;
    std::uint64_t mesh_epoch=1;
    // A worker can check the version without reading any mutable world data.
    // Shared lifetime allows cancellation even after the chunk is unloaded.
    std::shared_ptr<std::atomic<std::uint64_t>> mesh_ticket =
        std::make_shared<std::atomic<std::uint64_t>>(1);
    bool dirty=true;
};

class World {
public:
    uint32_t seed=0xBEEF7821u;
    std::unordered_map<uint64_t, std::unique_ptr<Chunk>> chunks;
    std::unordered_map<Position,uint8_t,PositionHash> edits;
    std::array<int32_t,MATERIAL_COUNT> inventory=STARTING_ITEMS;
    int center_x=std::numeric_limits<int>::max(), center_z=std::numeric_limits<int>::max();
    // The worker only reads an immutable seed and creates standalone chunk data.
    // The game thread alone owns loaded chunks, player edits and mesh caches.
    struct Request { int cx, cz; std::uint64_t stream_epoch=0; };
    struct Completed { int cx, cz; std::unique_ptr<Chunk> chunk; };
    std::mutex queue_mutex;
    std::condition_variable_any queue_cv;
    std::deque<Request> pending;
    std::deque<Completed> completed;
    std::unordered_set<uint64_t> scheduled;
    std::jthread worker;
    // Monotone cancellation ticket for cross-region terrain jobs.
    std::atomic<std::uint64_t> stream_epoch{1};
    std::atomic<int32_t> terrain_canceled{0};
    std::atomic<int32_t> mesh_canceled{0};
    int32_t mesh_committed=0, mesh_discarded=0;
    std::unordered_map<uint64_t,std::vector<Position>> edit_index;
    // Updated by the simulation thread; read only while prioritizing jobs.
    float view_x=0.0f, view_z=1.0f;
    std::int32_t next_revision=0;
    std::uint64_t next_generation=0;
    // Independent mesh worker: jobs are immutable copies of a 3x3 neighborhood.
    // Snapshotting occurs exclusively on the simulation thread. Work and result
    // queues are protected separately from the terrain-generation worker.
    struct MeshRequest {
        int cx=0,cz=0;
        std::uint64_t generation=0, epoch=0;
        std::shared_ptr<std::atomic<std::uint64_t>> ticket;
        std::vector<std::pair<uint64_t,std::shared_ptr<const VoxelSnapshot>>> neighborhood;
    };
    struct MeshResult {
        int cx=0,cz=0;
        std::uint64_t generation=0, epoch=0;
        bool success=false;
        std::vector<int32_t> opaque_mesh, fluid_mesh;
        std::array<uint8_t,CHUNK_VOLUME> light{};
    };
    std::mutex mesh_mutex;
    std::condition_variable_any mesh_cv;
    std::deque<MeshRequest> mesh_pending;
    std::deque<MeshResult> mesh_completed;
    // Only the main thread touches this set, including job enqueue/completion.
    std::unordered_set<uint64_t> mesh_scheduled;
    std::jthread mesh_worker;
    static constexpr int MESH_IN_FLIGHT_LIMIT=4;
    // World-local event queue; never iterates all liquid cells each frame.
    std::deque<Position> liquid_pending;
    std::unordered_set<Position,PositionHash> liquid_scheduled;
    // Reused by meshing: a 15-cell halo is sufficient to eliminate light seams.
    std::vector<uint8_t> light_halo;
    std::vector<uint8_t> light_block_halo;
    std::vector<int> light_queue;

    World()=default;
    World(const World&)=delete;
    World& operator=(const World&)=delete;
    ~World() { stop_worker(); }
    void stop_worker() {
        if(mesh_worker.joinable()) {
            mesh_worker.request_stop();
            mesh_cv.notify_all();
            mesh_worker.join();
        }
        if(worker.joinable()) {
            worker.request_stop();
            queue_cv.notify_all();
            worker.join();
        }
    }
    void reset(uint32_t s) {
        stop_worker();
        seed=s;
        chunks.clear(); edits.clear(); edit_index.clear(); inventory=STARTING_ITEMS;
        next_revision=0; next_generation=0;
        stream_epoch.fetch_add(1,std::memory_order_release);
        terrain_canceled.store(0);mesh_canceled.store(0);
        mesh_committed=mesh_discarded=0;
        {
            std::lock_guard lock(mesh_mutex);
            mesh_pending.clear(); mesh_completed.clear();
        }
        mesh_scheduled.clear();
        liquid_pending.clear();liquid_scheduled.clear();
        light_halo.clear();light_block_halo.clear();light_queue.clear();
        view_x=0.0f; view_z=1.0f;
        center_x=center_z=std::numeric_limits<int>::max();
        {
            std::lock_guard lock(queue_mutex);
            pending.clear(); completed.clear(); scheduled.clear();
        }
    }
    void start_worker() {
        worker=std::jthread([this](std::stop_token stop) {
            while(!stop.stop_requested()) {
                Request request{};
                {
                    std::unique_lock lock(queue_mutex);
                    queue_cv.wait(lock,stop,[this]{return !pending.empty();});
                    if(stop.stop_requested()) break;
                    request=pending.front(); pending.pop_front();
                }
                std::unique_ptr<Chunk> generated;
                // The current center is owned by the main thread. Only the
                // atomic ticket is read here, never center_x/center_z.
                if(request.stream_epoch==stream_epoch.load(std::memory_order_acquire))
                    generated=generate(request.cx,request.cz);
                if(!generated) terrain_canceled.fetch_add(1,std::memory_order_relaxed);
                {
                    std::lock_guard lock(queue_mutex);
                    completed.push_back({request.cx,request.cz,std::move(generated)});
                }
            }
        });
    }


    // A mesh job never looks at this World after dispatch. Its own staging
    // World receives only copied blocks/fluids, so game-thread edits, streaming
    // and save/load cannot race background AO or halo lighting calculations.
    void start_mesh_worker() {
        mesh_worker=std::jthread([this](std::stop_token stop) {
            World staging;
            while(!stop.stop_requested()) {
                MeshRequest job;
                {
                    std::unique_lock lock(mesh_mutex);
                    mesh_cv.wait(lock,stop,[this] { return !mesh_pending.empty(); });
                    if(stop.stop_requested()) break;
                    job=std::move(mesh_pending.front());
                    mesh_pending.pop_front();
                }
                MeshResult result;
                result.cx=job.cx; result.cz=job.cz;
                result.generation=job.generation; result.epoch=job.epoch;
                try {
                    // A queued snapshot whose source changed can be dropped
                    // before the expensive sunlight flood-fill begins.
                    if(job.ticket->load(std::memory_order_acquire)!=job.epoch) {
                        mesh_canceled.fetch_add(1,std::memory_order_relaxed);
                    } else {
                    staging.chunks.clear();
                    for(const auto& item:job.neighborhood) {
                        // Mutable staging belongs exclusively to this worker;
                        // other mesh requests can share an immutable source.
                        auto copy=std::make_unique<Chunk>();
                        copy->blocks=item.second->blocks;
                        copy->fluid=item.second->fluid;
                        staging.chunks.emplace(item.first,std::move(copy));
                    }
                    auto it=staging.chunks.find(pair_key(job.cx,job.cz));
                    if(it!=staging.chunks.end()) {
                        staging.build_mesh(job.cx,job.cz,*it->second);
                        result.opaque_mesh=std::move(it->second->mesh);
                        result.fluid_mesh=std::move(it->second->water_mesh);
                        result.light=it->second->light;
                        result.success=(job.ticket->load(std::memory_order_acquire)==job.epoch);
                        if(!result.success) {
                            result.opaque_mesh.clear();result.fluid_mesh.clear();
                            mesh_canceled.fetch_add(1,std::memory_order_relaxed);
                        }
                    }
                    }
                } catch(const std::exception&) {
                    // Leave the chunk dirty for the next attempt; no live data
                    // is ever touched or lost if a worker allocation fails.
                }
                staging.chunks.clear();
                {
                    std::lock_guard lock(mesh_mutex);
                    mesh_completed.push_back(std::move(result));
                }
            }
        });
    }

    static void mark_dirty(Chunk& ch) {
        ch.dirty=true;
        ++ch.mesh_epoch;
        ch.mesh_ticket->store(ch.mesh_epoch,std::memory_order_release);
    }
    static void invalidate_mesh(Chunk& ch) {
        ch.mesh_ticket->store(0,std::memory_order_release);
    }

    // Consume finished jobs only on the game thread. Ignore stale snapshots
    // after edits, water updates, neighbor changes, unload/reload or reset.
    void drain_mesh_results() {
        std::deque<MeshResult> ready;
        {
            std::lock_guard lock(mesh_mutex);
            ready.swap(mesh_completed);
        }
        for(auto& item:ready) {
            const auto key=pair_key(item.cx,item.cz);
            mesh_scheduled.erase(key);
            auto it=chunks.find(key);
            if(it==chunks.end()) continue;
            Chunk& ch=*it->second;
            if(!item.success || !ch.dirty || ch.generation!=item.generation ||
               ch.mesh_epoch!=item.epoch) {++mesh_discarded;continue;}
            ch.mesh=std::move(item.opaque_mesh);
            ch.water_mesh=std::move(item.fluid_mesh);
            ch.light=std::move(item.light);
            ch.dirty=false;
            ch.revision=++next_revision;
            ++mesh_committed;
        }
    }

    // Enqueue nearest dirty chunks without doing full lighting on the main
    // thread. Keep the number of immutable snapshots tightly bounded.
    void update_mesh_async(int dispatch_budget=2) {
        drain_mesh_results();
        // Snapshot work that has not begun yet can be evicted immediately.
        // This also reclaims its copied 3x3 voxel neighborhoods (up to ~360KB
        // per request) and frees a slot for a current, higher-priority chunk.
        {
            std::lock_guard lock(mesh_mutex);
            for(auto it=mesh_pending.begin();it!=mesh_pending.end();) {
                if(it->ticket->load(std::memory_order_acquire)==it->epoch) {
                    ++it;continue;
                }
                mesh_scheduled.erase(pair_key(it->cx,it->cz));
                it=mesh_pending.erase(it);
                mesh_canceled.fetch_add(1,std::memory_order_relaxed);
            }
        }
        if(mesh_scheduled.size()>=MESH_IN_FLIGHT_LIMIT) return;
        std::vector<std::pair<int,uint64_t>> candidates;
        for(const auto& [key,ch]:chunks) if(ch->dirty && !mesh_scheduled.contains(key)) {
            int x=int32_t(key>>32),z=int32_t(uint32_t(key));
            int dx=x-center_x,dz=z-center_z;
            candidates.emplace_back(mesh_priority(dx,dz,view_x,view_z),key);
        }
        const int count=std::min({dispatch_budget,
                                  MESH_IN_FLIGHT_LIMIT-int(mesh_scheduled.size()),
                                  int(candidates.size())});
        if(count<=0) return;
        std::partial_sort(candidates.begin(),candidates.begin()+count,candidates.end());
        for(int i=0;i<count;++i) {
            const uint64_t key=candidates[size_t(i)].second;
            Chunk& target=*chunks.at(key);
            const int cx=int32_t(key>>32),cz=int32_t(uint32_t(key));
            MeshRequest job;
            job.cx=cx;job.cz=cz;
            job.generation=target.generation;job.epoch=target.mesh_epoch;
            job.ticket=target.mesh_ticket;
            job.neighborhood.reserve(9);
            for(int dz=-1;dz<=1;++dz) for(int dx=-1;dx<=1;++dx) {
                const uint64_t neighbor=pair_key(cx+dx,cz+dz);
                auto it=chunks.find(neighbor);
                if(it==chunks.end()) continue;
                Chunk& source=*it->second;
                if(!source.snapshot) {
                    auto cached=std::make_shared<VoxelSnapshot>();
                    cached->blocks=source.blocks;
                    cached->fluid=source.fluid;
                    source.snapshot=std::move(cached);
                }
                job.neighborhood.emplace_back(neighbor,source.snapshot);
            }
            {
                std::lock_guard lock(mesh_mutex);
                mesh_pending.push_back(std::move(job));
            }
            mesh_scheduled.insert(key);
        }
        if(!mesh_worker.joinable()) start_mesh_worker();
        mesh_cv.notify_all();
    }

    // Fixed-size diagnostics: safe one-call FFM boundary, no allocations.
    // [loaded, genQueued, genReady, meshQueued, meshReady, meshScheduled,
    //  meshCommitted, meshDiscarded, meshCanceled, genCanceled]
    void stream_stats(int32_t* out) {
        out[0]=int32_t(chunks.size());
        {
            std::lock_guard lock(queue_mutex);
            out[1]=int32_t(pending.size());out[2]=int32_t(completed.size());
        }
        {
            std::lock_guard lock(mesh_mutex);
            out[3]=int32_t(mesh_pending.size());out[4]=int32_t(mesh_completed.size());
        }
        out[5]=int32_t(mesh_scheduled.size());
        out[6]=mesh_committed;out[7]=mesh_discarded;
        out[8]=mesh_canceled.load(std::memory_order_relaxed);
        out[9]=terrain_canceled.load(std::memory_order_relaxed);
    }

    int terrain_height(int x,int z) const {
        float a=noise(x/90.0f,z/90.0f,seed);
        float b=noise(x/35.0f,z/35.0f,seed+1);
        float c=noise(x/12.0f,z/12.0f,seed+2);
        return std::clamp(int(13+22*a+13*b+5*c),7,HEIGHT-12);
    }
    Biome biome_at(int x,int z) const {
        float humidity=noise(x/190.0f,z/190.0f,seed+1337u);
        float altitude=noise(x/175.0f,z/175.0f,seed+4301u);
        if(altitude>0.72f) return Biome::HIGHLAND;
        if(humidity<0.35f) return Biome::BARRENS;
        if(humidity>0.59f) return Biome::GROVE;
        return Biome::PRAIRIE;
    }
    // Caves are volumetric: a continuous 3D field, not a 2D heightmap trick.
    // A higher near-surface threshold leaves most soil intact while occasionally
    // opening a natural skylight. Bedrock y<6 remains solid.
    bool cave_at(int x,int y,int z,int surface) const {
        if(y<6 || y>surface) return false;
        float a=noise3(x*0.068f,y*0.082f,z*0.068f,seed+701u);
        float b=noise3(x*0.036f+14.0f,y*0.057f-9.0f,z*0.036f,seed+919u);
        float field=0.77f*a+0.23f*b;
        return field>(y>=surface-2 ? 0.715f : 0.645f);
    }
    uint8_t surface_block(int x,int z,int h) const {
        Biome biome=biome_at(x,z);
        if(biome==Biome::BARRENS || h<27) return SAND;
        if(biome==Biome::HIGHLAND && h>41) return STONE;
        return GRASS;
    }
    static size_t index(int x,int y,int z) { return (size_t(y)*SIDE+size_t(z))*SIDE+size_t(x); }
    static void set_local(Chunk& ch, int wx,int y,int wz,int cx,int cz,uint8_t b) {
        int xx=wx-cx*SIDE, zz=wz-cz*SIDE;
        if(xx>=0&&xx<SIDE&&zz>=0&&zz<SIDE&&y>=0&&y<HEIGHT)
            ch.blocks[index(xx,y,zz)]=b;
    }
    // Approximate the far-field terrain using sixteen 4x4 tiles.
    // This *intentionally* omits underground caves and individual trees;
    // the detailed geometry remains active near the player.
    void build_far_mesh(int cx,int cz,Chunk& ch) const {
        constexpr int TILE=4;
        constexpr int TILES=SIDE/TILE;
        int heights[TILES+2][TILES+2]{};
        for(int tz=-1;tz<=TILES;++tz) for(int tx=-1;tx<=TILES;++tx) {
            int wx=cx*SIDE+tx*TILE,wz=cz*SIDE+tz*TILE;
            int sum=terrain_height(wx+1,wz+1)+terrain_height(wx+3,wz+1)
                   +terrain_height(wx+1,wz+3)+terrain_height(wx+3,wz+3);
            heights[tz+1][tx+1]=std::clamp(((sum+4)/4+1)/2*2,6,HEIGHT-8);
        }
        auto append=[&](int x,int y,int z,int direction,int material,int w,int h) {
            if(w<=0 || h<=0) return;
            uint32_t a=uint32_t(x)|(uint32_t(y)<<5)|(uint32_t(z)<<12)
                      |(uint32_t(direction)<<17)|(uint32_t(material)<<20);
            uint32_t b=uint32_t(w)|(uint32_t(h)<<8)|0xFF000000u; // Sunlit far-field vertices.
            ch.far_mesh.push_back(int32_t(a));
            ch.far_mesh.push_back(int32_t(b));
        };
        ch.far_mesh.reserve(TILES*TILES*10);
        for(int tz=0;tz<TILES;++tz) for(int tx=0;tx<TILES;++tx) {
            int x=tx*TILE,z=tz*TILE;
            int h=heights[tz+1][tx+1];
            auto material=surface_block(cx*SIDE+x+2,cz*SIDE+z+2,h);
            append(x,h+1,z,3,int(material),TILE,TILE);
            int west=heights[tz+1][tx],east=heights[tz+1][tx+2];
            int north=heights[tz][tx+1],south=heights[tz+2][tx+1];
            // A far chunk can neighbor a fully detailed chunk whose real
            // terrain height differs from our 4x4 approximation. Perimeter
            // skirts close those gaps; interior faces remain height-clipped.
            // Mesh words, shader inputs and FFM ABI do not change.
            if(tx==0) append(x,6,z,0,STONE,TILE,h-6);
            else if(h>west) append(x,west+1,z,0,STONE,TILE,h-west);
            if(tx==TILES-1) append(x+TILE,6,z,1,STONE,TILE,h-6);
            else if(h>east) append(x+TILE,east+1,z,1,STONE,TILE,h-east);
            if(tz==0) append(x,6,z,4,STONE,TILE,h-6);
            else if(h>north) append(x,north+1,z,4,STONE,TILE,h-north);
            if(tz==TILES-1) append(x,6,z+TILE,5,STONE,TILE,h-6);
            else if(h>south) append(x,south+1,z+TILE,5,STONE,TILE,h-south);
        }
    }
    std::unique_ptr<Chunk> generate(int cx,int cz) const {
        auto ch=std::make_unique<Chunk>();
        for(int z=0;z<SIDE;++z) for(int x=0;x<SIDE;++x) {
            int wx=cx*SIDE+x,wz=cz*SIDE+z;
            int h=terrain_height(wx,wz);
            for(int y=0;y<=h;++y) {
                uint8_t type=y==h ? surface_block(wx,wz,h) : (y>=h-4?uint8_t(EARTH):uint8_t(STONE));
                if(cave_at(wx,y,wz,h)) type=AIR;
                else if(type==STONE && y<20 && (cell_hash(wx+y,wz-y,seed)%173)==0)
                    type=CRYSTAL;
                ch->blocks[index(x,y,z)]=type;
            }
        }
        // Still-water basins; liquids are not simulated and do not flow yet.
        for(int z=0;z<SIDE;++z) for(int x=0;x<SIDE;++x) {
            const int wx=cx*SIDE+x,wz=cz*SIDE+z;
            int h=terrain_height(wx,wz);
            if(h<WATER_LEVEL) for(int y=h+1;y<=WATER_LEVEL;++y)
                ch->blocks[index(x,y,z)]=WATER;
            // Underground aquifers are sparse, spatially seeded pockets.
            for(int y=6;y<std::min(h-3,WATER_LEVEL-5);++y) {
                size_t idx=index(x,y,z);
                if(ch->blocks[idx]==AIR &&
                   noise3(wx*.039f+73.0f,y*.052f,wz*.039f-37.0f,seed+447u)>.78f)
                    ch->blocks[idx]=WATER;
            }
        }
        // Trees are derived from world coordinates, including outside chunk bounds.
        // This makes canopy geometry identical regardless of chunk load order.
        for(int z=cz*SIDE-2;z<cz*SIDE+SIDE+2;++z)
            for(int x=cx*SIDE-2;x<cx*SIDE+SIDE+2;++x) {
                uint32_t h=cell_hash(x,z,seed+111);
                int surface=terrain_height(x,z);
                Biome biome=biome_at(x,z);
                if((biome!=Biome::GROVE && biome!=Biome::PRAIRIE)
                        || h % (biome==Biome::GROVE?92u:197u)!=0
                        || surface<WATER_LEVEL || cave_at(x,surface,z,surface)) continue;
                int y0=surface+1;
                int trunk=4+int(h%3);
                for(int y=y0;y<y0+trunk;++y) set_local(*ch,x,y,z,cx,cz,WOOD);
                int canopy=y0+trunk-1;
                for(int dz=-2;dz<=2;++dz) for(int dx=-2;dx<=2;++dx)
                    for(int dy=-2;dy<=2;++dy) {
                        if(dx*dx+dz*dz+dy*dy>7) continue;
                        int xx=x+dx, zz=z+dz, yy=canopy+dy;
                        if(dx==0&&dz==0&&dy<=0) continue;
                        set_local(*ch,xx,yy,zz,cx,cz,LEAF);
                    }
            }
        for(size_t i=0;i<CHUNK_VOLUME;++i)
            if(ch->blocks[i]==WATER) ch->fluid[i]=LIQUID_SOURCE;
        build_far_mesh(cx,cz,*ch);
        return ch;
    }
    // Called only on the game thread when a generated chunk is integrated.
    void apply_edits(Chunk& ch,int cx,int cz) const {
        auto it=edit_index.find(pair_key(cx,cz));
        if(it==edit_index.end()) return;
        for(const Position& p:it->second) {
            auto edit=edits.find(p);
            if(edit!=edits.end()) {
                set_local(ch,p.x,int(p.y),p.z,cx,cz,edit->second);
                const size_t i=index(positive_mod(p.x,SIDE),int(p.y),positive_mod(p.z,SIDE));
                ch.fluid[i]=edit->second==WATER?LIQUID_SOURCE:0;
            }
        }
    }
    void dirty_neighbors(int cx,int cz) {
        // AO samples may reach diagonally across a chunk corner.
        for(int dz=-1;dz<=1;++dz) for(int dx=-1;dx<=1;++dx) {
            auto it=chunks.find(pair_key(cx+dx,cz+dz));
            if(it!=chunks.end()) mark_dirty(*it->second);
        }
    }
    void dirty_after_block(int x,int z) {
        const int cx=floor_div(x,SIDE),cz=floor_div(z,SIDE);
        const int lx=positive_mod(x,SIDE),lz=positive_mod(z,SIDE);
        // A lamp or skylight obstruction can affect cells in another chunk
        // even when edited 10+ blocks away from the physical border. Invalidate
        // chunks whose nearest cell is within the light halo's L1 radius.
        // This also covers the 1-cell AO/geometry invalidation radius.
        for(int dz=-1;dz<=1;++dz) for(int dx=-1;dx<=1;++dx) {
            const int distance_x=dx<0?lx+1:(dx>0?SIDE-lx:0);
            const int distance_z=dz<0?lz+1:(dz>0?SIDE-lz:0);
            if(distance_x+distance_z>LIGHT_HALO) continue;
            auto it=chunks.find(pair_key(cx+dx,cz+dz));
            if(it!=chunks.end()) mark_dirty(*it->second);
        }
    }
    uint8_t corner_ao(int cx,int cz,int dir,int px,int py,int pz,
                      int w,int h,int u_corner,int v_corner) const {
        int p[3]={cx*SIDE+px,py,cz*SIDE+pz};
        int axis=dir/2;
        int u_axis=axis==0?2:0;
        int v_axis=axis==1?2:1;
        // Sample just OUTSIDE the visible surface, at the adjacent cell corner.
        if((dir&1)==0) --p[axis];
        if(u_corner) p[u_axis]+=w-1;
        if(v_corner) p[v_axis]+=h-1;
        int u_step=u_corner?1:-1,v_step=v_corner?1:-1;
        int side_u[3]={p[0],p[1],p[2]}, side_v[3]={p[0],p[1],p[2]};
        side_u[u_axis]+=u_step;
        side_v[v_axis]+=v_step;
        int diag[3]={side_u[0],side_u[1],side_u[2]};
        diag[v_axis]+=v_step;
        bool a=opaque(at(side_u[0],side_u[1],side_u[2]));
        bool b=opaque(at(side_v[0],side_v[1],side_v[2]));
        bool c=opaque(at(diag[0],diag[1],diag[2]));
        return uint8_t(a&&b?3:(int(a)+int(b)+int(c)));
    }
    void integrate(int cx,int cz,std::unique_ptr<Chunk> ch) {
        uint64_t key=pair_key(cx,cz);
        if(chunks.contains(key)) return;
        apply_edits(*ch,cx,cz);
        ch->generation=++next_generation;
        chunks.emplace(key,std::move(ch));
        dirty_neighbors(cx,cz);
        // Seed only *exposed* water boundaries. Interior lake cells are inert.
        seed_liquid_edges(cx,cz);
        // Neighboring chunks may have been loaded before the new border existed.
        for(int d=0;d<4;++d) {
            constexpr int dx[4]={-1,1,0,0},dz[4]={0,0,-1,1};
            if(chunks.contains(pair_key(cx+dx[d],cz+dz[d])))
                seed_liquid_border(cx+dx[d],cz+dz[d],-dx[d],-dz[d]);
        }
    }
    void schedule_liquid(int x,int y,int z) {
        if(y<1 || y>=HEIGHT) return;
        if(!chunks.contains(pair_key(floor_div(x,SIDE),floor_div(z,SIDE)))) return;
        Position p{x,z,uint8_t(y)};
        if(liquid_scheduled.insert(p).second) liquid_pending.push_back(p);
    }
    void wake_liquid(int x,int y,int z) {
        schedule_liquid(x,y,z);
        schedule_liquid(x,y-1,z);schedule_liquid(x,y+1,z);
        schedule_liquid(x-1,y,z);schedule_liquid(x+1,y,z);
        schedule_liquid(x,y,z-1);schedule_liquid(x,y,z+1);
    }
    uint8_t liquid_at(int x,int y,int z) const {
        if(y<0||y>=HEIGHT) return 0;
        auto it=chunks.find(pair_key(floor_div(x,SIDE),floor_div(z,SIDE)));
        if(it==chunks.end()) return 0;
        size_t i=index(positive_mod(x,SIDE),y,positive_mod(z,SIDE));
        return it->second->blocks[i]==WATER
            ? (it->second->fluid[i]?it->second->fluid[i]:uint8_t(LIQUID_SOURCE)) : 0;
    }
    void seed_liquid_border(int cx,int cz,int dx,int dz) {
        const int x0=cx*SIDE,z0=cz*SIDE;
        for(int y=1;y<HEIGHT;++y) for(int u=0;u<SIDE;++u) {
            const int x=dx<0?x0:(dx>0?x0+SIDE-1:x0+u);
            const int z=dz<0?z0:(dz>0?z0+SIDE-1:z0+u);
            const int nx=x+dx,nz=z+dz;
            if(liquid_at(x,y,z) && at(nx,y,nz)==AIR)
                schedule_liquid(nx,y,nz);
        }
    }
    void seed_liquid_edges(int cx,int cz) {
        const auto it=chunks.find(pair_key(cx,cz));
        if(it==chunks.end()) return;
        const Chunk& ch=*it->second;
        for(int y=1;y<HEIGHT;++y) for(int z=0;z<SIDE;++z) for(int x=0;x<SIDE;++x) {
            if(ch.blocks[index(x,y,z)]!=WATER) continue;
            int wx=cx*SIDE+x,wz=cz*SIDE+z;
            if(at(wx,y-1,wz)==AIR) schedule_liquid(wx,y-1,wz);
            constexpr int dx[4]={-1,1,0,0},dz[4]={0,0,-1,1};
            for(int d=0;d<4;++d)
                if(at(wx+dx[d],y,wz+dz[d])==AIR)
                    schedule_liquid(wx+dx[d],y,wz+dz[d]);
        }
    }
    // Deterministic, event-driven relaxation. Sources never deplete; weaker
    // water recedes when its support disappears. Processing is frame-budgeted.
    void tick_water(int budget) {
        for(int n=0;n<budget && !liquid_pending.empty();++n) {
            Position p=liquid_pending.front();liquid_pending.pop_front();
            liquid_scheduled.erase(p);
            const int x=p.x,y=int(p.y),z=p.z;
            auto it=chunks.find(pair_key(floor_div(x,SIDE),floor_div(z,SIDE)));
            if(it==chunks.end()) continue;
            Chunk& ch=*it->second;
            const size_t i=index(positive_mod(x,SIDE),y,positive_mod(z,SIDE));
            const uint8_t b=ch.blocks[i];
            if(opaque(b)) continue;
            uint8_t old_level=b==WATER?(ch.fluid[i]?ch.fluid[i]:uint8_t(LIQUID_SOURCE)):0;
            if(old_level==LIQUID_SOURCE) continue;
            int desired=liquid_at(x,y+1,z)>0?LIQUID_FALL:0;
            desired=std::max(desired,int(liquid_at(x-1,y,z))-1);
            desired=std::max(desired,int(liquid_at(x+1,y,z))-1);
            desired=std::max(desired,int(liquid_at(x,y,z-1))-1);
            desired=std::max(desired,int(liquid_at(x,y,z+1))-1);
            desired=std::clamp(desired,0,LIQUID_FALL);
            if(desired==old_level) continue;
            ch.fluid[i]=uint8_t(desired);
            ch.blocks[i]=desired?WATER:AIR;
            ch.snapshot.reset();
            dirty_after_block(x,z);
            wake_liquid(x,y,z);
        }
    }
    bool drain_completed() {
        // Swapping the queue holds the mutex for microseconds instead of building meshes under it.
        std::deque<Completed> ready;
        {
            std::lock_guard lock(queue_mutex);
            ready.swap(completed);
        }
        for(auto& item:ready) {
            scheduled.erase(pair_key(item.cx,item.cz));
            if(item.chunk && std::abs(item.cx-center_x)<=LOAD_RADIUS &&
               std::abs(item.cz-center_z)<=LOAD_RADIUS)
                integrate(item.cx,item.cz,std::move(item.chunk));
        }
        // A canceled in-flight job may still be needed at the new center.
        // Rechecking the desired region when a result arrives prevents a
        // permanent hole in the world while the player is standing still.
        return !ready.empty();
    }
    void stream(float px,float pz) {
        int cx=floor_div(int(std::floor(px)),SIDE);
        int cz=floor_div(int(std::floor(pz)),SIDE);
        const bool moved=(cx!=center_x||cz!=center_z);
        if(moved) {
            center_x=cx;center_z=cz;
            stream_epoch.fetch_add(1,std::memory_order_release);
        }
        // Evaluate finished chunks against the *new* view center. Otherwise
        // crossing a border discards chunks that are now exactly in range.
        const bool refill=drain_completed();
        if(!moved&&!refill) return;
        if(moved) {
            // Guarantee a collision-safe neighborhood immediately.
            for(int dz=-1;dz<=1;++dz) for(int dx=-1;dx<=1;++dx) {
                uint64_t key=pair_key(cx+dx,cz+dz);
                if(!chunks.contains(key)) integrate(cx+dx,cz+dz,generate(cx+dx,cz+dz));
            }
            for(auto it=chunks.begin();it!=chunks.end();) {
                int xx=int32_t(it->first>>32),zz=int32_t(uint32_t(it->first));
                if(std::abs(xx-cx)>UNLOAD_RADIUS||std::abs(zz-cz)>UNLOAD_RADIUS) {
                    invalidate_mesh(*it->second);
                    it=chunks.erase(it);
                    dirty_neighbors(xx,zz);
                } else ++it;
            }
        }
        std::vector<Request> needed;
        for(int dz=-LOAD_RADIUS;dz<=LOAD_RADIUS;++dz)
            for(int dx=-LOAD_RADIUS;dx<=LOAD_RADIUS;++dx) {
                uint64_t key=pair_key(cx+dx,cz+dz);
                if(!chunks.contains(key) && !scheduled.contains(key))
                    needed.push_back({cx+dx,cz+dz});
            }
        std::sort(needed.begin(),needed.end(),[cx,cz](Request a,Request b) {
            int da=(a.cx-cx)*(a.cx-cx)+(a.cz-cz)*(a.cz-cz);
            int db=(b.cx-cx)*(b.cx-cx)+(b.cz-cz)*(b.cz-cz);
            return da<db;
        });
        {
            std::lock_guard lock(queue_mutex);
            // Cancel stale queued jobs when changing chunk location. One currently
            // executing job may still complete; drain_completed will discard it.
            if(moved) {
                for(auto request:pending) scheduled.erase(pair_key(request.cx,request.cz));
                pending.clear();
            }
            const auto ticket=stream_epoch.load(std::memory_order_relaxed);
            for(auto request:needed) {
                uint64_t key=pair_key(request.cx,request.cz);
                if(scheduled.insert(key).second)
                    pending.push_back({request.cx,request.cz,ticket});
            }
        }
        if(!worker.joinable()) start_worker();
        queue_cv.notify_all();
    }
    // Build a compact greedy surface-only mesh for one chunk, including adjacent
    // chunk faces. A not-yet-loaded neighbor is treated as air until it arrives.
    void rebuild_light(Chunk& ch) {
        ch.light.fill(0);
        // Queue size bounded: a voxel is revisited only when its light increases.
        // 4-bit light strength: direct sky=15, lamp=14, sideways attenuation=1.
        std::deque<int> queue;
        for(int z=0;z<SIDE;++z) for(int x=0;x<SIDE;++x) {
            bool sunlight=true;
            for(int y=HEIGHT-1;y>=0;--y) {
                const size_t i=index(x,y,z);
                uint8_t b=ch.blocks[i];
                if(b==LAMP) {
                    ch.light[i]=14;queue.push_back(int(i));
                    sunlight=false;
                } else if(opaque(b)) sunlight=false;
                else if(sunlight) {
                    ch.light[i]=15; queue.push_back(int(i));
                }
            }
        }
        while(!queue.empty()) {
            int i=queue.front();queue.pop_front();
            int x=i%SIDE,z=(i/SIDE)%SIDE,y=i/(SIDE*SIDE);
            int next=int(ch.light[size_t(i)])-1;
            if(next<=0) continue;
            constexpr int offsets[6][3]={{-1,0,0},{1,0,0},{0,-1,0},
                                         {0,1,0},{0,0,-1},{0,0,1}};
            for(auto &d:offsets) {
                int xx=x+d[0],yy=y+d[1],zz=z+d[2];
                if(xx<0||xx>=SIDE||yy<0||yy>=HEIGHT||zz<0||zz>=SIDE) continue;
                size_t ni=index(xx,yy,zz);
                if(opaque(ch.blocks[ni])) continue;
                int level=next-(ch.blocks[ni]==WATER?1:0);
                if(level>ch.light[ni]) {ch.light[ni]=uint8_t(level);queue.push_back(int(ni));}
            }
        }
    }
    // Bake the destination chunk with its 15-voxel world-space neighborhood.
    // Including neighbors in the flood fill eliminates one-chunk-only light seams
    // without ever requiring a whole-world light rebuild.
    void rebuild_light_halo(int cx,int cz,Chunk& dest) {
        constexpr int stride=LIGHT_SPAN;
        const size_t layer=size_t(stride)*stride,volume=layer*HEIGHT;
        light_block_halo.assign(volume,uint8_t(AIR));
        light_halo.assign(volume,0);
        light_queue.clear();
        light_queue.reserve(volume/4);
        // Height of the lowest directly sunlit cell for each X/Z column.
        // Keeping this tiny skyline replaces a six-neighbor scan over every
        // sunlit voxel in the 46x46x80 halo.
        std::vector<uint8_t> sun_floor(layer,0);
        for(int z=0;z<stride;++z) for(int x=0;x<stride;++x) {
            const int wx=cx*SIDE+x-LIGHT_HALO,wz=cz*SIDE+z-LIGHT_HALO;
            auto it=chunks.find(pair_key(floor_div(wx,SIDE),floor_div(wz,SIDE)));
            const Chunk* source=it==chunks.end()?nullptr:it->second.get();
            const int lx=positive_mod(wx,SIDE),lz=positive_mod(wz,SIDE);
            bool sky=true;
            for(int y=HEIGHT-1;y>=0;--y) {
                size_t i=size_t(y)*layer+size_t(z)*stride+size_t(x);
                uint8_t b=source?source->blocks[index(lx,y,lz)]:uint8_t(AIR);
                light_block_halo[i]=b;
                if(b==LAMP) {light_halo[i]=14;light_queue.push_back(int(i));}
                if(opaque(b) && sky) {
                    sun_floor[size_t(z)*stride+size_t(x)]=uint8_t(y+1);
                    sky=false;
                } else if(sky) light_halo[i]=15;
            }
        }
        // Direct sunlight can spread sideways only where an adjacent column
        // has a higher shadow line. Seed exactly those interface cells.
        // The vertical sun-shadow boundary itself is opaque and needs no seed.
        constexpr int neighbors[4][2]={{-1,0},{1,0},{0,-1},{0,1}};
        for(int z=0;z<stride;++z) for(int x=0;x<stride;++x) {
            const int bottom=int(sun_floor[size_t(z)*stride+size_t(x)]);
            if(bottom>=HEIGHT) continue;
            for(const auto& d:neighbors) {
                const int nx=x+d[0],nz=z+d[1];
                if(nx<0||nx>=stride||nz<0||nz>=stride) continue;
                const int other=int(sun_floor[size_t(nz)*stride+size_t(nx)]);
                for(int y=bottom;y<other;++y) {
                    const size_t ni=size_t(y)*layer+size_t(nz)*stride+size_t(nx);
                    if(opaque(light_block_halo[ni])) continue;
                    if(light_halo[ni]>=14-(light_block_halo[ni]==WATER?1:0)) continue;
                    light_queue.push_back(int(size_t(y)*layer+size_t(z)*stride+size_t(x)));
                }
            }
        }
        constexpr int delta[6][3]={{-1,0,0},{1,0,0},{0,-1,0},
                                    {0,1,0},{0,0,-1},{0,0,1}};
        for(size_t head=0;head<light_queue.size();++head) {
            const int i=light_queue[head];
            const int x=i%stride,z=(i/stride)%stride,y=int(size_t(i)/layer);
            const int next=int(light_halo[size_t(i)])-1;
            if(next<=0) continue;
            for(const auto& d:delta) {
                const int nx=x+d[0],ny=y+d[1],nz=z+d[2];
                if(nx<0||nx>=stride||ny<0||ny>=HEIGHT||nz<0||nz>=stride) continue;
                const size_t ni=size_t(ny)*layer+size_t(nz)*stride+size_t(nx);
                if(opaque(light_block_halo[ni])) continue;
                const int candidate=next-(light_block_halo[ni]==WATER?1:0);
                if(candidate>light_halo[ni]) {
                    light_halo[ni]=uint8_t(candidate);
                    light_queue.push_back(int(ni));
                }
            }
        }
        for(int y=0;y<HEIGHT;++y) for(int z=0;z<SIDE;++z) for(int x=0;x<SIDE;++x)
            dest.light[index(x,y,z)]=light_halo[size_t(y)*layer
                       +size_t(z+LIGHT_HALO)*stride+size_t(x+LIGHT_HALO)];
    }
    uint8_t corner_light(const Chunk& ch,int dir,int px,int py,int pz,
                         int w,int h,int uc,int vc) const {
        int p[3]={px,py,pz};
        int axis=dir/2,ua=axis==0?2:0,va=axis==1?2:1;
        if((dir&1)==0) --p[axis];
        if(uc) p[ua]+=w-1;
        if(vc) p[va]+=h-1;
        const int xx=std::clamp(p[0]+LIGHT_HALO,0,LIGHT_SPAN-1);
        const int yy=std::clamp(p[1],0,HEIGHT-1);
        const int zz=std::clamp(p[2]+LIGHT_HALO,0,LIGHT_SPAN-1);
        int strength=light_halo.empty() ? ch.light[index(std::clamp(p[0],0,SIDE-1),yy,
                                               std::clamp(p[2],0,SIDE-1))]
            :light_halo[(size_t(yy)*LIGHT_SPAN+size_t(zz))*LIGHT_SPAN+size_t(xx)];
        return uint8_t(std::clamp((strength+2)/5,0,3));
    }
    void build_mesh(int cx,int cz,Chunk& chunk) {
        rebuild_light_halo(cx,cz,chunk);
        chunk.mesh.clear();
        chunk.water_mesh.clear();
        // dir 0:-X 1:+X 2:-Y 3:+Y 4:-Z 5:+Z
        for(int dir=0;dir<6;++dir) {
            int axis=dir/2,sign=(dir&1)?1:-1;
            int dimension=axis==1?HEIGHT:SIDE;
            int u_size=SIDE,v_size=axis==1?SIDE:HEIGHT;
            // All three axes map to a 2D grid with u along x/z and v along y/z.
            std::vector<uint16_t> mask(size_t(u_size)*v_size);
            for(int plane=0;plane<=dimension;++plane) {
                for(int v=0;v<v_size;++v) for(int u=0;u<u_size;++u) {
                    int x=0,y=0,z=0;
                    if(axis==0) {x=plane;z=u;y=v;}
                    if(axis==1) {x=u;y=plane;z=v;}
                    if(axis==2) {x=u;z=plane;y=v;}
                    int bx=x,by=y,bz=z;
                    if(sign>0) {
                        if(axis==0) --bx;
                        if(axis==1) --by;
                        if(axis==2) --bz;
                    }
                    int nx=bx,ny=by,nz=bz;
                    if(axis==0) nx+=sign;
                    if(axis==1) ny+=sign;
                    if(axis==2) nz+=sign;
                    // Source block must belong to this chunk, not its neighbor.
                    bool source_inside=bx>=0&&bx<SIDE&&by>=0&&by<HEIGHT&&bz>=0&&bz<SIDE;
                    uint8_t block=source_inside?chunk.blocks[index(bx,by,bz)]:uint8_t(AIR);
                    uint8_t neighbor=AIR;
                    if(block!=AIR) {
                        if(nx>=0&&nx<SIDE&&ny>=0&&ny<HEIGHT&&nz>=0&&nz<SIDE)
                            neighbor=chunk.blocks[index(nx,ny,nz)];
                        else neighbor=at(cx*SIDE+nx,ny,cz*SIDE+nz);
                    }
                    // Opaque blocks border both air and water. Water contributes
                    // only a separate visible fluid surface against air.
                    bool visible=block==WATER ? neighbor==AIR
                                                : (opaque(block) && !opaque(neighbor));
                    uint16_t level=(block==WATER && source_inside)
                        ? chunk.fluid[index(bx,by,bz)] : 0;
                    if(block==WATER && level==0) level=LIQUID_SOURCE;
                    mask[size_t(v)*u_size+u]=visible
                        ? uint16_t(block | (level<<8)) : uint16_t(AIR);
                }
                for(int v=0;v<v_size;++v) for(int u=0;u<u_size;) {
                    uint16_t packed=mask[size_t(v)*u_size+u];
                    uint8_t mat=uint8_t(packed & 255);
                    if(mat==AIR) {++u;continue;}
                    int w=1;
                    while(u+w<u_size&&mask[size_t(v)*u_size+u+w]==packed) ++w;
                    int h=1;
                    bool extends=true;
                    // Height-sensitive water sides cannot be merged vertically.
                    while(v+h<v_size&&extends&&!(mat==WATER && axis!=1)) {
                        for(int k=0;k<w;++k)
                            if(mask[size_t(v+h)*u_size+u+k]!=packed) {extends=false;break;}
                        if(extends) ++h;
                    }
                    for(int dv=0;dv<h;++dv) for(int du=0;du<w;++du)
                        mask[size_t(v+dv)*u_size+u+du]=AIR;
                    int px=0,py=0,pz=0;
                    if(axis==0) {px=plane;py=v;pz=u;}
                    if(axis==1) {px=u;py=plane;pz=v;}
                    if(axis==2) {px=u;py=v;pz=plane;}
                    uint32_t word0=uint32_t(px)|(uint32_t(py)<<5)|(uint32_t(pz)<<12)
                          |(uint32_t(dir)<<17)|(uint32_t(mat)<<20)
                          |(uint32_t(packed>>8)<<24);
                    uint32_t ao=uint32_t(corner_ao(cx,cz,dir,px,py,pz,w,h,0,0))
                               |(uint32_t(corner_ao(cx,cz,dir,px,py,pz,w,h,1,0))<<2)
                               |(uint32_t(corner_ao(cx,cz,dir,px,py,pz,w,h,1,1))<<4)
                               |(uint32_t(corner_ao(cx,cz,dir,px,py,pz,w,h,0,1))<<6);
                    uint32_t lv=uint32_t(corner_light(chunk,dir,px,py,pz,w,h,0,0))
                               |(uint32_t(corner_light(chunk,dir,px,py,pz,w,h,1,0))<<2)
                               |(uint32_t(corner_light(chunk,dir,px,py,pz,w,h,1,1))<<4)
                               |(uint32_t(corner_light(chunk,dir,px,py,pz,w,h,0,1))<<6);
                    uint32_t word1=uint32_t(w)|(uint32_t(h)<<8)|(ao<<16)|(lv<<24);
                    auto& target=mat==WATER?chunk.water_mesh:chunk.mesh;
                    target.push_back(std::int32_t(word0));
                    target.push_back(std::int32_t(word1));
                    u+=w;
                }
            }
        }
        chunk.dirty=false;
        chunk.revision=++next_revision;
    }
    void rebuild_meshes(int budget) {
        // Prefer meshes close to the player; budget limits frame-time spikes.
        std::vector<std::pair<int,uint64_t>> dirty;
        for(auto& [key,ch]:chunks) if(ch->dirty) {
            int x=int32_t(key>>32),z=int32_t(uint32_t(key));
            int dx=x-center_x,dz=z-center_z;
            dirty.emplace_back(dx*dx+dz*dz,key);
        }
        if(dirty.empty()) return;
        int count=std::min(budget,int(dirty.size()));
        std::partial_sort(dirty.begin(),dirty.begin()+count,dirty.end());
        for(int i=0;i<count;++i) {
            uint64_t key=dirty[size_t(i)].second;
            build_mesh(int32_t(key>>32),int32_t(uint32_t(key)),*chunks.at(key));
        }
    }
    uint8_t at(int x,int y,int z) const {
        if(y<0) return STONE;
        if(y>=HEIGHT) return AIR;
        auto it=chunks.find(pair_key(floor_div(x,SIDE),floor_div(z,SIDE)));
        if(it==chunks.end()) return AIR;
        return it->second->blocks[index(positive_mod(x,SIDE),y,positive_mod(z,SIDE))];
    }
    void set(int x,int y,int z,uint8_t block) {
        if(y<1||y>=HEIGHT||block>WATER) return;
        Position p{x,z,uint8_t(y)};
        if(!edits.contains(p)) edit_index[pair_key(floor_div(x,SIDE),floor_div(z,SIDE))].push_back(p);
        edits[p]=block;
        auto it=chunks.find(pair_key(floor_div(x,SIDE),floor_div(z,SIDE)));
        if(it!=chunks.end()) {
            size_t i=index(positive_mod(x,SIDE),y,positive_mod(z,SIDE));
            it->second->blocks[i]=block;
            it->second->fluid[i]=block==WATER?LIQUID_SOURCE:0;
            it->second->snapshot.reset();
            dirty_after_block(x,z);
            wake_liquid(x,y,z);
        }
    }
    bool save(const char* name) const {
        std::string temp=std::string(name)+".tmp";
        std::ofstream file(temp,std::ios::binary|std::ios::trunc);
        if(!file) return false;
        file.write("SVXL0002",8);
        uint32_t count=uint32_t(edits.size());
        file.write(reinterpret_cast<const char*>(&seed),4);
        file.write(reinterpret_cast<const char*>(&count),4);
        for(const auto& [pos,block] : edits) {
            file.write(reinterpret_cast<const char*>(&pos.x),4);
            file.write(reinterpret_cast<const char*>(&pos.z),4);
            file.write(reinterpret_cast<const char*>(&pos.y),1);
            file.write(reinterpret_cast<const char*>(&block),1);
        }
        for(auto n:inventory) file.write(reinterpret_cast<const char*>(&n),4);
        file.close();
        if(!file) return false;
#ifdef _WIN32
        std::remove(name); // Windows rename does not replace an existing destination.
#endif
        if(std::rename(temp.c_str(),name)!=0) { std::remove(temp.c_str()); return false; }
        return true;
    }
    void load(const char* name) {
        std::ifstream file(name,std::ios::binary);
        if(!file) return;
        char magic[8]{}; uint32_t new_seed=0, count=0;
        file.read(magic,8);file.read(reinterpret_cast<char*>(&new_seed),4);
        file.read(reinterpret_cast<char*>(&count),4);
        bool old_format=std::string(magic,8)=="SVXL0001";
        if(!file||(!old_format && std::string(magic,8)!="SVXL0002")||count>2'000'000) return;
        std::unordered_map<Position,uint8_t,PositionHash> loaded;
        std::array<int32_t,MATERIAL_COUNT> loaded_items=STARTING_ITEMS;
        for(uint32_t i=0;i<count;++i) {
            Position p{};uint8_t b=0;
            file.read(reinterpret_cast<char*>(&p.x),4);
            file.read(reinterpret_cast<char*>(&p.z),4);
            file.read(reinterpret_cast<char*>(&p.y),1);
            file.read(reinterpret_cast<char*>(&b),1);
            if(!file||p.y>=HEIGHT||b>(old_format?CRYSTAL:WATER)) return;
            loaded[p]=b;
        }
        if(!old_format) for(auto& n:loaded_items) {
            file.read(reinterpret_cast<char*>(&n),4);
            if(!file||n<0||n>9999) return;
        }
        seed=new_seed; edits=std::move(loaded);inventory=loaded_items;chunks.clear();
        edit_index.clear();
        for(const auto& [p,b]:edits)
            edit_index[pair_key(floor_div(p.x,SIDE),floor_div(p.z,SIDE))].push_back(p);
        center_x=center_z=std::numeric_limits<int>::max();
    }
};

struct RayHit { bool found=false; int x=0,y=0,z=0,prev_x=0,prev_y=0,prev_z=0,axis=1; float dist=MAX_VIEW; uint8_t block=AIR; };
RayHit trace(const World& w,V3 eye,V3 dir,float limit,bool skip_water=false) {
    int x=int(std::floor(eye.x)),y=int(std::floor(eye.y)),z=int(std::floor(eye.z));
    const int sx=dir.x>=0?1:-1,sy=dir.y>=0?1:-1,sz=dir.z>=0?1:-1;
    const float inf=std::numeric_limits<float>::infinity();
    float tx=std::abs(dir.x)>1e-8f ? ((sx>0?float(x+1):float(x))-eye.x)/dir.x : inf;
    float ty=std::abs(dir.y)>1e-8f ? ((sy>0?float(y+1):float(y))-eye.y)/dir.y : inf;
    float tz=std::abs(dir.z)>1e-8f ? ((sz>0?float(z+1):float(z))-eye.z)/dir.z : inf;
    const float dx=std::abs(dir.x)>1e-8f?std::abs(1.0f/dir.x):inf;
    const float dy=std::abs(dir.y)>1e-8f?std::abs(1.0f/dir.y):inf;
    const float dz=std::abs(dir.z)>1e-8f?std::abs(1.0f/dir.z):inf;
    int px=x,py=y,pz=z,axis=1;float t=0;
    // Cache chunk bounds per ray: most DDA steps remain in one chunk.
    // This avoids two hash-table lookups and coordinate divisions per voxel.
    int low_x=std::numeric_limits<int>::max(), low_z=std::numeric_limits<int>::max();
    const Chunk* chunk=nullptr;
    while(t<=limit) {
        uint8_t block=AIR;
        if(y<0) block=STONE;
        else if(y<HEIGHT) {
            if(x<low_x || x>=low_x+SIDE || z<low_z || z>=low_z+SIDE) {
                low_x=floor_div(x,SIDE)*SIDE;
                low_z=floor_div(z,SIDE)*SIDE;
                auto it=w.chunks.find(pair_key(low_x/SIDE,low_z/SIDE));
                chunk=it==w.chunks.end()?nullptr:it->second.get();
            }
            if(chunk) block=chunk->blocks[World::index(x-low_x,y,z-low_z)];
        }
        if(block!=AIR && !(skip_water && block==WATER))
            return {true,x,y,z,px,py,pz,axis,t,block};
        px=x;py=y;pz=z;
        if(tx<ty && tx<tz) {x+=sx;t=tx;tx+=dx;axis=0;}
        else if(ty<tz) {y+=sy;t=ty;ty+=dy;axis=1;}
        else {z+=sz;t=tz;tz+=dz;axis=2;}
    }
    return {};
}

struct Engine {
    World world;
    V3 feet{0,50,0};
    V3 velocity{0,0,0};
    float yaw=0,pitch=0;
    bool grounded=false;
    int selected=GRASS;
    bool collide(V3 p) const {
        int x0=int(std::floor(p.x-PLAYER_RADIUS)),x1=int(std::floor(p.x+PLAYER_RADIUS));
        int y0=int(std::floor(p.y+0.01f)),y1=int(std::floor(p.y+PLAYER_HEIGHT-0.01f));
        int z0=int(std::floor(p.z-PLAYER_RADIUS)),z1=int(std::floor(p.z+PLAYER_RADIUS));
        for(int y=y0;y<=y1;++y) for(int z=z0;z<=z1;++z)
            for(int x=x0;x<=x1;++x) if(!walkable(world.at(x,y,z))) return true;
        return false;
    }
    V3 eye() const { return {feet.x,feet.y+EYE,feet.z}; }
    V3 forward() const {return {std::sin(yaw)*std::cos(pitch),std::sin(pitch),std::cos(yaw)*std::cos(pitch)};}
    void initialize(uint32_t seed) {
        world.reset(seed);world.load("stratawild.svxl");
        feet={0,float(world.terrain_height(0,0))+1.05f,0};
        velocity={0,0,0};world.stream(feet.x,feet.z);
        // A single synchronous bootstrap mesh avoids a blank first frame.
        // All subsequent mesh generation happens off the render/game thread.
        world.rebuild_meshes(1);
        while(collide(feet)&&feet.y<HEIGHT+3) feet.y+=1;
        yaw=0;pitch=0;grounded=false;selected=GRASS;
    }
    void move_axis(int axis,float amount) {
        if(amount==0) return;
        V3 next=feet;
        if(axis==0) next.x+=amount;
        if(axis==1) next.y+=amount;
        if(axis==2) next.z+=amount;
        if(!collide(next)) {feet=next;return;}
        if(axis==0) velocity.x=0;
        if(axis==2) velocity.z=0;
        if(axis==1) {
            if(amount<0) grounded=true;
            velocity.y=0;
        }
    }
    void tick(int keys,float a,float b,float dt) {
        if(!std::isfinite(a)||!std::isfinite(b)||!std::isfinite(dt)) return;
        dt=std::clamp(dt,0.0f,0.05f);
        yaw=a;pitch=std::clamp(b,-1.52f,1.52f);
        float forward_input=float(bool(keys&1))-float(bool(keys&2));
        float side_input=float(bool(keys&8))-float(bool(keys&4));
        float length=std::sqrt(forward_input*forward_input+side_input*side_input);
        if(length>0) {forward_input/=length;side_input/=length;}
        bool swimming=world.at(int(std::floor(feet.x)),int(std::floor(feet.y+0.8f)),
                               int(std::floor(feet.z)))==WATER;
        float speed=((keys&32)?8.5f:5.2f)*(swimming?0.63f:1.0f);
        float dx=(std::sin(yaw)*forward_input+std::cos(yaw)*side_input)*speed;
        float dz=(std::cos(yaw)*forward_input-std::sin(yaw)*side_input)*speed;
        float damping=std::min(1.0f,dt*14.0f);
        velocity.x+=(dx-velocity.x)*damping;
        velocity.z+=(dz-velocity.z)*damping;
        if((keys&16)&&(grounded||swimming)) {velocity.y=swimming?3.1f:7.4f;grounded=false;}
        velocity.y=std::max(swimming?-3.5f:-22.0f,
                            velocity.y-(swimming?4.5f:21.0f)*dt);
        if(swimming) velocity.y*=std::max(0.0f,1.0f-dt*1.5f);
        grounded=false;
        // Substeps prevent skipping through thin walls during occasional long frames.
        int n=std::max(1,int(std::ceil(dt/0.015f)));
        float step=dt/n;
        for(int i=0;i<n;++i) {
            move_axis(0,velocity.x*step);
            move_axis(2,velocity.z*step);
            move_axis(1,velocity.y*step);
        }
        world.view_x=std::sin(yaw);
        world.view_z=std::cos(yaw);
        world.stream(feet.x,feet.z);
        world.tick_water(1200); // Fixed work budget; high-flow scenes cannot stall a frame.
        world.update_mesh_async(2);
    }
    int action(int kind,int material) {
        if(material>=1&&material<=LAMP) selected=material;
        auto hit=trace(world,eye(),forward(),7.0f,true);
        if(!hit.found) return 0;
        if(kind==1) {
            if(hit.block==AIR||hit.block==WATER) return 0;
            if(world.inventory[hit.block]<9999) ++world.inventory[hit.block];
            world.set(hit.x,hit.y,hit.z,AIR);
            return 1;
        }
        if(kind==2 && selected>=1 && selected<=LAMP && world.inventory[selected]>0 &&
                hit.prev_y>=1 && hit.prev_y<HEIGHT &&
                walkable(world.at(hit.prev_x,hit.prev_y,hit.prev_z))) {
            V3 p={float(hit.prev_x)+0.5f,float(hit.prev_y)+0.5f,float(hit.prev_z)+0.5f};
            bool overlap=std::abs(p.x-feet.x)<0.5f+PLAYER_RADIUS &&
                std::abs(p.z-feet.z)<0.5f+PLAYER_RADIUS &&
                float(hit.prev_y)<feet.y+PLAYER_HEIGHT && float(hit.prev_y+1)>feet.y;
            if(!overlap) {
                world.set(hit.prev_x,hit.prev_y,hit.prev_z,uint8_t(selected));
                --world.inventory[selected];
                return 1;
            }
        }
        return 0;
    }
};
Engine engine;

int blend(int a,int b,float t) {return std::clamp(int(a*(1-t)+b*t),0,255);}
int rgb(int r,int g,int b) {return int(0xff000000u | (uint32_t(r)<<16) | (uint32_t(g)<<8) | uint32_t(b));}
int sky_color(V3 d) {
    float t=std::clamp((d.y+0.15f)*0.9f,0.0f,1.0f);
    int r=blend(177,67,t),g=blend(208,147,t),b=blend(222,203,t);
    float glow=std::max(0.0f,dot(d,normalize({0.45f,0.68f,0.31f})));
    float sun=glow;
    for(int i=0;i<7;++i) sun*=sun; // 128th power without libm pow()
    return rgb(blend(r,255,sun),blend(g,239,sun),blend(b,191,sun));
}
int block_color(const RayHit& h,float fog,V3 direction) {
    constexpr int colors[][3]={
        {0,0,0},{97,159,105},{123,93,74},{111,123,135},
        {205,180,126},{141,105,76},{87,151,111},{137,150,203},
        {255,214,120},{52,131,183}
    };
    int b=int(h.block);
    float light=h.axis==1?(direction.y<0?1.0f:0.53f):(h.axis==0?0.74f:0.84f);
    uint32_t hash=cell_hash(h.x*31+h.y,h.z*31+h.y,0x1763abU);
    float grain=0.91f+float(hash&31U)*(0.15f/31.0f);
    int r=std::min(255,int(colors[b][0]*light*grain));
    int g=std::min(255,int(colors[b][1]*light*grain));
    int bl=std::min(255,int(colors[b][2]*light*grain));
    // Distance fog hides the world-cache edge and reduces high-frequency visual noise.
    int sr=165,sg=194,sb=210;
    return rgb(blend(r,sr,fog),blend(g,sg,fog),blend(bl,sb,fog));
}
}

EXPORT void voxel_init(std::int64_t seed) noexcept {
    try {engine.initialize(uint32_t(seed));} catch(...) {}
}
EXPORT void voxel_tick(std::int32_t keys,float yaw,float pitch,float dt) noexcept {
    try {engine.tick(keys,yaw,pitch,dt);} catch(...) {}
}
EXPORT void voxel_render(std::int32_t* out,std::int32_t length,std::int32_t width,std::int32_t height) noexcept {
    if(!out||width<=0||height<=0||width>1920||height>1080||int64_t(width)*height>length) return;
    try {
        V3 f=engine.forward();
        V3 r={std::cos(engine.yaw),0,-std::sin(engine.yaw)};
        V3 u={-std::sin(engine.yaw)*std::sin(engine.pitch),std::cos(engine.pitch),-std::cos(engine.yaw)*std::sin(engine.pitch)};
        V3 eye=engine.eye();
        float aspect=float(width)/height;
        constexpr float half_tan=0.700208f; // tan(70 degrees / 2)
        float sx=2*aspect*half_tan/width,sy=2*half_tan/height;
        V3 first=f+r*(-aspect*half_tan+sx*0.5f)+u*(half_tan-sy*0.5f);
        auto render_rows=[&](int y0,int y1) {
            for(int y=y0;y<y1;++y) {
                V3 row=first+u*(-sy*y);
                for(int x=0;x<width;++x) {
                    V3 dir=normalize(row+r*(sx*x));
                    RayHit hit=trace(engine.world,eye,dir,MAX_VIEW);
                    if(hit.found) {
                        float fog=std::clamp((hit.dist-24.0f)/(MAX_VIEW-24.0f),0.0f,1.0f);
                        out[size_t(y)*width+x]=block_color(hit,fog,dir);
                    } else out[size_t(y)*width+x]=sky_color(dir);
                }
            }
        };
        // Split independent scanline bands across cores. World data remains immutable
        // for the duration of this call; no locks or per-pixel allocations.
        const unsigned cpus=std::max(1u,std::thread::hardware_concurrency());
        const int workers=std::min({4,int(cpus),std::max(1,height/72)});
        std::vector<std::jthread> pool;
        pool.reserve(size_t(workers-1));
        for(int i=0;i<workers-1;++i)
            pool.emplace_back(render_rows,i*height/workers,(i+1)*height/workers);
        render_rows((workers-1)*height/workers,height);
        // jthread joins when pool goes out of scope, before returning to Java.
    } catch(...) {}
}

EXPORT std::int32_t voxel_interact(std::int32_t kind,std::int32_t material) noexcept {
    try {return engine.action(kind,material);} catch(...) {return 0;}
}
EXPORT std::int32_t voxel_save() noexcept {
    try {return engine.world.save("stratawild.svxl")?1:0;} catch(...) {return 0;}
}
EXPORT std::int32_t voxel_loaded_chunks() noexcept {return std::int32_t(engine.world.chunks.size());}
// Batch diagnostics for F3: no per-chunk calls or allocations across FFM.
EXPORT void voxel_stream_stats(std::int32_t* out,std::int32_t length) noexcept {
    if(!out||length<10) return;
    try {engine.world.stream_stats(out);} catch(...) {}
}

// The GPU renderer transfers complete compact meshes, never individual blocks.
// List format: [cx,cz,revision,quadCount]. Revision zero exposes an
// immediately available coarse impostor while its detailed mesh is queued;
// the renderer must not request detailed quads until a positive revision.
EXPORT std::int32_t voxel_mesh_list(std::int32_t* output,std::int32_t length) noexcept {
    if(!output||length<0) return 0;
    try {
        int n=0;
        for(auto& [key,ch]:engine.world.chunks) {
            if((n+1)*4>length) continue;
            output[n*4]=int32_t(key>>32);
            output[n*4+1]=int32_t(uint32_t(key));
            output[n*4+2]=ch->revision;
            output[n*4+3]=int(ch->mesh.size()/2);
            ++n;
        }
        return n;
    } catch(...) {return 0;}
}
EXPORT std::int32_t voxel_mesh_copy(std::int32_t cx,std::int32_t cz,
                                      std::int32_t* output,std::int32_t length) noexcept {
    if(!output||length<0) return -1;
    try {
        auto it=engine.world.chunks.find(pair_key(cx,cz));
        if(it==engine.world.chunks.end()||it->second->revision==0) return -1;
        auto& mesh=it->second->mesh;
        if(mesh.size()>size_t(length)) return -int(mesh.size());
        if(!mesh.empty()) std::memcpy(output,mesh.data(),mesh.size()*sizeof(int32_t));
        return int(mesh.size());
    } catch(...) {return -1;}
}
// Coarse distance mesh is produced with the chunk, without additional worker
// synchronization and without storing an entire second voxel volume.
EXPORT std::int32_t voxel_mesh_copy_far(std::int32_t cx,std::int32_t cz,
                                         std::int32_t* output,std::int32_t length) noexcept {
    if(!output||length<0) return -1;
    try {
        auto it=engine.world.chunks.find(pair_key(cx,cz));
        if(it==engine.world.chunks.end()) return -1;
        const auto& mesh=it->second->far_mesh;
        if(mesh.size()>size_t(length)) return -int(mesh.size());
        if(!mesh.empty()) std::memcpy(output,mesh.data(),mesh.size()*sizeof(int32_t));
        return int(mesh.size());
    } catch(...) {return -1;}
}
EXPORT void voxel_camera(float* out,std::int32_t length) noexcept {
    if(!out||length<5) return;
    auto eye=engine.eye();
    out[0]=eye.x;out[1]=eye.y;out[2]=eye.z;
    out[3]=engine.yaw;out[4]=engine.pitch;
}

// All counts are retained natively and transferred in a single FFM call.
EXPORT void voxel_inventory(std::int32_t* out,std::int32_t length) noexcept {
    if(!out || length<MATERIAL_COUNT) return;
    for(int i=0;i<MATERIAL_COUNT;++i) out[i]=engine.world.inventory[size_t(i)];
}
EXPORT std::int32_t voxel_mesh_water_words(std::int32_t cx,std::int32_t cz) noexcept {
    auto it=engine.world.chunks.find(pair_key(cx,cz));
    if(it==engine.world.chunks.end()||it->second->revision==0) return -1;
    return std::int32_t(it->second->water_mesh.size());
}
EXPORT std::int32_t voxel_mesh_copy_water(std::int32_t cx,std::int32_t cz,
                                          std::int32_t* output,std::int32_t length) noexcept {
    if(!output||length<0) return -1;
    auto it=engine.world.chunks.find(pair_key(cx,cz));
    if(it==engine.world.chunks.end()||it->second->revision==0) return -1;
    auto &mesh=it->second->water_mesh;
    if(mesh.size()>size_t(length)) return -std::int32_t(mesh.size());
    if(!mesh.empty()) std::memcpy(output,mesh.data(),mesh.size()*sizeof(int32_t));
    return std::int32_t(mesh.size());
}
