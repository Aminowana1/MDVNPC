package com.mdvcraft.mdvnpc.routine;

import com.mdvcraft.mdvnpc.MdvNpcPlugin;
import com.mdvcraft.mdvnpc.config.*;
import com.mdvcraft.mdvnpc.runtime.*;
import com.mdvcraft.mdvnpc.shop.ShopService;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Bed;
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

/** Tests lifecycle independently of LibsDisguises rendering/server-only Authlib. */
class SleepLifecycleTest {
    @TempDir Path folder;
    ServerMock server;World world;RoutineService service;RoutineVisuals visuals;RoutineVisuals.Pose pose;
    @BeforeEach void setup() throws Exception {
        server=MockBukkit.mock();world=mock(World.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());when(world.getName()).thenReturn("world");
        when(world.getMinHeight()).thenReturn(-64);when(world.getMaxHeight()).thenReturn(320);
        when(world.isChunkLoaded(anyInt(),anyInt())).thenReturn(true);when(world.getFullTime()).thenReturn(18000L);
        when(world.getNearbyPlayers(any(Location.class),anyDouble(),any())).thenReturn(List.of(mock(Player.class)));
        when(world.getBlockAt(anyInt(),anyInt(),anyInt())).thenAnswer(call -> {
            int x=call.getArgument(0),y=call.getArgument(1),z=call.getArgument(2);Block block=mock(Block.class);
            when(block.getType()).thenReturn(y==63?Material.STONE:Material.AIR);when(block.isPassable()).thenReturn(y!=63);
            when(block.getBoundingBox()).thenReturn(y==63?new org.bukkit.util.BoundingBox(x,y,z,x+1,y+1,z+1):new org.bukkit.util.BoundingBox(x,y,z,x,y,z));
            if(x==0 && y==64 && z==0)when(block.getBlockData()).thenReturn(mock(Bed.class));
            return block;
        });
        var plugin=mock(MdvNpcPlugin.class);when(plugin.getName()).thenReturn("MDVNPC-test");when(plugin.isEnabled()).thenReturn(true);
        when(plugin.getServer()).thenReturn(server);when(plugin.getDataFolder()).thenReturn(folder.toFile());when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        when(plugin.settings()).thenReturn(Settings.parse(new YamlConfiguration()));when(plugin.shops()).thenReturn(mock(ShopService.class));
        var yaml=new YamlConfiguration();yaml.set("npcs.sleeper.location.world","world");var def=NpcParser.parse(yaml).get("sleeper");
        Villager entity=mock(Villager.class);when(entity.getUniqueId()).thenReturn(UUID.randomUUID());when(entity.isValid()).thenReturn(true);when(entity.getWorld()).thenReturn(world);
        when(entity.getLocation()).thenAnswer(i -> new Location(world,.5,64,1.5));
        var npc=new ActiveNpc(def,new Location(world,.5,64,1.5),entity,null);
        var manager=mock(NpcManager.class);when(plugin.manager()).thenReturn(manager);when(manager.activeNpcs()).thenReturn(List.of(npc));
        visuals=mock(RoutineVisuals.class);pose=new RoutineVisuals.Pose();pose.sleeping=true;pose.npc=npc;
        when(visuals.enter(any(),any(),any(),any(),anyLong())).thenReturn(pose);when(visuals.restoreSleep(any(),anyBoolean())).thenReturn(true);
        service=new RoutineService(plugin,visuals);when(plugin.routines()).thenReturn(service);
        service.repository().put("sleeper",new RoutineGoal(1,RoutineGoal.Type.SLEEP,RoutineGoal.WalkMode.CYCLE,1320,420,2.4,20,
                List.of(new RoutineGoal.Point(world.getUID(),0,64,0,0))));service.start();server.getScheduler().performTicks(2);
    }
    @AfterEach void cleanup(){if(service!=null)service.close();MockBukkit.unmock();}
    void absent() {when(world.getNearbyPlayers(any(Location.class),anyDouble(),any())).thenReturn(List.of());server.getScheduler().performTicks(22);}
    @Test void suspensionKeepsBedAndActivationRepairsExistingPose() {
        absent();verify(visuals).suspend(eq(pose),anyLong());verify(visuals,never()).leave(any(),anyBoolean());
        assertTrue(service.claimed(new RoutineGoal.Point(world.getUID(),0,64,0,0)));
        when(world.getNearbyPlayers(any(Location.class),anyDouble(),any())).thenReturn(List.of(mock(Player.class)));server.getScheduler().performTicks(22);
        verify(visuals).restoreSleep(pose,true);verify(visuals,never()).leave(any(),anyBoolean());assertEquals("durmiendo; goal 1",service.status("sleeper"));
    }
    @Test void scheduleEndReleasesBedEvenWhileNoOneIsNearby() {
        absent();when(world.getFullTime()).thenReturn(25000L);server.getScheduler().performTicks(22);
        verify(visuals).leave(pose,true);assertFalse(service.claimed(new RoutineGoal.Point(world.getUID(),0,64,0,0)));
    }
}
