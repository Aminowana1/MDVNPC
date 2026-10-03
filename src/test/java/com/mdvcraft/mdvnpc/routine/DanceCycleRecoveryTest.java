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
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.util.BoundingBox;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DanceCycleRecoveryTest {
    @TempDir Path folder;
    ServerMock server;
    MdvNpcPlugin plugin;
    World world;
    Villager entity;
    RoutineService service;
    RoutineVisuals visuals;
    RoutineNavigator navigator;
    DanceController dancers;
    ActiveNpc npc;
    RoutineGoal.Point point;
    Location position;
    boolean mounted;
    boolean firstSeatExists=true;
    boolean otherSeatExists;
    boolean controllerStarted;
    long danceUntil;
    final List<Long> seatedAt=new ArrayList<>(),dancingAt=new ArrayList<>();

    @BeforeEach void setup() throws Exception {
        server=MockBukkit.mock();world=mock(World.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());when(world.getName()).thenReturn("world");
        when(world.getMinHeight()).thenReturn(-64);when(world.getMaxHeight()).thenReturn(320);
        when(world.isChunkLoaded(anyInt(),anyInt())).thenReturn(true);when(world.getFullTime()).thenReturn(1000L);
        Player observer=mock(Player.class);when(observer.getUniqueId()).thenReturn(UUID.randomUUID());
        when(observer.getWorld()).thenReturn(world);when(observer.isOnline()).thenReturn(true);
        when(observer.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(observer.getLocation()).thenAnswer(i->new Location(world,.5,64,3.5));
        when(world.getNearbyPlayers(any(Location.class),anyDouble(),any())).thenReturn(List.of(observer));
        when(world.getBlockAt(anyInt(),anyInt(),anyInt())).thenAnswer(call->{
            int x=call.getArgument(0),y=call.getArgument(1),z=call.getArgument(2);Block block=mock(Block.class);
            when(block.getType()).thenReturn(y==63?Material.STONE:Material.AIR);
            when(block.isPassable()).thenReturn(y!=63);
            when(block.getBoundingBox()).thenReturn(y==63?new BoundingBox(x,y,z,x+1,y+1,z+1):new BoundingBox(x,y,z,x,y,z));
            if(y==64 && z==0 && (x==0 && firstSeatExists || x==4 && otherSeatExists))when(block.getBlockData()).thenReturn(mock(Stairs.class));
            return block;
        });
        plugin=mock(MdvNpcPlugin.class);when(plugin.getName()).thenReturn("dance-cycle-test");when(plugin.isEnabled()).thenReturn(true);
        when(plugin.getServer()).thenReturn(server);when(plugin.getDataFolder()).thenReturn(folder.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        var options=new YamlConfiguration();options.set("routines.occasional-looking",false);
        when(plugin.settings()).thenReturn(Settings.parse(options));when(plugin.shops()).thenReturn(mock(ShopService.class));
        var yaml=new YamlConfiguration();yaml.set("npcs.guest.location.world","world");yaml.set("npcs.guest.trait.type","fiestero");
        entity=mock(Villager.class);when(entity.getUniqueId()).thenReturn(UUID.randomUUID());
        when(entity.isValid()).thenReturn(true);when(entity.getWorld()).thenReturn(world);
        position=new Location(world,.5,64,1.5);
        when(entity.getLocation()).thenAnswer(i->position.clone());
        when(entity.getEyeLocation()).thenAnswer(i->position.clone().add(0,1.6,0));
        when(entity.isInsideVehicle()).thenAnswer(i->mounted);
        npc=new ActiveNpc(NpcParser.parse(yaml).get("guest"),position.clone(),entity,null);
        var manager=mock(NpcManager.class);when(plugin.manager()).thenReturn(manager);when(manager.activeNpcs()).thenReturn(List.of(npc));
        visuals=mock(RoutineVisuals.class);
        when(visuals.enter(any(),any(),any(),any(),anyLong())).thenAnswer(call->{
            var pose=new RoutineVisuals.Pose();pose.npc=npc;pose.exit=((Location)call.getArgument(3)).clone();
            pose.seat=mock(ArmorStand.class);when(pose.seat.isValid()).thenReturn(true);
            mounted=true;seatedAt.add(call.getArgument(4));return pose;
        });
        doAnswer(call->{mounted=false;position=((RoutineVisuals.Pose)call.getArgument(0)).exit.clone();return null;})
                .when(visuals).leave(any(),anyBoolean());
        service=new RoutineService(plugin,visuals);when(plugin.routines()).thenReturn(service);
        point=new RoutineGoal.Point(world.getUID(),0,64,0,0);
        service.repository().put("guest",goal(List.of(point)));
        service.start();navigator=mock(RoutineNavigator.class);
        when(navigator.move(any(),any(),anyDouble(),anyLong(),anyInt())).thenAnswer(call->{
            position=((Location)call.getArgument(1)).clone();return RoutineNavigator.Result.ARRIVED;
        });
        var navigation=RoutineService.class.getDeclaredField("navigator");navigation.setAccessible(true);navigation.set(service,navigator);
        dancers=mock(DanceController.class);
        when(dancers.start(eq(npc),any(),anyLong())).thenAnswer(call->{controllerStarted=false;return true;});
        when(dancers.tick(eq(npc),anyLong(),anyInt(),anyDouble())).thenAnswer(call->{
            long tick=call.getArgument(1);
            if(!controllerStarted){controllerStarted=true;danceUntil=tick+1200;dancingAt.add(tick);}
            if(tick>=danceUntil)return DanceController.Result.FINISHED;
            position=new Location(world,8.5,64,1.5);return DanceController.Result.DANCING;
        });
        var dancing=RoutineService.class.getDeclaredField("dancers");dancing.setAccessible(true);dancing.set(service,dancers);
    }

    @AfterEach void cleanup(){try{if(service!=null)service.close();}finally{MockBukkit.unmock();}}
    private RoutineGoal goal(List<RoutineGoal.Point> points){return new RoutineGoal(1,RoutineGoal.Type.SIT,RoutineGoal.WalkMode.CYCLE,420,1080,2.4,20,points);}
    private void startDancing(){server.getScheduler().performTicks(620);assertTrue(service.status("guest").contains("bailando"));}
    private void finishDance(){doReturn(DanceController.Result.FINISHED).when(dancers).tick(eq(npc),anyLong(),anyInt(),anyDouble());}

    @Test void sixtySecondDancesAndThirtySecondSeatedIntervalsRepeat() {
        server.getScheduler().performTicks(590);verify(dancers,never()).start(any(),any(),anyLong());
        server.getScheduler().performTicks(3210);
        assertEquals(3,seatedAt.size());assertEquals(2,dancingAt.size());assertTrue(mounted);
        for(int cycle=0;cycle<2;cycle++){
            assertTrue(dancingAt.get(cycle)-seatedAt.get(cycle)>=600);
            assertTrue(seatedAt.get(cycle+1)-dancingAt.get(cycle)>=1200);
        }
        assertTrue(service.status("guest").contains("sentado"));assertTrue(service.claimed(point));
        verify(entity,never()).teleport(any(Location.class));
    }

    @Test void longReturnProgressDoesNotConsumeTheSeatedIntervalOrDiscardReservation() {
        startDancing();finishDance();position=new Location(world,100.5,64,1.5);
        doAnswer(call->{
            position.setX(position.getX()-.024);return RoutineNavigator.Result.MOVING;
        }).when(navigator).move(any(),any(),anyDouble(),anyLong(),anyInt());
        server.getScheduler().performTicks(1400);assertEquals(1,seatedAt.size());assertTrue(service.claimed(point));
        verify(dancers,times(1)).start(eq(npc),any(),anyLong());
        doAnswer(call->{
            position=((Location)call.getArgument(1)).clone();return RoutineNavigator.Result.ARRIVED;
        }).when(navigator).move(any(),any(),anyDouble(),anyLong(),anyInt());
        server.getScheduler().performTicks(2);assertEquals(2,seatedAt.size());
        server.getScheduler().performTicks(590);verify(dancers,times(1)).start(eq(npc),any(),anyLong());
        server.getScheduler().performTicks(20);verify(dancers,times(2)).start(eq(npc),any(),anyLong());
    }

    @Test void returnJustShortOfWaypointMountsInsteadOfWaitingForAnotherPath() {
        startDancing();finishDance();position=new Location(world,.5,64,2);
        doReturn(RoutineNavigator.Result.WAITING).when(navigator).move(any(),any(),anyDouble(),anyLong(),anyInt());
        clearInvocations(navigator);server.getScheduler().performTicks(4);
        assertEquals(2,seatedAt.size());assertTrue(mounted);assertTrue(service.claimed(point));
        verify(navigator,never()).move(any(),any(),anyDouble(),anyLong(),anyInt());
        verify(entity,never()).teleport(any(Location.class));
    }

    @Test void inaccessibleReturnTriesOtherDoorwaysThenReleasesOnlyTheProblemSeat() throws Exception {
        var other=new RoutineGoal.Point(world.getUID(),4,64,0,0);
        service.repository().put("guest",goal(List.of(point,other)));
        startDancing();finishDance();otherSeatExists=true;
        doAnswer(call->{
            Location target=call.getArgument(1);
            if(target.getX()<3)return RoutineNavigator.Result.WAITING;
            position=target.clone();return RoutineNavigator.Result.ARRIVED;
        }).when(navigator).move(any(),any(),anyDouble(),anyLong(),anyInt());
        server.getScheduler().performTicks(480);
        assertFalse(service.claimed(point));assertTrue(service.claimed(other));assertEquals(2,seatedAt.size());assertTrue(mounted);
        assertTrue(service.status("guest").contains("sentado"));verify(entity,never()).teleport(any(Location.class));
    }

    @Test void onlyInaccessibleSeatIsReleasedAndRetriesStayBounded() {
        startDancing();finishDance();
        doReturn(RoutineNavigator.Result.WAITING).when(navigator).move(any(),any(),anyDouble(),anyLong(),anyInt());
        clearInvocations(navigator);server.getScheduler().performTicks(480);
        assertFalse(service.claimed(point));assertEquals(1,seatedAt.size());
        clearInvocations(navigator);server.getScheduler().performTicks(400);
        verify(navigator,never()).move(any(),any(),anyDouble(),anyLong(),anyInt());
        assertFalse(service.claimed(point));verify(entity,never()).teleport(any(Location.class));
    }

    @Test void rejectedSeatMountFindsAnotherSeatInsteadOfKeepingAStandingReturner() throws Exception {
        var other=new RoutineGoal.Point(world.getUID(),4,64,0,0);
        service.repository().put("guest",goal(List.of(point,other)));
        startDancing();finishDance();otherSeatExists=true;
        doReturn(null).when(visuals).enter(eq(npc),any(),eq(point),any(),anyLong());
        server.getScheduler().performTicks(10);
        assertFalse(service.claimed(point));assertTrue(service.claimed(other));assertEquals(2,seatedAt.size());assertTrue(mounted);
        verify(entity,never()).teleport(any(Location.class));
    }

    @Test void removedReturnSeatImmediatelyFindsAnotherSeatFromTheSameGoal() throws Exception {
        var other=new RoutineGoal.Point(world.getUID(),4,64,0,0);
        service.repository().put("guest",goal(List.of(point,other)));
        startDancing();finishDance();otherSeatExists=true;firstSeatExists=false;
        server.getScheduler().performTicks(4);
        assertFalse(service.claimed(point));assertTrue(service.claimed(other));assertEquals(2,seatedAt.size());assertTrue(mounted);
        verify(entity,never()).teleport(any(Location.class));
    }

    @Test void unloadedSeatCannotReadItsBlocksOrTeleportAndRecoversWhenLoaded() {
        startDancing();finishDance();position=new Location(world,16.5,64,1.5);
        when(world.isChunkLoaded(eq(0),anyInt())).thenReturn(false);clearInvocations(world,entity);
        server.getScheduler().performTicks(40);
        verify(world,never()).getBlockAt(eq(0),anyInt(),anyInt());verify(entity,never()).teleport(any(Location.class));
        assertFalse(service.claimed(point));assertEquals(1,seatedAt.size());
        when(world.isChunkLoaded(eq(0),anyInt())).thenReturn(true);server.getScheduler().performTicks(120);
        assertEquals(2,seatedAt.size());assertTrue(mounted);assertTrue(service.claimed(point));
    }

    @Test void musicCancellationReturnsToSeatAndScheduleBoundaryNeverRestoresTheOldSeat() {
        startDancing();finishDance();server.getScheduler().performTicks(4);
        assertEquals(2,seatedAt.size());assertTrue(mounted);
        doAnswer(call->{
            position=new Location(world,8.5,64,1.5);return DanceController.Result.DANCING;
        }).when(dancers).tick(eq(npc),anyLong(),anyInt(),anyDouble());
        server.getScheduler().performTicks(620);assertTrue(service.status("guest").contains("acercándose")||service.status("guest").contains("bailando"));
        when(world.getFullTime()).thenReturn(12000L);server.getScheduler().performTicks(2);
        assertFalse(service.claimed(point));assertFalse(service.status("guest").contains("bailando"));
        assertEquals(2,seatedAt.size());
    }

    @Test void reactionCancellationResumesReturnRatherThanLeavingNpcStanding() {
        startDancing();service.prepareReaction(npc);server.getScheduler().performTicks(4);
        assertEquals(2,seatedAt.size());assertTrue(mounted);assertTrue(service.status("guest").contains("sentado"));
        verify(dancers).cancel("guest");verify(entity,never()).teleport(any(Location.class));
    }
}
