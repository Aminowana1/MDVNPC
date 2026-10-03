package com.mdvcraft.mdvnpc.routine;

import com.mdvcraft.mdvnpc.MdvNpcPlugin;
import com.mdvcraft.mdvnpc.config.*;
import com.mdvcraft.mdvnpc.runtime.*;
import com.mdvcraft.mdvnpc.shop.ShopService;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.*;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DanceLifecycleTest {
    @TempDir Path folder;
    ServerMock server;World world;RoutineService service;RoutineVisuals visuals;RoutineVisuals.Pose pose;
    DanceController dancers;ActiveNpc npc;RoutineGoal.Point point;
    @BeforeEach void setup() throws Exception {
        server=MockBukkit.mock();world=mock(World.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());when(world.getName()).thenReturn("world");
        when(world.getMinHeight()).thenReturn(-64);when(world.getMaxHeight()).thenReturn(320);
        when(world.isChunkLoaded(anyInt(),anyInt())).thenReturn(true);when(world.getFullTime()).thenReturn(1000L);
        when(world.getNearbyPlayers(any(Location.class),anyDouble(),any())).thenReturn(List.of(mock(Player.class)));
        when(world.getBlockAt(anyInt(),anyInt(),anyInt())).thenAnswer(call -> {
            int x=call.getArgument(0),y=call.getArgument(1),z=call.getArgument(2);Block b=mock(Block.class);
            when(b.getType()).thenReturn(y==63?Material.STONE:Material.AIR);when(b.isPassable()).thenReturn(y!=63);
            when(b.getBoundingBox()).thenReturn(y==63?new org.bukkit.util.BoundingBox(x,y,z,x+1,y+1,z+1):new org.bukkit.util.BoundingBox(x,y,z,x,y,z));
            if(x==0 && y==64 && z==0)when(b.getBlockData()).thenReturn(mock(Stairs.class));return b;
        });
        var plugin=mock(MdvNpcPlugin.class);when(plugin.getName()).thenReturn("dance-test");when(plugin.isEnabled()).thenReturn(true);
        when(plugin.getServer()).thenReturn(server);when(plugin.getDataFolder()).thenReturn(folder.toFile());when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        when(plugin.settings()).thenReturn(Settings.parse(new YamlConfiguration()));when(plugin.shops()).thenReturn(mock(ShopService.class));
        var yaml=new YamlConfiguration();yaml.set("npcs.guest.location.world","world");var def=NpcParser.parse(yaml).get("guest");
        Villager entity=mock(Villager.class);when(entity.getUniqueId()).thenReturn(UUID.randomUUID());when(entity.isValid()).thenReturn(true);when(entity.getWorld()).thenReturn(world);
        when(entity.isInsideVehicle()).thenReturn(true);when(entity.getLocation()).thenAnswer(i->new Location(world,.5,64,1.5));
        npc=new ActiveNpc(def,new Location(world,.5,64,1.5),entity,null);
        var manager=mock(NpcManager.class);when(plugin.manager()).thenReturn(manager);when(manager.activeNpcs()).thenReturn(List.of(npc));
        visuals=mock(RoutineVisuals.class);pose=new RoutineVisuals.Pose();pose.npc=npc;pose.seat=mock(ArmorStand.class);when(pose.seat.isValid()).thenReturn(true);
        when(visuals.enter(any(),any(),any(),any(),anyLong())).thenReturn(pose);
        service=new RoutineService(plugin,visuals);when(plugin.routines()).thenReturn(service);
        point=new RoutineGoal.Point(world.getUID(),0,64,0,0);
        service.repository().put("guest",new RoutineGoal(1,RoutineGoal.Type.SIT,RoutineGoal.WalkMode.CYCLE,420,1080,2.4,20,List.of(point)));
        service.start();dancers=mock(DanceController.class);when(dancers.start(eq(npc),any(),anyLong())).thenReturn(true);
        when(dancers.tick(eq(npc),anyLong(),anyInt(),anyDouble())).thenReturn(DanceController.Result.DANCING);
        var field=RoutineService.class.getDeclaredField("dancers");field.setAccessible(true);field.set(service,dancers);
        server.getScheduler().performTicks(14);
    }
    @AfterEach void cleanup(){if(service!=null)service.close();MockBukkit.unmock();}
    @Test void dancingReleasesVisualSeatButRetainsReservationAndReturnsToSit() {
        assertTrue(service.status("guest").contains("bailando"));assertTrue(service.claimed(point));
        verify(visuals).leave(pose,true);
        when(dancers.tick(eq(npc),anyLong(),anyInt(),anyDouble())).thenReturn(DanceController.Result.FINISHED);
        server.getScheduler().performTicks(4);verify(dancers).cancel("guest");
        verify(visuals,times(2)).enter(eq(npc),any(),eq(point),any(),anyLong());assertTrue(service.claimed(point));
    }
    @Test void scheduleBoundaryStopsDancingAndFreesSeat() {
        when(world.getFullTime()).thenReturn(12000L);server.getScheduler().performTicks(2);
        verify(dancers).cancel("guest");assertFalse(service.claimed(point));assertFalse(service.status("guest").contains("bailando"));
    }
    @Test void noObserversCancelsTemporaryDance() {
        when(world.getNearbyPlayers(any(Location.class),anyDouble(),any())).thenReturn(List.of());server.getScheduler().performTicks(22);
        verify(dancers).cancel("guest");assertTrue(service.status("guest").contains("suspendido"));
    }
}
