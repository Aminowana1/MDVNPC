package com.mdvcraft.mdvnpc.routine;

import com.destroystokyo.paper.entity.Pathfinder;
import com.mdvcraft.mdvnpc.MdvNpcPlugin;
import com.mdvcraft.mdvnpc.config.NpcParser;
import com.mdvcraft.mdvnpc.config.Settings;
import com.mdvcraft.mdvnpc.runtime.ActiveNpc;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.util.BoundingBox;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Audit harness: real RoutineService, navigator, terrain, and scheduler; mocked world,
 * Paper PathResult, and cosmetic poses. These checks do not run Paper's native A*.
 */
class NavigationGoalsAuditTest {
    @TempDir Path folder;
    RoutineTransitionTest f;
    MdvNpcPlugin plugin;

    @BeforeEach void setup() throws Exception {
        f=new RoutineTransitionTest();f.folder=folder;f.setup();
        plugin=(MdvNpcPlugin)field(RoutineService.class,"plugin").get(f.service);
        configureCadence(2);
    }
    @AfterEach void cleanup(){if(f!=null)f.cleanup();}
    private static Field field(Class<?> type,String name) throws Exception {
        Field result=type.getDeclaredField(name);result.setAccessible(true);return result;
    }
    private void configureCadence(int cadence) {
        YamlConfiguration options=new YamlConfiguration();
        options.set("routines.movement-interval-ticks",cadence);
        options.set("routines.occasional-looking",false);
        when(plugin.settings()).thenReturn(Settings.parse(options));
    }
    private RoutineGoal.Point point(int x,int z){return new RoutineGoal.Point(f.world.getUID(),x,64,z,0);}
    private RoutineGoal goal(int order,RoutineGoal.Type type,RoutineGoal.WalkMode mode,double speed,RoutineGoal.Point... points) {
        return new RoutineGoal(order,type,mode,0,0,speed,128,List.of(points));
    }
    private void begin(RoutineGoal... goals) throws Exception {
        for(RoutineGoal goal:goals)f.service.repository().put("thurg",goal);
        f.service.start();
    }
    private boolean reached(int x,int z) {
        Location target=point(x,z).location(f.world);
        return f.moves.stream().anyMatch(at->at.distanceSquared(target)<.025);
    }
    private Pathfinder.PathResult straight(Location target) {
        List<Location> points=new ArrayList<>();int x=f.position.getBlockX(),z=f.position.getBlockZ();
        points.add(new Location(f.world,x,64,z));
        while(x!=target.getBlockX() || z!=target.getBlockZ()) {
            x+=Integer.compare(target.getBlockX(),x);z+=Integer.compare(target.getBlockZ(),z);
            points.add(new Location(f.world,x,64,z));
        }
        Pathfinder.PathResult result=mock(Pathfinder.PathResult.class);when(result.getPoints()).thenReturn(points);return result;
    }
    private void solid(int x,int y,int z) {
        Block block=f.world.getBlockAt(x,y,z);
        when(block.getType()).thenReturn(Material.STONE);when(block.isPassable()).thenReturn(false);
        when(block.getBlockData()).thenReturn(null);
        when(block.getBoundingBox()).thenReturn(new BoundingBox(x,y,z,x+1,y+1,z+1));
    }
    private void air(int x,int y,int z) {
        Block block=f.world.getBlockAt(x,y,z);
        when(block.getType()).thenReturn(Material.AIR);when(block.isPassable()).thenReturn(true);
        when(block.getBlockData()).thenReturn(null);
        when(block.getBoundingBox()).thenReturn(new BoundingBox(x,y,z,x,y,z));
    }
    static Stream<Arguments> remoteFloors() {
        return Stream.of(Arguments.of(RoutineGoal.Type.WORK,1),Arguments.of(RoutineGoal.Type.WORK,-1),
                Arguments.of(RoutineGoal.Type.SLEEP,1),Arguments.of(RoutineGoal.Type.SLEEP,-1));
    }

    @ParameterizedTest @MethodSource("remoteFloors")
    void remoteBedOrWorkOnAnotherFloorCompletesItsStairJourney(RoutineGoal.Type type,int direction) throws Exception {
        int finalY=64+4*direction;
        for(int x=1;x<=162;x++)for(int z=-1;z<=1;z++) {
            int feet=64+Math.min(x,4)*direction;
            if(direction<0)air(x,63,z);
            solid(x,feet-1,z);
        }
        RoutineGoal.Point destination=new RoutineGoal.Point(f.world.getUID(),160,finalY,0,0);
        if(type==RoutineGoal.Type.SLEEP) {
            when(f.world.getBlockAt(160,finalY,0).getBlockData()).thenReturn(mock(org.bukkit.block.data.type.Bed.class));
            // Only the west side of the remote bed remains available after the stair journey.
            for(int[] side:new int[][]{{1,0},{0,1},{0,-1}})for(int y=finalY;y<=finalY+1;y++)solid(160+side[0],y,side[1]);
        }
        when(f.pathfinder.findPath(any(Location.class))).thenAnswer(call->{
            Location target=call.getArgument(0);List<Location> path=new ArrayList<>();
            for(int x=f.position.getBlockX();x<=target.getBlockX();x++)
                path.add(new Location(f.world,x,64+Math.min(Math.max(x,0),4)*direction,0));
            if(target.getBlockZ()!=0)path.add(new Location(f.world,target.getBlockX(),finalY,target.getBlockZ()));
            Pathfinder.PathResult result=mock(Pathfinder.PathResult.class);
            when(result.getPoints()).thenReturn(path);when(result.canReachFinalPoint()).thenReturn(true);return result;
        });
        Location initial=f.position.clone();
        begin(goal(1,type,RoutineGoal.WalkMode.CYCLE,2.4,destination));f.advance(1600);
        assertEquals(finalY,f.position.getY(),.02,f.service.status("thurg"));
        if(type==RoutineGoal.Type.WORK)assertTrue(f.service.canInteract(f.npc),f.service.status("thurg"));
        else {
            assertTrue(f.service.status("thurg").contains("durmiendo"),f.service.status("thurg"));
            verify(f.visuals).enter(eq(f.npc),any(),eq(destination),eq(new Location(f.world,159.5,finalY,.5)),anyLong());
        }
        f.assertWalkingSteps(initial);
    }

    @Test void targetWalkCompletesAllWaypointsThenStartsWork() throws Exception {
        begin(goal(1,RoutineGoal.Type.WALK,RoutineGoal.WalkMode.TARGET,2.4,point(5,0),point(10,0)),
                goal(2,RoutineGoal.Type.WORK,RoutineGoal.WalkMode.CYCLE,2.4,point(20,0)));
        Location initial=f.position.clone();f.advance(220);
        assertTrue(reached(5,0));assertTrue(reached(10,0));assertTrue(reached(20,0));
        assertEquals(RoutineGoal.Type.WORK,f.service.activeGoal(f.npc).type());
        assertTrue(f.service.canInteract(f.npc));f.assertWalkingSteps(initial);
    }

    @Test void cycleWalkVisitsWaypointsRepeatedly() throws Exception {
        begin(goal(1,RoutineGoal.Type.WALK,RoutineGoal.WalkMode.CYCLE,2.4,point(2,0),point(6,0)));
        Location initial=f.position.clone();f.advance(360);
        assertTrue(reached(2,0));assertTrue(reached(6,0));
        assertEquals(RoutineGoal.Type.WALK,f.service.activeGoal(f.npc).type());
        assertFalse(f.service.status("thurg").contains("secuencia terminada"));
        List<Integer> visits=new ArrayList<>();
        for(Location step:f.moves) {
            Integer visited=null;
            if(step.distanceSquared(new Location(f.world,2.5,64,.5))<.025)visited=2;
            else if(step.distanceSquared(new Location(f.world,6.5,64,.5))<.025)visited=6;
            if(visited!=null && (visits.isEmpty() || !visited.equals(visits.getLast())))visits.add(visited);
        }
        assertTrue(visits.size()>=4,"cycle must revisit both points: "+visits);
        assertEquals(List.of(2,6,2,6),visits.subList(0,4));
        f.assertWalkingSteps(initial);
    }

    @Test void randomWalkUsesOtherPointsAndKeepsWalking() throws Exception {
        begin(goal(1,RoutineGoal.Type.WALK,RoutineGoal.WalkMode.RANDOM,2.4,point(2,0),point(6,0)));
        Location initial=f.position.clone();f.advance(420);
        assertTrue(reached(2,0));assertTrue(reached(6,0));
        assertEquals(RoutineGoal.Type.WALK,f.service.activeGoal(f.npc).type());
        assertFalse(f.service.canInteract(f.npc));f.assertWalkingSteps(initial);
    }

    @Test void aProgressingWorkJourneyLongerThanSixtySecondsStillArrives() throws Exception {
        begin(goal(1,RoutineGoal.Type.WORK,RoutineGoal.WalkMode.CYCLE,2.4,point(160,0)));
        Location initial=f.position.clone();f.advance(1500);
        assertTrue(f.service.canInteract(f.npc),f.service.status("thurg"));
        assertTrue(reached(160,0));f.assertWalkingSteps(initial);
    }

    @Test void minimumLegalSpeedAtOneTickCadenceMustCountAsProgress() throws Exception {
        configureCadence(1);f.position=new Location(f.world,2.5,64,.5);
        begin(goal(1,RoutineGoal.Type.WORK,RoutineGoal.WalkMode.CYCLE,.2,point(80,0)));
        f.advance(1202);
        assertTrue(f.moves.size()>1100,"the fixture must actually be walking");
        assertTrue(f.position.getX()>14,"the NPC has made over eleven blocks of progress");
        assertFalse(f.service.status("thurg").contains("ruta inaccesible"),
                "actual continuous .01-block steps must refresh travelSince: "+f.service.status("thurg"));
    }

    @Test void sleepUsesItsOnlyLocallyAvailableSide() throws Exception {
        // East is the only clear approach to the bed at (0,64,0).
        for(int[] side:new int[][]{{0,1},{0,-1},{-1,0}})for(int y=64;y<=65;y++)solid(side[0],y,side[1]);
        f.position=new Location(f.world,5.5,64,.5);
        begin(goal(1,RoutineGoal.Type.SLEEP,RoutineGoal.WalkMode.CYCLE,2.4,point(0,0)));
        Location initial=f.position.clone();f.advance(60);
        RoutineGoal.Point expectedBed=point(0,0);
        verify(f.visuals).enter(eq(f.npc),any(),eq(expectedBed),eq(new Location(f.world,1.5,64,.5)),anyLong());
        assertTrue(f.service.status("thurg").contains("durmiendo"));f.assertWalkingSteps(initial);
    }

    @Test void sleepRetriesNativeRejectedSidesAndUsesTheReachableSide() throws Exception {
        f.position=new Location(f.world,-10.5,64,.5);
        when(f.pathfinder.findPath(any(Location.class))).thenAnswer(call->{
            Location target=call.getArgument(0);
            return target.getBlockX()==1 && target.getBlockZ()==0?straight(target):null;
        });
        begin(goal(1,RoutineGoal.Type.SLEEP,RoutineGoal.WalkMode.CYCLE,2.4,point(0,0)));
        Location initial=f.position.clone();f.advance(220);
        assertTrue(f.service.status("thurg").contains("durmiendo"),f.service.status("thurg"));
        verify(f.pathfinder).findPath(eq(new Location(f.world,1.5,64,.5)));
        f.assertWalkingSteps(initial);
    }

    @Test void danceReturnsToItsReservedSeatUsingRealControlledNavigation() throws Exception {
        YamlConfiguration npcConfig=new YamlConfiguration();
        npcConfig.set("npcs.thurg.location.world","world");npcConfig.set("npcs.thurg.trait.type","fiestero");
        f.npc=new ActiveNpc(NpcParser.parse(npcConfig).get("thurg"),f.position.clone(),f.entity,null);
        when(plugin.manager().activeNpcs()).thenReturn(List.of(f.npc));
        doAnswer(call->{
            Location approach=call.getArgument(3);
            assertTrue(f.position.distanceSquared(approach)<=.36,"return mounts only near its verified doorway");
            RoutineVisuals.Pose pose=new RoutineVisuals.Pose();pose.npc=f.npc;pose.exit=approach.clone();
            pose.seat=mock(org.bukkit.entity.ArmorStand.class);when(pose.seat.isValid()).thenReturn(true);
            when(f.entity.getVehicle()).thenReturn(pose.seat);return pose;
        }).when(f.visuals).enter(any(),any(),any(),any(),anyLong());
        begin(goal(1,RoutineGoal.Type.SIT,RoutineGoal.WalkMode.CYCLE,2.4,point(18,0)));
        DanceController dancers=mock(DanceController.class);
        when(dancers.start(eq(f.npc),any(),anyLong())).thenReturn(true);
        when(dancers.tick(eq(f.npc),anyLong(),anyInt(),anyDouble())).thenAnswer(call->{
            f.position=new Location(f.world,28.5,64,.5);return DanceController.Result.DANCING;
        });
        field(RoutineService.class,"dancers").set(f.service,dancers);
        f.advance(800);
        assertTrue(f.service.status("thurg").contains("bailando"));
        assertTrue(f.service.claimed(point(18,0)));
        when(dancers.tick(eq(f.npc),anyLong(),anyInt(),anyDouble())).thenReturn(DanceController.Result.FINISHED);
        f.moves.clear();Location initial=f.position.clone();f.advance(140);
        RoutineGoal.Point expectedSeat=point(18,0);
        verify(f.visuals,times(2)).enter(eq(f.npc),any(),eq(expectedSeat),any(),anyLong());
        assertTrue(f.service.status("thurg").contains("sentado"),f.service.status("thurg"));
        assertTrue(f.service.claimed(point(18,0)));f.assertWalkingSteps(initial);
    }
}
