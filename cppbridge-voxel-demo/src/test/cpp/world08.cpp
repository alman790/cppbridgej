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
static bool until(auto predicate,int timeout_ms=7000) {
    const auto deadline=std::chrono::steady_clock::now()+std::chrono::milliseconds(timeout_ms);
    while(std::chrono::steady_clock::now()<deadline) {
        if(predicate()) return true;
        std::this_thread::sleep_for(1ms);
    }
    return predicate();
}
int main() {
    // Regression: a terrain job canceled by a new stream center must not
    // leave a permanent hole when the player subsequently stands still.
    {
        World w;
        w.center_x=w.center_z=0;
        const auto key=pair_key(2,0);
        w.scheduled.insert(key);
        {
            std::lock_guard lock(w.queue_mutex);
            w.completed.push_back({2,0,nullptr});
        }
        w.stream(0.0f,0.0f);
        assert(w.scheduled.contains(key)); // Same-center retry must happen.
        int32_t stats[10]{};
        w.stream_stats(stats);
        assert(stats[0]==0 && stats[1]>=1 && stats[2]>=0);
        // Workers may still be generating during this check, and stop_worker
        // joins them before World frees its chunk maps.
    }
    // Finished terrain must be evaluated against the new center when crossing
    // a chunk boundary (otherwise the same generated result is thrown away).
    {
        World w;
        w.center_x=w.center_z=0;
        auto result=std::make_unique<Chunk>();
        result->blocks.fill(STONE);
        Chunk* expected=result.get();
        const auto key=pair_key(3,0);
        w.scheduled.insert(key);
        {
            std::lock_guard lock(w.queue_mutex);
            w.completed.push_back({3,0,std::move(result)});
        }
        w.stream(3.0f*SIDE,0.0f);
        assert(w.chunks.contains(key));
        assert(w.chunks.at(key).get()==expected);
    }
    // Cancellation ticket is observed *before* expensive terrain generation.
    {
        World w;
        w.stream_epoch.store(10);
        {
            std::lock_guard lock(w.queue_mutex);
            w.pending.push_back({10000,10000,9});
        }
        w.start_worker();
        assert(until([&] {
            std::lock_guard lock(w.queue_mutex);
            return !w.completed.empty();
        }));
        w.stop_worker();
        assert(w.completed.front().chunk==nullptr);
        assert(w.terrain_canceled.load()==1);
    }
    // Removing a stale *queued* mesh returns its memory immediately, without
    // waiting for the worker to finish other work.
    {
        World w;
        w.center_x=w.center_z=0;
        solid(w,0,0);
        const auto key=pair_key(0,0);
        Chunk& target=*w.chunks.at(key);
        World::MeshRequest queued;
        queued.cx=queued.cz=0;
        queued.generation=target.generation;
        queued.epoch=target.mesh_epoch;
        queued.ticket=target.mesh_ticket;
        w.mesh_pending.push_back(std::move(queued));
        w.mesh_scheduled.insert(key);
        World::mark_dirty(target);
        w.update_mesh_async(0);
        assert(w.mesh_pending.empty());
        assert(w.mesh_scheduled.empty());
        assert(w.mesh_canceled.load()==1);
        // An old token may outlive the removed chunk. It must never match the
        // token of a different chunk occupying the same coordinates.
        auto token=target.mesh_ticket;
        World::invalidate_mesh(target);
        w.chunks.erase(key);
        solid(w,0,0);
        assert(token->load()==0);
        assert(w.chunks.at(key)->mesh_ticket!=token);
        assert(w.chunks.at(key)->mesh_ticket->load()==w.chunks.at(key)->mesh_epoch);
        // Consecutive edits invalidate queued/active work without unbounding
        // the number of in-flight snapshot allocations.
        for(int step=0;step<12;++step) {
            w.set(8,20,8,(step&1)?STONE:AIR);
            w.update_mesh_async(2);
            assert(w.mesh_scheduled.size()<=World::MESH_IN_FLIGHT_LIMIT);
        }
        assert(until([&] {
            w.update_mesh_async(2);
            const auto& ch=*w.chunks.at(key);
            return ch.revision>0 && !ch.dirty;
        }));
        int32_t stats[10]{};
        w.stream_stats(stats);
        assert(stats[0]==1 && stats[5]<=World::MESH_IN_FLIGHT_LIMIT);
        assert(stats[6]>0 && stats[8]>=1);
    }
    // Native public API rejects undersized diagnostics buffers without writes.
    int32_t out[11]{};
    for(auto& n:out) n=1234;
    voxel_stream_stats(out,9);
    for(auto n:out) assert(n==1234);
    voxel_stream_stats(out,10);
    assert(out[0]>=0 && out[10]==1234);
    std::puts("Stratawild 0.8: retry after cancellation, worker tickets, queue limits, diagnostics: OK");
}
