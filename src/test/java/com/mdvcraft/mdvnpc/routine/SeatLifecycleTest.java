package com.mdvcraft.mdvnpc.routine;

import com.mdvcraft.mdvnpc.MdvNpcPlugin;
import com.mdvcraft.mdvnpc.config.NpcParser;
import com.mdvcraft.mdvnpc.config.Settings;
import com.mdvcraft.mdvnpc.runtime.ActiveNpc;
import com.mdvcraft.mdvnpc.runtime.NpcManager;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** A retained chair must restore its actual passenger instead of staying "seated" in state only. */
class SeatLifecycleTest {
    @TempDir Path folder;
    ServerMock server;World world;RoutineService service;RoutineVisuals visuals;
    RoutineVisuals.Pose pose;ActiveNpc npc;Villager entity;RoutineGoal.Point point;
    final AtomicReference<Entity> vehicle=new AtomicReference<>();
    boolean repairSucceeds=true;

    @BeforeEach void setup() throws Exception {
        server=MockBukkit.mock();world=mock(World.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());when(world.getName()).thenReturn("world");
        when(world.getMinHeight()).thenReturn(-64);when(world.getMaxHeight()).thenReturn(320);
        when(world.isChunkLoaded(anyInt(),anyInt())).thenReturn(true);when(world.getFullTime()).thenReturn(6000L);
        when(world.getNearbyPlayers(any(Location.class),anyDouble(),any())).thenReturn(List.of(mock(Player.class)));
        when(world.getBlockAt(anyInt(),anyInt(),anyInt())).thenAnswer(call->{
            int x=call.getArgument(0),y=call.getArgument(1),z=call.getArgument(2);Block block=mock(Block.class);
            when(block.getType()).thenReturn(y==63?Material.STONE:Material.AIR);when(block.isPassable()).thenReturn(y!=63);
            when(block.getBoundingBox()).thenReturn(y==63?new org.bukkit.util.BoundingBox(x,y,z,x+1,y+1,z+1):new org.bukkit.util.BoundingBox(x,y,z,x,y,z));
            if(x==0 && y==64 && z==0)when(block.getBlockData()).thenReturn(mock(Stairs.class));
            return block;
        });
        MdvNpcPlugin plugin=mock(MdvNpcPlugin.class);when(plugin.getName()).thenReturn("seat-test");when(plugin.isEnabled()).thenReturn(true);
        when(plugin.getServer()).thenReturn(server);when(plugin.getDataFolder()).thenReturn(folder.toFile());when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        when(plugin.settings()).thenReturn(Settings.parse(new YamlConfiguration()));when(plugin.shops()).thenReturn(mock(ShopService.class));
        YamlConfiguration yaml=new YamlConfiguration();yaml.set("npcs.guest.location.world","world");
        entity=mock(Villager.class);when(entity.getUniqueId()).thenReturn(UUID.randomUUID());when(entity.isValid()).thenReturn(true);when(entity.getWorld()).thenReturn(world);
        when(entity.getLocation()).thenAnswer(i->new Location(world,.5,64,1.5));when(entity.getVehicle()).thenAnswer(i->vehicle.get());
        npc=new ActiveNpc(NpcParser.parse(yaml).get("guest"),new Location(world,.5,64,1.5),entity,null);
        NpcManager manager=mock(NpcManager.class);when(plugin.manager()).thenReturn(manager);when(manager.activeNpcs()).thenReturn(List.of(npc));
        visuals=mock(RoutineVisuals.class);pose=new RoutineVisuals.Pose();pose.npc=npc;pose.seat=mock(ArmorStand.class);when(pose.seat.isValid()).thenReturn(true);
        when(visuals.enter(any(),any(),any(),any(),anyLong())).thenAnswer(i->{vehicle.set(pose.seat);return pose;});
        when(visuals.restoreSeat(eq(pose),anyBoolean())).thenAnswer(i->{
            assertTrue(service.mounting(entity),"seat repairs must authorize their own mount events");
            if(repairSucceeds)vehicle.set(pose.seat);return repairSucceeds;
        });
        service=new RoutineService(plugin,visuals);when(plugin.routines()).thenReturn(service);
        point=new RoutineGoal.Point(world.getUID(),0,64,0,0);
        service.repository().put("guest",new RoutineGoal(1,RoutineGoal.Type.SIT,RoutineGoal.WalkMode.CYCLE,0,0,2.4,20,List.of(point)));
        service.start();server.getScheduler().performTicks(2);assertTrue(service.claimed(point));
    }
    @AfterEach void cleanup(){try{if(service!=null)service.close();}finally{MockBukkit.unmock();}}

    @Test void aLostPassengerIsRepairedBeforeTheChairReservationIsReleased(){
        vehicle.set(null);clearInvocations(visuals);server.getScheduler().performTicks(22);
        verify(visuals,atLeastOnce()).restoreSeat(pose,false);verify(visuals,never()).leave(any(),anyBoolean());
        assertSame(pose.seat,vehicle.get());assertTrue(service.claimed(point));assertTrue(service.status("guest").contains("sentado"));
        assertFalse(service.mounting(entity));assertFalse(service.internal(entity));
    }
    @Test void periodicChecksAlsoRepairPosesWithAnIntactServerMount(){
        clearInvocations(visuals);server.getScheduler().performTicks(42);
        verify(visuals,atLeastOnce()).restoreSeat(pose,false);verify(visuals,never()).leave(any(),anyBoolean());assertTrue(service.claimed(point));
    }
    @Test void activationRefreshesTheRetainedChairAndItsMountAuthorizationEndsAfterward(){
        when(world.getNearbyPlayers(any(Location.class),anyDouble(),any())).thenReturn(List.of());server.getScheduler().performTicks(22);
        verify(visuals).suspend(eq(pose),anyLong());assertTrue(service.claimed(point));
        when(world.getNearbyPlayers(any(Location.class),anyDouble(),any())).thenReturn(List.of(mock(Player.class)));server.getScheduler().performTicks(22);
        verify(visuals).restoreSeat(pose,true);verify(visuals,never()).leave(any(),anyBoolean());assertTrue(service.status("guest").contains("sentado"));
        assertFalse(service.mounting(entity));assertFalse(service.internal(entity));
    }
    @Test void aFailedRepairReleasesTheChairAndCanRetryThroughWalking(){
        repairSucceeds=false;vehicle.set(null);server.getScheduler().performTicks(22);
        verify(visuals).leave(pose,true);assertFalse(service.claimed(point));assertFalse(service.status("guest").contains("error"));
        assertFalse(service.mounting(entity));assertFalse(service.internal(entity));
    }
    @Test void aForeignVehicleCannotEnableBeerOffersAsThoughItWereTheChair(){
        assertTrue(service.canReceiveBeer(npc));vehicle.set(mock(Entity.class));assertFalse(service.canReceiveBeer(npc));
    }
}
