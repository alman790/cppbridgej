#include "../../main/cpp/stratawild.cpp"
#include <cassert>
#include <cstdio>
#include <filesystem>
#include <fstream>
#include <vector>

int main() {
    World w;
    w.seed=0x20C0FFEEu;
    // Deterministic water, separate from ordinary terrain/opaque geometry.
    int water_count=0;
    for(int cz=-3;cz<=3;++cz) for(int cx=-3;cx<=3;++cx) {
        auto first=w.generate(cx,cz);
        auto second=w.generate(cx,cz);
        assert(first->blocks==second->blocks);
        for(uint8_t b:first->blocks) water_count+=b==WATER;
    }
    assert(water_count>0);
    assert(!opaque(WATER) && walkable(WATER) && !walkable(STONE));

    // Water faces are never sent through the opaque draw pass.
    auto ch=std::make_unique<Chunk>();
    ch->blocks[World::index(3,12,3)]=WATER;
    ch->blocks[World::index(4,12,3)]=STONE;
    w.integrate(0,0,std::move(ch));
    auto& c=*w.chunks.at(pair_key(0,0));
    w.build_mesh(0,0,c);
    assert(c.water_mesh.size()==10); // Five exposed water sides, one against stone.
    assert(c.mesh.size()==12);       // Opaque stone includes the face against water.
    for(size_t i=0;i<c.water_mesh.size();i+=2)
        assert((uint32_t(c.water_mesh[i])>>20 & 15u)==WATER);
    for(size_t i=0;i<c.mesh.size();i+=2)
        assert((uint32_t(c.mesh[i])>>20 & 15u)==STONE);

    // Sunlight & emitted light propagate through air but not rock.
    Chunk l;
    l.blocks[World::index(5,8,5)]=STONE;
    l.blocks[World::index(5,9,5)]=STONE;
    w.rebuild_light(l);
    assert(l.light[World::index(5,12,5)]==15);
    assert(l.light[World::index(5,8,5)]==0);
    l.blocks.fill(STONE);
    for(int x=4;x<=7;++x) l.blocks[World::index(x,11,5)]=AIR;
    l.blocks[World::index(4,11,5)]=LAMP;
    w.rebuild_light(l);
    assert(l.light[World::index(5,11,5)]>=13);
    assert(l.light[World::index(7,11,5)]>=11);
    assert(l.light[World::index(5,12,5)]==0); // Rock blocks propagation.

    // A 4-bit material tag is stored in unused word0 bits, 8 bytes per face.
    w.set(5,11,5,LAMP);
    w.build_mesh(0,0,c);
    bool found_lamp=false,found_lighting=false;
    for(size_t i=0;i<c.mesh.size();i+=2) {
        uint32_t a=uint32_t(c.mesh[i]),b=uint32_t(c.mesh[i+1]);
        found_lamp|=((a>>20)&15u)==LAMP;
        found_lighting|=(b>>24)!=0;
    }
    assert(found_lamp && found_lighting);

    // Stock is consumed only by successful placement; mining replenishes it.
    Engine play;
    play.world.reset(909);
    play.world.integrate(0,0,std::make_unique<Chunk>());
    play.feet={4.5f,10.f,4.f};
    play.world.set(4,11,7,STONE);
    play.world.inventory[STONE]=0;
    assert(play.action(2,STONE)==0);
    assert(play.world.at(4,11,6)==AIR);
    play.world.inventory[STONE]=1;
    assert(play.action(2,STONE)==1);
    assert(play.world.at(4,11,6)==STONE);
    assert(play.world.inventory[STONE]==0);
    assert(play.action(1,STONE)==1);
    assert(play.world.at(4,11,6)==AIR);
    assert(play.world.inventory[STONE]==1);

    // Native water-mesh exports preserve the zero-copy bulk-buffer contract.
    engine.world.reset(777);
    auto liquid=std::make_unique<Chunk>();
    liquid->blocks[World::index(3,9,3)]=WATER;
    engine.world.integrate(0,0,std::move(liquid));
    engine.world.build_mesh(0,0,*engine.world.chunks.at(pair_key(0,0)));
    assert(voxel_mesh_water_words(0,0)==12);
    int32_t buffer[12]{};
    assert(voxel_mesh_copy_water(0,0,buffer,12)==12);
    assert(voxel_mesh_copy_water(0,0,buffer,11)==-12);
    int32_t counts[10]{};
    voxel_inventory(counts,10);
    assert(counts[STONE]==STARTING_ITEMS[STONE]);
    assert(voxel_mesh_water_words(19,19)==-1);

    // Save data is transactional: failed/partial input must not overwrite state.
    w.inventory[STONE]=7;
    w.inventory[LAMP]=11;
    w.set(9,15,9,WATER);
    assert(w.save("world04-test.svxl"));
    World read;
    read.reset(123);
    read.load("world04-test.svxl");
    assert(read.seed==w.seed);
    assert(read.inventory[STONE]==7 && read.inventory[LAMP]==11);
    assert(read.edits.contains(Position{9,9,15}));
    assert(read.edits.at(Position{9,9,15})==WATER);
    {
        std::ofstream short_file("world04-truncated.svxl",std::ios::binary);
        short_file.write("SVXL0002",8);
    }
    read.load("world04-truncated.svxl");
    assert(read.inventory[STONE]==7);
    assert(read.edits.at(Position{9,9,15})==WATER);

    // Old SVXL0001 saves are accepted; inventory starts with fair starter stock.
    {
        std::ofstream old("world04-old.svxl",std::ios::binary);
        uint32_t oldSeed=777,entries=1;
        int32_t x=-16,z=18;
        uint8_t y=18,material=CRYSTAL;
        old.write("SVXL0001",8);
        old.write(reinterpret_cast<const char*>(&oldSeed),4);
        old.write(reinterpret_cast<const char*>(&entries),4);
        old.write(reinterpret_cast<const char*>(&x),4);
        old.write(reinterpret_cast<const char*>(&z),4);
        old.write(reinterpret_cast<const char*>(&y),1);
        old.write(reinterpret_cast<const char*>(&material),1);
    }
    read.load("world04-old.svxl");
    assert(read.seed==777);
    assert(read.edits.at(Position{-16,18,18})==CRYSTAL);
    assert(read.inventory==STARTING_ITEMS);
    std::filesystem::remove("world04-test.svxl");
    std::filesystem::remove("world04-truncated.svxl");
    std::filesystem::remove("world04-old.svxl");
    std::printf("Stratawild 0.4: liquid=%d, sunlight, lamp lighting, packed ABI, saves: OK\n",water_count);
}
