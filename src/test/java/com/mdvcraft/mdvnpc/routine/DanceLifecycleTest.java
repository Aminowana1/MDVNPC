package com.mdvcraft.mdvnpc.routine;

import com.mdvcraft.mdvnpc.MdvNpcPlugin;
import com.mdvcraft.mdvnpc.config.*;
import com.mdvcraft.mdvnpc.runtime.*;
import com.mdvcraft.mdvnpc.shop.ShopService;
import com.mdvcraft.mdvnpc.trait.Trait;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockbukkit.mockbukkit.*;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DanceLifecycleTest {
    @TempDir Path folder;
    ServerMock server;World world;RoutineService service;RoutineVisuals visuals;RoutineVisuals.Pose pose;
    DanceController dancers;ActiveNpc npc;NpcManager manager;RoutineGoal.Point point;
    boolean mounted;
    @BeforeEach void setup() throws Exception {
        server=MockBukkit.mock();world=mock(World.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());when(world.getName()).thenReturn("world");
        when(world.getMinHeight()).thenReturn(-64);when(world.getMaxHeight()).thenReturn(320);
        when(world.isChunkLoaded(anyInt(),anyInt())).thenReturn(true);when(world.getFullTime()).thenReturn(1000L);
        Player observer=mock(Player.class);when(observer.getUniqueId()).thenReturn(UUID.randomUUID());
        when(observer.getWorld()).thenReturn(world);when(observer.isOnline()).thenReturn(true);when(observer.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(observer.getLocation()).thenAnswer(i->new Location(world,.5,64,3.5));
        when(observer.getEyeLocation()).thenAnswer(i->new Location(world,.5,65.6,3.5));
        when(world.getNearbyPlayers(any(Location.class),anyDouble(),any())).thenReturn(List.of(observer));
        when(world.getBlockAt(anyInt(),anyInt(),anyInt())).thenAnswer(call -> {
            int x=call.getArgument(0),y=call.getArgument(1),z=call.getArgument(2);Block b=mock(Block.class);
            when(b.getType()).thenReturn(y==63?Material.STONE:Material.AIR);when(b.isPassable()).thenReturn(y!=63);
            when(b.getBoundingBox()).thenReturn(y==63?new org.bukkit.util.BoundingBox(x,y,z,x+1,y+1,z+1):new org.bukkit.util.BoundingBox(x,y,z,x,y,z));
            if(x==0 && y==64 && z==0)when(b.getBlockData()).thenReturn(mock(Stairs.class));return b;
        });
        var plugin=mock(MdvNpcPlugin.class);when(plugin.getName()).thenReturn("dance-test");when(plugin.isEnabled()).thenReturn(true);
        when(plugin.getServer()).thenReturn(server);when(plugin.getDataFolder()).thenReturn(folder.toFile());when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        when(plugin.settings()).thenReturn(Settings.parse(new YamlConfiguration()));when(plugin.shops()).thenReturn(mock(ShopService.class));
        var yaml=new YamlConfiguration();yaml.set("npcs.guest.location.world","world");yaml.set("npcs.guest.trait.type","fiestero");
        var def=NpcParser.parse(yaml).get("guest");
        Villager entity=mock(Villager.class);when(entity.getUniqueId()).thenReturn(UUID.randomUUID());when(entity.isValid()).thenReturn(true);when(entity.getWorld()).thenReturn(world);
        when(entity.isInsideVehicle()).thenAnswer(i->mounted);when(entity.getLocation()).thenAnswer(i->new Location(world,.5,64,1.5));
        when(entity.getEyeLocation()).thenAnswer(i->new Location(world,.5,65.6,1.5));
        npc=new ActiveNpc(def,new Location(world,.5,64,1.5),entity,null);
        manager=mock(NpcManager.class);when(plugin.manager()).thenReturn(manager);when(manager.activeNpcs()).thenReturn(List.of(npc));
        visuals=mock(RoutineVisuals.class);pose=new RoutineVisuals.Pose();pose.npc=npc;pose.seat=mock(ArmorStand.class);when(pose.seat.isValid()).thenReturn(true);
        when(entity.getVehicle()).thenAnswer(i->mounted?pose.seat:null);when(visuals.restoreSeat(any(),anyBoolean())).thenReturn(true);
        when(visuals.enter(any(),any(),any(),any(),anyLong())).thenAnswer(i->{mounted=true;return pose;});
        doAnswer(i->{mounted=false;return null;}).when(visuals).leave(any(),anyBoolean());
        service=new RoutineService(plugin,visuals);when(plugin.routines()).thenReturn(service);
        point=new RoutineGoal.Point(world.getUID(),0,64,0,0);
        service.repository().put("guest",new RoutineGoal(1,RoutineGoal.Type.SIT,RoutineGoal.WalkMode.CYCLE,420,1080,2.4,20,List.of(point)));
        service.start();dancers=mock(DanceController.class);when(dancers.start(eq(npc),any(),anyLong())).thenReturn(true);
        when(dancers.tick(eq(npc),anyLong(),anyInt(),anyDouble())).thenReturn(DanceController.Result.DANCING);
        var field=RoutineService.class.getDeclaredField("dancers");field.setAccessible(true);field.set(service,dancers);
    }
    @AfterEach void cleanup(){try{if(service!=null)service.close();}finally{MockBukkit.unmock();}}
    @Test void dancingReleasesVisualSeatButRetainsReservationAndReturnsToSit() {
        server.getScheduler().performTicks(620);
        assertTrue(service.status("guest").contains("bailando"));assertTrue(service.claimed(point));
        verify(visuals).leave(pose,true);
        when(dancers.tick(eq(npc),anyLong(),anyInt(),anyDouble())).thenReturn(DanceController.Result.FINISHED);
        server.getScheduler().performTicks(4);verify(dancers).cancel("guest");
        verify(visuals,times(2)).enter(eq(npc),any(),eq(point),any(),anyLong());assertTrue(service.claimed(point));
    }
    @Test void scheduleBoundaryStopsDancingAndFreesSeat() {
        server.getScheduler().performTicks(620);
        when(world.getFullTime()).thenReturn(12000L);server.getScheduler().performTicks(2);
        verify(dancers).cancel("guest");assertFalse(service.claimed(point));assertFalse(service.status("guest").contains("bailando"));
    }
    @Test void noObserversCancelsTemporaryDance() {
        server.getScheduler().performTicks(620);
        when(world.getNearbyPlayers(any(Location.class),anyDouble(),any())).thenReturn(List.of());server.getScheduler().performTicks(22);
        verify(dancers).cancel("guest");assertTrue(service.status("guest").contains("suspendido"));
    }
    @ParameterizedTest
    @EnumSource(value=Trait.class,names="PARTYGOER",mode=EnumSource.Mode.EXCLUDE)
    void seatedNpcsWithoutPartygoerNeverEvenSearchForDance(Trait trait) {
        var yaml=new YamlConfiguration();yaml.set("npcs.guest.location.world","world");
        yaml.set("npcs.guest.trait.type",trait.name().toLowerCase(Locale.ROOT));
        npc=new ActiveNpc(NpcParser.parse(yaml).get("guest"),new Location(world,.5,64,1.5),npc.entity(),npc.disguise());
        pose.npc=npc;when(manager.activeNpcs()).thenReturn(List.of(npc));
        server.getScheduler().performTicks(80);
        verify(dancers,never()).start(any(),any(),anyLong());
        verify(visuals,never()).leave(any(),anyBoolean());
        assertTrue(service.claimed(point));assertFalse(service.status("guest").contains("bailando"));
    }
}
