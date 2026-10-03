package com.mdvcraft.mdvnpc.routine;

import com.destroystokyo.paper.entity.Pathfinder;
import com.mdvcraft.mdvnpc.MdvNpcPlugin;
import com.mdvcraft.mdvnpc.config.NpcParser;
import com.mdvcraft.mdvnpc.config.Settings;
import com.mdvcraft.mdvnpc.runtime.ActiveNpc;
import com.mdvcraft.mdvnpc.runtime.NpcManager;
import com.mdvcraft.mdvnpc.shop.ShopService;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Bed;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.util.BoundingBox;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.*;

import java.nio.file.Path;
import java.util.*;
import java.util.function.Predicate;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RoutineTransitionTest {
    @TempDir Path folder;
    ServerMock server;World world;RoutineService service;RoutineVisuals visuals;
    ActiveNpc npc;Villager entity;Player observer;Pathfinder pathfinder;
    Location position,observerPosition;boolean following=true;
    final List<Location> moves=new ArrayList<>();
    final Map<String,Block> blocks=new HashMap<>();

    @BeforeEach @SuppressWarnings("unchecked") void setup() throws Exception {
        server=MockBukkit.mock();world=mock(World.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());when(world.getName()).thenReturn("world");
        when(world.getMinHeight()).thenReturn(-64);when(world.getMaxHeight()).thenReturn(320);
        when(world.isChunkLoaded(anyInt(),anyInt())).thenReturn(true);when(world.getFullTime()).thenReturn(6000L);
        position=new Location(world,.5,64,.5);observer=mock(Player.class);
        when(observer.getUniqueId()).thenReturn(UUID.randomUUID());when(observer.isOnline()).thenReturn(true);
        when(observer.getWorld()).thenReturn(world);when(observer.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(observer.getLocation()).thenAnswer(i->following?position.clone().add(0,0,1):observerPosition);
        when(observer.getEyeLocation()).thenAnswer(i->observer.getLocation()==null?null:observer.getLocation().clone().add(0,1.6,0));
        when(world.getNearbyPlayers(any(Location.class),anyDouble(),any())).thenAnswer(call->{
            Location at=call.getArgument(0),eye=observer.getLocation();double range=call.getArgument(1);
            Predicate<Player> filter=call.getArgument(2);
            return eye!=null && at.distanceSquared(eye)<=range*range && filter.test(observer)?List.of(observer):List.of();
        });
        when(world.getBlockAt(anyInt(),anyInt(),anyInt())).thenAnswer(call->{
            int x=call.getArgument(0),y=call.getArgument(1),z=call.getArgument(2);
            return blocks.computeIfAbsent(x+","+y+","+z,ignored->{
                Block block=mock(Block.class);boolean floor=y==63;
                when(block.getType()).thenReturn(floor?Material.STONE:Material.AIR);when(block.isPassable()).thenReturn(!floor);
                BoundingBox box=floor?new BoundingBox(x,y,z,x+1,y+1,z+1):new BoundingBox(x,y,z,x,y,z);
                when(block.getBoundingBox()).thenReturn(box);
                if(y==64 && z==0 && (x==0 || x==18)) {
                    BlockData data=x==0?mock(Bed.class):mock(Stairs.class);when(block.getBlockData()).thenReturn(data);
                }
                return block;
            });
        });
        MdvNpcPlugin plugin=mock(MdvNpcPlugin.class);ShopService shops=mock(ShopService.class);
        Settings settings=Settings.parse(new YamlConfiguration());Logger logger=mock(Logger.class);
        when(plugin.getName()).thenReturn("transition-test");when(plugin.isEnabled()).thenReturn(true);
        when(plugin.getServer()).thenReturn(server);when(plugin.getDataFolder()).thenReturn(folder.toFile());
        when(plugin.settings()).thenReturn(settings);when(plugin.getLogger()).thenReturn(logger);when(plugin.shops()).thenReturn(shops);
        YamlConfiguration yaml=new YamlConfiguration();yaml.set("npcs.thurg.location.world","world");yaml.set("npcs.thurg.location.y",64);
        entity=mock(Villager.class);when(entity.getUniqueId()).thenReturn(UUID.randomUUID());when(entity.getWorld()).thenReturn(world);
        when(entity.isValid()).thenReturn(true);when(entity.isOnGround()).thenReturn(true);when(entity.isInsideVehicle()).thenReturn(true);
        when(entity.getLocation()).thenAnswer(i->position.clone());when(entity.getEyeLocation()).thenAnswer(i->position.clone().add(0,1.6,0));
        when(entity.teleport(any(Location.class))).thenAnswer(call->{position=((Location)call.getArgument(0)).clone();moves.add(position.clone());return true;});
        npc=new ActiveNpc(NpcParser.parse(yaml).get("thurg"),position.clone(),entity,null);
        NpcManager manager=mock(NpcManager.class);when(manager.activeNpcs()).thenReturn(List.of(npc));when(plugin.manager()).thenReturn(manager);
        pathfinder=mock(Pathfinder.class);when(entity.getPathfinder()).thenReturn(pathfinder);
        when(pathfinder.findPath(any(Location.class))).thenAnswer(call->{
            Location target=call.getArgument(0);List<Location> path=new ArrayList<>();int x=position.getBlockX(),z=position.getBlockZ();
            path.add(new Location(world,x,64,z));
            while(x!=target.getBlockX() || z!=target.getBlockZ()) {
                x+=Integer.compare(target.getBlockX(),x);z+=Integer.compare(target.getBlockZ(),z);path.add(new Location(world,x,64,z));
            }
            Pathfinder.PathResult result=mock(Pathfinder.PathResult.class);when(result.getPoints()).thenReturn(path);return result;
        });
        visuals=mock(RoutineVisuals.class);when(visuals.restoreSleep(any(),anyBoolean())).thenReturn(true);
        when(visuals.enter(any(),any(),any(),any(),anyLong())).thenAnswer(call->{
            Location approach=call.getArgument(3);assertTrue(position.distanceSquared(approach)<.025,"pose begins only after walking to its doorway");
            RoutineVisuals.Pose pose=new RoutineVisuals.Pose();pose.npc=npc;pose.sleeping=((RoutineGoal)call.getArgument(1)).type()==RoutineGoal.Type.SLEEP;
            if(!pose.sleeping){ArmorStand seat=mock(ArmorStand.class);when(seat.isValid()).thenReturn(true);pose.seat=seat;}
            return pose;
        });
        service=new RoutineService(plugin,visuals);when(plugin.routines()).thenReturn(service);
    }
    @AfterEach void cleanup(){try{if(service!=null)service.close();}finally{MockBukkit.unmock();}}
    RoutineGoal goal(int order,RoutineGoal.Type type,RoutineGoal.WalkMode mode,int from,int until,int x) {
        return new RoutineGoal(order,type,mode,from,until,2.4,20,List.of(new RoutineGoal.Point(world.getUID(),x,64,0,0)));
    }
    void advance(int ticks){server.getScheduler().performTicks(ticks);}
    void assertWalkingSteps(Location from) {
        for(Location step:moves){assertTrue(from.distance(step)<=.240001,"unexpected visible jump: "+from+" -> "+step);from=step;}
    }

    @Test void randomWalkChangesToSittingByWalkingToTheNewSeat() throws Exception {
        service.repository().put("thurg",goal(1,RoutineGoal.Type.WALK,RoutineGoal.WalkMode.RANDOM,720,960,6));
        service.repository().put("thurg",goal(2,RoutineGoal.Type.SIT,RoutineGoal.WalkMode.CYCLE,960,720,18));
        Location initial=position.clone();service.start();advance(2);
        when(world.getFullTime()).thenReturn(10000L);advance(20);
        verify(visuals,never()).enter(any(),any(),any(),any(),anyLong());
        advance(200);verify(visuals).enter(eq(npc),any(),any(),any(),anyLong());
        assertTrue(service.status("thurg").contains("sentado"));assertWalkingSteps(initial);
    }

    @Test void anObserverAtTheDestinationPreventsCatchupAndTheNpcResumesWalkingFromItsOldPosition() throws Exception {
        service.repository().put("thurg",goal(1,RoutineGoal.Type.WORK,RoutineGoal.WalkMode.CYCLE,0,0,160));
        service.start();advance(2);following=false;observerPosition=new Location(world,160.5,64,.5);advance(22);
        assertTrue(service.status("thurg").contains("suspendido"));Location paused=position.clone();moves.clear();advance(120);
        assertEquals(paused,position);assertTrue(moves.isEmpty());
        observerPosition=paused.clone().add(0,0,1);advance(22);
        assertFalse(service.status("thurg").contains("suspendido"));assertFalse(service.canInteract(npc));
        assertFalse(moves.isEmpty());assertWalkingSteps(paused);
    }

    @Test void aVisibleObserverBeyondActivationRangeBlocksCatchupWithoutActivatingTheNpc() throws Exception {
        service.repository().put("thurg",goal(1,RoutineGoal.Type.WORK,RoutineGoal.WalkMode.CYCLE,0,0,160));
        service.start();advance(2);following=false;observerPosition=position.clone().add(70,0,0);advance(22);
        Location paused=position.clone();moves.clear();advance(120);
        assertTrue(service.status("thurg").contains("suspendido"));assertEquals(paused,position);assertTrue(moves.isEmpty());
    }

    @Test void followingSpectatorsKeepTheWalkVisibleAndNeverTriggerCatchup() throws Exception {
        when(observer.getGameMode()).thenReturn(GameMode.SPECTATOR);
        service.repository().put("thurg",goal(1,RoutineGoal.Type.WALK,RoutineGoal.WalkMode.RANDOM,0,0,160));
        Location initial=position.clone();service.start();advance(60);
        assertFalse(service.status("thurg").contains("suspendido"));assertWalkingSteps(initial);
    }

    @Test void entityTrackersBeyondTheLocalVisibilityGuardAlsoPreventCatchup() throws Exception {
        following=false;observerPosition=new Location(world,-130,64,.5);
        when(entity.getTrackedBy()).thenReturn(Set.of(observer));
        service.repository().put("thurg",goal(1,RoutineGoal.Type.WORK,RoutineGoal.WalkMode.CYCLE,0,0,160));
        Location initial=position.clone();service.start();advance(120);
        assertTrue(service.status("thurg").contains("suspendido"));assertEquals(initial,position);assertTrue(moves.isEmpty());
    }

    @Test void unseenSleepIsPreservedUntilTheScheduleChangesAndCatchupRunsOnce() throws Exception {
        position=new Location(world,.5,64,1.5);when(world.getFullTime()).thenReturn(1000L);
        service.repository().put("thurg",goal(1,RoutineGoal.Type.SLEEP,RoutineGoal.WalkMode.CYCLE,420,480,0));
        service.repository().put("thurg",goal(2,RoutineGoal.Type.WORK,RoutineGoal.WalkMode.CYCLE,480,420,160));
        service.start();advance(2);following=false;observerPosition=null;advance(22);
        verify(visuals,never()).leave(any(),anyBoolean());assertTrue(service.status("thurg").contains("suspendido"));
        when(world.getFullTime()).thenReturn(2000L);advance(22);
        verify(visuals).leave(any(),eq(true));assertEquals(160.5,position.getX(),.000001);
        int movesAfterCatchup=moves.size();long blockReads=mockingDetails(world).getInvocations().stream().filter(i->i.getMethod().getName().equals("getBlockAt")).count();
        advance(120);assertEquals(movesAfterCatchup,moves.size());
        assertEquals(blockReads,mockingDetails(world).getInvocations().stream().filter(i->i.getMethod().getName().equals("getBlockAt")).count());
        following=true;advance(22);assertTrue(service.canInteract(npc));
    }
}
