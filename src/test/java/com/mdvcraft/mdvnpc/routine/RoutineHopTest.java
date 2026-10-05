package com.mdvcraft.mdvnpc.routine;

import com.destroystokyo.paper.entity.Pathfinder;
import com.mdvcraft.mdvnpc.config.NpcParser;
import com.mdvcraft.mdvnpc.runtime.ActiveNpc;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Door;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Villager;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.VoxelShape;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import java.util.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Recovery execution and independent voxel clearance; native Paper search remains mocked. */
class RoutineHopTest {
    private static final List<BoundingBox> CUBE=List.of(new BoundingBox(0,0,0,1,1,1));
    private record Key(int x,int y,int z) {}
    private record Cell(Block block,Supplier<List<BoundingBox>> boxes) {}
    private final Map<Key,Cell> cells=new HashMap<>();
    private final List<Key> reads=new ArrayList<>();
    private final List<Location> teleports=new ArrayList<>();
    private World world;
    private DoorController doors;
    private RoutineNavigator navigator;
    private ActiveNpc npc;
    private Pathfinder finder;
    private Location position;
    private int teleportMode;
    private boolean doorOpen,allowDoor=true,allowOpening=true;
    private int openingAttempts;

    @BeforeEach void setup() {
        MockBukkit.mock();world=mock(World.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        when(world.getMinHeight()).thenReturn(-64);when(world.getMaxHeight()).thenReturn(320);
        when(world.isChunkLoaded(anyInt(),anyInt())).thenReturn(true);
        when(world.getBlockAt(anyInt(),anyInt(),anyInt())).thenAnswer(call->{
            Key key=new Key(call.getArgument(0),call.getArgument(1),call.getArgument(2));reads.add(key);
            return cells.computeIfAbsent(key,ignored->cell(key,key.y()==63?Material.STONE:Material.AIR,
                    ()->key.y()==63?CUBE:List.of(),null)).block();
        });
        doors=mock(DoorController.class);when(doors.openNear(any(),any(),anyLong())).thenReturn(true);
        when(doors.canOpen(any())).thenAnswer(call->allowDoor);
        position=new Location(world,.5,64,.5,-90,75);
        Villager entity=mock(Villager.class);finder=mock(Pathfinder.class);
        when(entity.getWorld()).thenReturn(world);when(entity.isValid()).thenReturn(true);
        when(entity.getLocation()).thenAnswer(call->position.clone());when(entity.getPathfinder()).thenReturn(finder);
        when(entity.isOnGround()).thenReturn(true);
        YamlConfiguration yaml=new YamlConfiguration();yaml.set("npcs.hop.location.world","world");yaml.set("npcs.hop.location.y",64);
        npc=new ActiveNpc(NpcParser.parse(yaml).get("hop"),position.clone(),entity,null);
        navigator=new RoutineNavigator(doors,(ignored,to)->{
            if(teleportMode!=1 && teleportMode!=2) {position=to.clone();teleports.add(to.clone());}
            if(teleportMode==4 && Math.abs(to.getY()-64)<1e-6 && to.getX()>1.4)position.add(0,.04,0);
            return teleportMode!=2 && teleportMode!=3;
        });
    }
    @AfterEach void cleanup() {navigator.clear();MockBukkit.unmock();}

    private Cell cell(Key key,Material material,Supplier<List<BoundingBox>> boxes,BlockData data) {
        Block block=mock(Block.class);when(block.getType()).thenReturn(material);
        when(block.getBlockData()).thenReturn(data);
        when(block.getWorld()).thenReturn(world);when(block.getX()).thenReturn(key.x());
        when(block.getY()).thenReturn(key.y());when(block.getZ()).thenReturn(key.z());
        when(block.getLocation()).thenReturn(new Location(world,key.x(),key.y(),key.z()));
        VoxelShape shape=mock(VoxelShape.class);when(shape.getBoundingBoxes()).thenAnswer(call->boxes.get());
        when(block.getCollisionShape()).thenReturn(shape);return new Cell(block,boxes);
    }
    private void put(int x,int y,int z,Material material,List<BoundingBox> boxes) {
        Key key=new Key(x,y,z);cells.put(key,cell(key,material,()->boxes,null));
    }
    private void door() {
        for(int y=64;y<=65;y++) {
            Key key=new Key(1,y,0);Door data=mock(Door.class);when(data.isOpen()).thenAnswer(call->doorOpen);
            cells.put(key,cell(key,Material.OAK_DOOR,()->doorOpen
                    ?List.of(new BoundingBox(0,0,0,1,1,.1875))
                    :List.of(new BoundingBox(0,0,0,.1875,1,1)),data));
        }
        when(doors.openNear(any(),any(),anyLong())).thenAnswer(call->{
            Location at=call.getArgument(1);
            if(at.getX()+.32>=1 && at.getX()-.32<2 && !doorOpen) {
                openingAttempts++;if(!allowOpening || !allowDoor)return false;doorOpen=true;
            }
            return true;
        });
    }
    private void assertBodyClear(Location at) {
        BoundingBox body=new BoundingBox(at.getX()-.30,at.getY()+.015,at.getZ()-.30,
                at.getX()+.30,at.getY()+1.95,at.getZ()+.30);
        for(var entry:cells.entrySet()) for(BoundingBox local:entry.getValue().boxes().get()) {
            Key key=entry.getKey();
            assertFalse(local.clone().shift(key.x(),key.y(),key.z()).overlaps(body),
                    ()->"body intersects "+entry.getValue().block().getType()+" at "+key+" from "+at);
        }
    }
    private List<Location> finishHop(long startTick) {
        List<Location> frames=new ArrayList<>();RoutineNavigator.Result result=RoutineNavigator.Result.MOVING;
        for(long tick=startTick+2;tick<=startTick+200 && result==RoutineNavigator.Result.MOVING;tick+=2) {
            result=navigator.advanceHop(npc,tick);assertBodyClear(position);frames.add(position.clone());
        }
        assertEquals(RoutineNavigator.Result.ARRIVED,result);assertFalse(navigator.activeHop("hop"));
        assertFalse(navigator.controlsVerticalStep("hop"));return frames;
    }
    private void nativeRoute() {
        Pathfinder.PathResult path=mock(Pathfinder.PathResult.class);
        when(path.getPoints()).thenReturn(List.of(new Location(world,0,64,0),new Location(world,3,64,0)));
        when(path.canReachFinalPoint()).thenReturn(true);when(finder.findPath(any(Location.class))).thenReturn(path);
        assertEquals(RoutineNavigator.Result.MOVING,navigator.move(npc,new Location(world,3.5,64,.5),2.4,0,2));
        assertEquals(1,navigator.activeRoutes());
    }

    @Test void defaultHopIsAnAnimatedFacingArcWithExactLandingAndNoBrainWakeup() {
        Location start=position.clone();assertTrue(navigator.startHop(npc,.6,1,0));
        assertEquals(start,position,"preflight must not teleport");assertTrue(navigator.controlsVerticalStep("hop"));
        List<Location> frames=finishHop(0);assertTrue(frames.size()>=6);
        assertTrue(frames.getFirst().getX()<1.0,"the first frame must not cover the entire forward distance");
        assertEquals(64.6,frames.stream().mapToDouble(Location::getY).max().orElseThrow(),1e-6);
        assertEquals(1.5,position.getX(),1e-6);assertEquals(.5,position.getZ(),1e-6);assertEquals(64,position.getY(),1e-6);
        assertTrue(frames.stream().allMatch(at->Math.abs(at.getZ()-.5)<1e-6),"pitch must not tilt the forward motion");
        verify(npc.entity(),never()).setAI(anyBoolean());verify(npc.entity(),never()).setAware(anyBoolean());
        verify(finder,never()).findPath(any(Location.class));
    }
    @Test void allFourYawDirectionsUseTheCurrentFacing() {
        for(float yaw:new float[]{0,90,180,270}) {
            position=new Location(world,.5,64,.5,yaw,-85);Location start=position.clone();
            assertTrue(navigator.startHop(npc,.6,1,0));finishHop(0);
            assertEquals(start.getX()-Math.sin(Math.toRadians(yaw)),position.getX(),1e-6);
            assertEquals(start.getZ()+Math.cos(Math.toRadians(yaw)),position.getZ(),1e-6);
        }
    }
    @Test void aLateUpdateCannotTurnTheAnimationIntoOneLongTeleport() {
        assertTrue(navigator.startHop(npc,.6,1,0));
        assertEquals(RoutineNavigator.Result.MOVING,navigator.advanceHop(npc,100));
        assertTrue(navigator.activeHop("hop"));assertTrue(position.getX()<1);
        assertTrue(position.getY()>64 && position.getY()<64.6);assertEquals(1,teleports.size());
    }
    @Test void configuredMaximumAndMinimumHopDimensionsAreHonored() {
        assertTrue(navigator.startHop(npc,4,4,0));List<Location> frames=finishHop(0);
        assertTrue(frames.size()>=40);assertEquals(68,frames.stream().mapToDouble(Location::getY).max().orElseThrow(),1e-6);
        assertEquals(4.5,position.getX(),1e-6);
        position=new Location(world,.5,64,.5,-90,0);assertTrue(navigator.startHop(npc,.05,.05,0));finishHop(0);
        assertEquals(.55,position.getX(),1e-6);
    }
    @Test void aFractionalMudLandingDoesNotIncreaseTheConfiguredApexOrHover() {
        put(1,63,0,Material.MUD,List.of(new BoundingBox(0,0,0,1,.875,1)));
        assertTrue(navigator.startHop(npc,.6,1,0));List<Location> frames=finishHop(0);
        assertTrue(frames.stream().allMatch(at->at.getY()<=64.600001));
        assertTrue(frames.stream().anyMatch(at->at.getY()>64.57));assertEquals(63.875,position.getY(),1e-6);
    }
    @Test void aWallOrLowCeilingRejectsTheCompleteArcWithoutMotion() {
        put(1,64,0,Material.STONE,CUBE);put(1,65,0,Material.STONE,CUBE);
        assertFalse(navigator.startHop(npc,.6,1,0));assertTrue(teleports.isEmpty());
        cells.clear();put(0,66,0,Material.STONE,CUBE);
        assertFalse(navigator.startHop(npc,.6,1,2));assertTrue(teleports.isEmpty());
        assertEquals(RoutineNavigator.HopFailure.BLOCKED,navigator.hopFailure("hop"));
    }
    @Test void evenAHighConfiguredHopCannotCrossAFenceOrWallBelowItsArc() {
        for(Material barrier:List.of(Material.OAK_FENCE,Material.COBBLESTONE_WALL)) {
            cells.clear();put(1,63,0,barrier,List.of(new BoundingBox(0,0,0,1,1.5,1)));
            assertFalse(navigator.startHop(npc,4,2,0),"the projected path must reject "+barrier);
            assertTrue(teleports.isEmpty());
        }
    }
    @Test void aGapOrHazardAtTheLandingIsRejected() {
        put(1,63,0,Material.AIR,List.of());put(1,62,0,Material.AIR,List.of());
        assertFalse(navigator.startHop(npc,.6,1,0));
        put(1,63,0,Material.MAGMA_BLOCK,CUBE);
        assertFalse(navigator.startHop(npc,.6,1,2));assertTrue(teleports.isEmpty());
    }
    @Test void anUnloadedEndpointIsNotReadOrLoaded() {
        position=new Location(world,15.5,64,.5,-90,0);when(world.isChunkLoaded(1,0)).thenReturn(false);
        assertFalse(navigator.startHop(npc,.6,1,0));assertTrue(teleports.isEmpty());
        assertTrue(reads.stream().noneMatch(key->key.x()>=16),"unloaded destination blocks must not be requested");
    }
    @Test void anAuthorizedClosedDoorOpensBeforeTheAnimationPassesItsPanel() {
        door();assertTrue(navigator.startHop(npc,.6,2,0));finishHop(0);
        assertTrue(doorOpen);assertEquals(1,openingAttempts);assertEquals(2.5,position.getX(),1e-6);
    }
    @Test void deniedAndCancelledDoorOpeningsCannotMoveTheNpcThroughThePanel() {
        door();allowDoor=false;assertFalse(navigator.startHop(npc,.6,2,0));assertEquals(0,openingAttempts);
        allowDoor=true;allowOpening=false;assertTrue(navigator.startHop(npc,.6,2,2));
        Location before=position.clone();assertEquals(RoutineNavigator.Result.WAITING,navigator.advanceHop(npc,4));
        assertEquals(before,position);assertEquals(1,openingAttempts);assertFalse(doorOpen);
        assertEquals(RoutineNavigator.HopFailure.BLOCKED,navigator.hopFailure("hop"));
    }
    @Test void aNoOpFirstFramePreservesTheNativeRouteAndReportsNoMotion() {
        nativeRoute();Location before=position.clone();assertTrue(navigator.startHop(npc,.6,1,2));teleportMode=1;
        assertEquals(RoutineNavigator.Result.WAITING,navigator.advanceHop(npc,4));assertEquals(before,position);
        assertEquals(RoutineNavigator.HopFailure.NO_MOTION,navigator.hopFailure("hop"));assertEquals(1,navigator.activeRoutes());
        assertFalse(navigator.controlsVerticalStep("hop"));teleportMode=0;
        assertEquals(RoutineNavigator.Result.MOVING,navigator.move(npc,new Location(world,3.5,64,.5),2.4,6,2));
        verify(finder,times(1)).findPath(any(Location.class));
    }
    @Test void actualMotionInvalidatesTheRouteEvenIfTheTeleportCallbackRejectsIt() {
        nativeRoute();assertTrue(navigator.startHop(npc,.6,1,2));teleportMode=3;
        assertEquals(RoutineNavigator.Result.WAITING,navigator.advanceHop(npc,4));assertEquals(0,navigator.activeRoutes());
        assertEquals(RoutineNavigator.HopFailure.TELEPORT_REJECTED,navigator.hopFailure("hop"));
        assertFalse(navigator.activeHop("hop"));assertFalse(navigator.controlsVerticalStep("hop"));
    }
    @Test void changedLandingOrExternalDisplacementAbortsAtTheLastSafeFrame() {
        assertTrue(navigator.startHop(npc,.6,1,0));assertEquals(RoutineNavigator.Result.MOVING,navigator.advanceHop(npc,2));
        Location last=position.clone();put(1,63,0,Material.AIR,List.of());put(1,62,0,Material.AIR,List.of());
        assertEquals(RoutineNavigator.Result.WAITING,navigator.advanceHop(npc,4));assertEquals(last,position);
        assertEquals(RoutineNavigator.HopFailure.BLOCKED,navigator.hopFailure("hop"));
        cells.clear();position=new Location(world,.5,64,.5,-90,0);assertTrue(navigator.startHop(npc,.6,1,6));
        position.add(2,0,0);Location displaced=position.clone();
        assertEquals(RoutineNavigator.Result.WAITING,navigator.advanceHop(npc,8));assertEquals(displaced,position);
        assertEquals(RoutineNavigator.HopFailure.DISPLACED,navigator.hopFailure("hop"));
    }
    @Test void cancellationAndClearReleaseOnlyTheRequestedOwnership() {
        nativeRoute();assertTrue(navigator.startHop(npc,.6,1,2));navigator.cancelHop("hop");
        assertFalse(navigator.activeHop("hop"));assertEquals(1,navigator.activeRoutes());
        assertTrue(navigator.startHop(npc,.6,1,4));navigator.cancel("hop");
        assertFalse(navigator.activeHop("hop"));assertEquals(0,navigator.activeRoutes());
        assertTrue(navigator.startHop(npc,.6,1,6));navigator.clear();
        assertFalse(navigator.activeHop("hop"));assertFalse(navigator.controlsVerticalStep("hop"));
    }
    @Test void invalidDimensionsAndUngroundedStartsNeverEnterTheAnimation() {
        assertFalse(navigator.startHop(npc,Double.NaN,1,0));assertFalse(navigator.startHop(npc,.6,Double.POSITIVE_INFINITY,0));
        assertFalse(navigator.startHop(npc,.04,1,0));assertFalse(navigator.startHop(npc,.6,4.01,0));
        position.setY(64.2);assertFalse(navigator.startHop(npc,.6,1,0));
        assertEquals(RoutineNavigator.HopFailure.INVALID_START,navigator.hopFailure("hop"));assertTrue(teleports.isEmpty());
    }
    @Test void aTeleportAdjustedFinalFrameMustHaveActualFloorSupportBeforeFinishing() {
        assertTrue(navigator.startHop(npc,.6,1,0));teleportMode=4;
        RoutineNavigator.Result result=RoutineNavigator.Result.MOVING;
        for(long tick=2;tick<=40 && result==RoutineNavigator.Result.MOVING;tick+=2)
            result=navigator.advanceHop(npc,tick);
        assertEquals(RoutineNavigator.Result.WAITING,result);
        assertEquals(64.04,position.getY(),1e-6);
        assertEquals(RoutineNavigator.HopFailure.DISPLACED,navigator.hopFailure("hop"));
        assertFalse(navigator.activeHop("hop"));assertFalse(navigator.controlsVerticalStep("hop"));
    }
    @Test void completingAHopThroughMoveDoesNotReportArrivalAtAFartherGoal() {
        Location goal=new Location(world,10.5,64,.5);assertTrue(navigator.startHop(npc,.6,1,0));
        RoutineNavigator.Result result=RoutineNavigator.Result.MOVING;
        for(long tick=2;tick<=40 && navigator.activeHop("hop");tick+=2) {
            result=navigator.move(npc,goal,2.4,tick,2);
            assertNotEquals(RoutineNavigator.Result.ARRIVED,result,"hop completion cannot finish the routine's longer trip");
        }
        assertFalse(navigator.activeHop("hop"));assertEquals(RoutineNavigator.Result.MOVING,result);
        assertEquals(1.5,position.getX(),1e-6);verify(finder,never()).findPath(any(Location.class));
    }
    @Test void aConfiguredTwoBlockWalkingClimbUsesItsOriginalGroundToRejectAFence() {
        navigator.configureHeights(2,2.3,1.01);
        for(int x=1;x<=2;x++)put(x,65,0,Material.STONE,CUBE);
        Pathfinder.PathResult path=mock(Pathfinder.PathResult.class);
        when(path.getPoints()).thenReturn(List.of(new Location(world,0,64,0),new Location(world,1,66,0),new Location(world,2,66,0)));
        when(path.canReachFinalPoint()).thenReturn(true);when(finder.findPath(any(Location.class))).thenReturn(path);
        Location goal=new Location(world,2.5,66,.5);
        RoutineNavigator.Result result=RoutineNavigator.Result.MOVING;
        for(long tick=0;tick<200 && result!=RoutineNavigator.Result.ARRIVED;tick+=2) {
            result=navigator.move(npc,goal,2.4,tick,2);assertBodyClear(position);
        }
        assertEquals(RoutineNavigator.Result.ARRIVED,result,"the paired ordinary high floor remains traversable");
        navigator.clear();position=new Location(world,.5,64,.5,-90,0);
        put(1,64,0,Material.OAK_FENCE,List.of(new BoundingBox(0,0,0,1,1.5,1)));
        for(long tick=0;tick<100;tick+=2) {
            assertNotEquals(RoutineNavigator.Result.ARRIVED,navigator.move(npc,goal,2.4,tick,2));
            assertEquals(64,position.getY(),1e-6,"a high floor above the fence must not trigger a forbidden lift");
            assertTrue(position.getX()<.700001);assertBodyClear(position);
        }
    }
}
