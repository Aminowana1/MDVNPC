package com.mdvcraft.mdvnpc.routine;

import com.destroystokyo.paper.entity.Pathfinder;
import com.mdvcraft.mdvnpc.config.NpcParser;
import com.mdvcraft.mdvnpc.runtime.ActiveNpc;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Villager;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.VoxelShape;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Audit fixtures for the real RoutineTerrain and waypoint replay. Paper search is mocked:
 * these tests prove replay/collision behavior only, not live native route discovery.
 * Collision assertions independently examine all stored voxel boxes, including below-foot
 * blocks, so production's own clearance predicate cannot hide an incorrect traversal.
 */
class NavigationTerrainAuditTest {
    private static final List<BoundingBox> CUBE = List.of(new BoundingBox(0,0,0,1,1,1));
    private static final List<BoundingBox> WALL_NORTH_SOUTH = List.of(
            new BoundingBox(.25,0,.25,.75,1.5,.75),
            new BoundingBox(.3125,0,0,.6875,1.5,1));
    private record Cell(Block block, List<BoundingBox> local) {}
    private record Key(int x, int y, int z) {}
    private final Map<Key,Cell> cells = new HashMap<>();
    private World world;
    private Location position;
    private RoutineNavigator navigator;
    private RoutineTerrain terrain;
    private Pathfinder finder;
    private ActiveNpc npc;

    @BeforeEach void setup() {
        MockBukkit.mock();
        world=mock(World.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        when(world.getMinHeight()).thenReturn(-64);
        when(world.getMaxHeight()).thenReturn(320);
        when(world.isChunkLoaded(anyInt(),anyInt())).thenReturn(true);
        when(world.getBlockAt(anyInt(),anyInt(),anyInt())).thenAnswer(call -> {
            int x=call.getArgument(0), y=call.getArgument(1), z=call.getArgument(2);
            return cells.computeIfAbsent(new Key(x,y,z), ignored -> cell(x,y,z,
                    y==63 ? Material.STONE : Material.AIR, y==63 ? CUBE : List.of())).block();
        });
        DoorController doors=mock(DoorController.class);
        when(doors.openNear(any(),any(),anyLong())).thenReturn(true);
        terrain=new RoutineTerrain(world,doors);
        navigator=new RoutineNavigator(doors,(ignored,to) -> { position=to.clone(); return true; });
        finder=mock(Pathfinder.class);
        Villager entity=mock(Villager.class);
        when(entity.getWorld()).thenReturn(world);
        when(entity.isValid()).thenReturn(true);
        when(entity.isOnGround()).thenReturn(true);
        when(entity.getPathfinder()).thenReturn(finder);
        when(entity.getLocation()).thenAnswer(call -> position.clone());
        position=new Location(world,.5,64,.5);
        YamlConfiguration yaml=new YamlConfiguration();
        yaml.set("npcs.terrain.location.world","world");
        yaml.set("npcs.terrain.location.y",64);
        npc=new ActiveNpc(NpcParser.parse(yaml).get("terrain"),position.clone(),entity,null);
    }

    @AfterEach void cleanup() { navigator.clear(); MockBukkit.unmock(); }

    private Cell cell(int x,int y,int z,Material material,List<BoundingBox> boxes) {
        Block block=mock(Block.class);
        when(block.getType()).thenReturn(material);
        when(block.isPassable()).thenReturn(boxes.isEmpty());
        VoxelShape shape=mock(VoxelShape.class);
        when(shape.getBoundingBoxes()).thenReturn(boxes);
        when(block.getCollisionShape()).thenReturn(shape);
        when(block.getBoundingBox()).thenReturn(boxes.isEmpty() ? new BoundingBox(x,y,z,x,y,z)
                : new BoundingBox(x,y,z,x+1,y+1,z+1));
        return new Cell(block,boxes);
    }
    private void put(int x,int y,int z,Material material,List<BoundingBox> boxes) {
        cells.put(new Key(x,y,z),cell(x,y,z,material,boxes));
    }
    private void path(Location... points) { path(List.of(points)); }
    private void path(List<Location> points) {
        Pathfinder.PathResult result=mock(Pathfinder.PathResult.class);
        when(result.getPoints()).thenReturn(points);
        when(result.canReachFinalPoint()).thenReturn(true);
        when(finder.findPath(any(Location.class))).thenReturn(result);
    }
    private Location raw(int x,int y,int z) { return new Location(world,x,y,z); }

    private String physicalCollision(Location at) {
        BoundingBox body=new BoundingBox(at.getX()-.30,at.getY()+.015,at.getZ()-.30,
                at.getX()+.30,at.getY()+1.95,at.getZ()+.30);
        for(var entry:cells.entrySet()) for(BoundingBox local:entry.getValue().local()) {
            Key key=entry.getKey();
            if(local.clone().shift(key.x(),key.y(),key.z()).overlaps(body))
                return "body intersects "+entry.getValue().block().getType()+" at "+key+" from "+at;
        }
        return null;
    }
    private void assertStep(Location previous) {
        assertTrue(previous.distance(position)<=.240001,"all movement shares the configured speed allowance");
        assertTrue(terrain.fits(position.getX(),position.getY(),position.getZ(),false),"terrain rejects current body");
        assertNull(physicalCollision(position),() -> physicalCollision(position));
    }
    private List<Location> walk(Location destination) {
        List<Location> visited=new ArrayList<>();
        RoutineNavigator.Result result=RoutineNavigator.Result.MOVING;
        for(int tick=0;tick<600 && result!=RoutineNavigator.Result.ARRIVED;tick+=2) {
            Location previous=position.clone();
            result=navigator.move(npc,destination,2.4,tick,2);
            assertStep(previous);
            visited.add(position.clone());
        }
        assertEquals(RoutineNavigator.Result.ARRIVED,result,"a usable supplied route must actually finish; final position="+position);
        assertTrue(position.distance(destination)<.159,"arrival position is within the production tolerance");
        return visited;
    }

    @ParameterizedTest
    @EnumSource(value=Material.class,names={"STONE","GLASS","GLASS_PANE","COBBLESTONE_WALL"})
    void oneBlockCorridorBesideWallsOrWindowsActuallyFinishes(Material material) {
        List<BoundingBox> boxes=switch(material) {
            case GLASS_PANE -> List.of(new BoundingBox(0,0,.4375,1,1,.5625));
            case COBBLESTONE_WALL -> List.of(new BoundingBox(0,0,.3125,1,1.5,.6875));
            default -> CUBE;
        };
        for(int x=-1;x<=5;x++) for(int z:new int[]{-1,1}) for(int y=64;y<=65;y++)
            put(x,y,z,material,boxes);
        path(raw(0,64,0),raw(1,64,0),raw(2,64,0),raw(3,64,0),raw(4,64,0));
        List<Location> visited=walk(new Location(world,4.5,64,.5));
        assertTrue(visited.stream().allMatch(at -> Math.abs(at.getY()-64)<1e-6));
        verify(finder,times(1)).findPath(any(Location.class));
    }

    @Test void solidWindowIsAvoidedByASuppliedDetour() {
        for(int y=64;y<=65;y++) put(1,y,0,Material.GLASS_PANE,
                List.of(new BoundingBox(.4375,0,0,.5625,1,1)));
        path(raw(0,64,0),raw(0,64,1),raw(1,64,1),raw(2,64,1),raw(2,64,0));
        List<Location> visited=walk(new Location(world,2.5,64,.5));
        assertTrue(visited.stream().anyMatch(at -> at.getZ()>1.2));
    }

    @Test void aWindowAcrossTheOnlySuppliedRouteCannotBeTraversed() {
        for(int y=64;y<=65;y++) put(1,y,0,Material.GLASS_PANE,
                List.of(new BoundingBox(.4375,0,0,.5625,1,1)));
        path(raw(0,64,0),raw(1,64,0),raw(2,64,0));
        for(int tick=0;tick<60;tick+=2) {
            Location previous=position.clone();
            assertNotEquals(RoutineNavigator.Result.ARRIVED,navigator.move(npc,new Location(world,2.5,64,.5),2.4,tick,2));
            assertStep(previous);
        }
        assertEquals(.5,position.getX(),1e-6);
    }

    @Test void aNativeDiagonalBetweenSlabsAvoidsTheHigherLateralBlock() {
        for(int x=-1;x<=3;x++) for(int z=-1;z<=2;z++)
            put(x,64,z,Material.STONE_SLAB,List.of(new BoundingBox(0,0,0,1,.5,1)));
        put(1,64,0,Material.STONE,CUBE);
        position=new Location(world,.5,64.5,.5);
        path(raw(0,65,0),raw(1,65,1),raw(2,65,1));
        List<Location> visited=walk(new Location(world,2.5,64.5,1.5));
        assertTrue(visited.stream().allMatch(at -> Math.abs(at.getY()-64.5)<1e-6));
    }

    @ParameterizedTest
    @EnumSource(value=Material.class,names={"DIRT_PATH","MUD","SOUL_SAND"})
    void loweredSurfacesAreReachedAndLeftAtTheirActualHeight(Material material) {
        double top=material==Material.DIRT_PATH ? .9375 : .875;
        put(1,63,0,material,List.of(new BoundingBox(0,0,0,1,top,1)));
        path(raw(0,64,0),raw(1,64,0),raw(2,64,0));
        List<Location> visited=walk(new Location(world,2.5,64,.5));
        assertTrue(visited.stream().anyMatch(at -> Math.abs(at.getY()-(63+top))<1e-6));
        assertEquals(64,position.getY(),1e-6);
        verify(finder,times(1)).findPath(any(Location.class));
    }

    @Test void carpetIsEnteredAndLeftAtItsThinPhysicalHeight() {
        put(1,64,0,Material.WHITE_CARPET,List.of(new BoundingBox(0,0,0,1,.0625,1)));
        path(raw(0,64,0),raw(1,65,0),raw(2,64,0));
        List<Location> visited=walk(new Location(world,2.5,64,.5));
        assertTrue(visited.stream().anyMatch(at -> Math.abs(at.getY()-64.0625)<1e-6));
        assertTrue(visited.stream().allMatch(at -> at.getY()>=64 && at.getY()<=64.062501));
    }

    @Test void slabThenFullBlockUsesBothPhysicalLevelsWithoutSpeedSnaps() {
        put(1,64,0,Material.STONE_SLAB,List.of(new BoundingBox(0,0,0,1,.5,1)));
        put(2,64,0,Material.STONE,CUBE);
        path(raw(0,64,0),raw(1,65,0),raw(2,65,0));
        List<Location> visited=walk(new Location(world,2.5,65,.5));
        assertTrue(visited.stream().anyMatch(at -> Math.abs(at.getY()-64.5)<1e-6));
        assertEquals(65,position.getY(),1e-6);
    }

    @Test void topSlabCanBeClimbedAndDescendedAsAFullHeightSupport() {
        put(1,64,0,Material.STONE_SLAB,List.of(new BoundingBox(0,.5,0,1,1,1)));
        path(raw(0,64,0),raw(1,65,0),raw(2,64,0));
        List<Location> visited=walk(new Location(world,2.5,64,.5));
        assertTrue(visited.stream().anyMatch(at -> Math.abs(at.getY()-65)<1e-6));
    }

    static Stream<Arguments> stairCases() {
        List<Arguments> result=new ArrayList<>();
        for(BlockFace direction:List.of(BlockFace.EAST,BlockFace.SOUTH,BlockFace.WEST,BlockFace.NORTH))
            for(String shape:List.of("straight","inner-right","outer-right"))
                for(boolean down:new boolean[]{false,true}) result.add(Arguments.of(direction,shape,down));
        return result.stream();
    }
    private int[] rotate(int x,int z,BlockFace direction) {
        return switch(direction) {
            case EAST -> new int[]{x,z};
            case SOUTH -> new int[]{-z,x};
            case WEST -> new int[]{-x,-z};
            case NORTH -> new int[]{z,-x};
            default -> throw new AssertionError(direction);
        };
    }
    private BoundingBox rotate(BoundingBox box,BlockFace direction) {
        return switch(direction) {
            case EAST -> box;
            case SOUTH -> new BoundingBox(1-box.getMaxZ(),box.getMinY(),box.getMinX(),1-box.getMinZ(),box.getMaxY(),box.getMaxX());
            case WEST -> new BoundingBox(1-box.getMaxX(),box.getMinY(),1-box.getMaxZ(),1-box.getMinX(),box.getMaxY(),1-box.getMinZ());
            case NORTH -> new BoundingBox(box.getMinZ(),box.getMinY(),1-box.getMaxX(),box.getMaxZ(),box.getMaxY(),1-box.getMinX());
            default -> throw new AssertionError(direction);
        };
    }
    @ParameterizedTest @MethodSource("stairCases")
    void stairsFollowTheirVoxelsInEveryOrientationAndBothDirections(BlockFace direction,String shape,boolean down) {
        List<BoundingBox> boxes=new ArrayList<>(List.of(new BoundingBox(0,0,0,1,.5,1)));
        if(shape.equals("outer-right")) boxes.add(new BoundingBox(.5,.5,.5,1,1,1));
        else boxes.add(new BoundingBox(.5,.5,0,1,1,1));
        if(shape.equals("inner-right")) boxes.add(new BoundingBox(0,.5,.5,.5,1,1));
        int[] one=rotate(1,0,direction), two=rotate(2,0,direction);
        put(one[0],64,one[1],Material.OAK_STAIRS,boxes.stream().map(box -> rotate(box,direction)).toList());
        put(two[0],64,two[1],Material.STONE,CUBE);
        List<Location> points=new ArrayList<>(List.of(raw(0,64,0),raw(one[0],65,one[1]),raw(two[0],65,two[1])));
        Location destination=new Location(world,two[0]+.5,65,two[1]+.5);
        if(down) {
            Collections.reverse(points);
            position=destination.clone();
            destination=new Location(world,.5,64,.5);
        }
        path(points);
        List<Location> visited=walk(destination);
        assertTrue(visited.stream().anyMatch(at -> at.getY()>64.4 && at.getY()<64.7),"lower tread must be used");
        verify(finder,times(1)).findPath(any(Location.class));
    }

    @Test void oneBlockDepressionIsDescendedAndClimbedWithoutSkippingTheFloor() {
        put(1,63,0,Material.AIR,List.of());
        put(1,62,0,Material.STONE,CUBE);
        path(raw(0,64,0),raw(1,63,0),raw(2,64,0),raw(3,64,0));
        List<Location> visited=walk(new Location(world,3.5,64,.5));
        assertTrue(visited.stream().anyMatch(at -> Math.abs(at.getY()-63)<1e-6));
        assertTrue(visited.stream().allMatch(at -> at.getY()>=63 && at.getY()<=64));
    }

    @Test void unsupportedTwoBlockHoleCannotBeCrossedBySkippingAWaypoint() {
        put(1,63,0,Material.AIR,List.of());
        put(1,62,0,Material.AIR,List.of());
        put(1,61,0,Material.STONE,CUBE);
        path(raw(0,64,0),raw(2,64,0));
        for(int tick=0;tick<100;tick+=2) {
            Location previous=position.clone();
            assertNotEquals(RoutineNavigator.Result.ARRIVED,navigator.move(npc,new Location(world,2.5,64,.5),2.4,tick,2));
            assertStep(previous);
            assertTrue(position.getX()<1.11,"cannot progress beyond all nearby support");
        }
    }

    @Test void lowCeilingPreventsAClimbInsteadOfTeleportingThroughIt() {
        put(1,64,0,Material.STONE_SLAB,List.of(new BoundingBox(0,0,0,1,.5,1)));
        put(1,66,0,Material.STONE,CUBE);
        path(raw(0,64,0),raw(1,65,0));
        Location previous=position.clone();
        assertEquals(RoutineNavigator.Result.WAITING,navigator.move(npc,new Location(world,1.5,64.5,.5),2.4,0,2));
        assertStep(previous);
        assertEquals(previous,position);
    }

    @ParameterizedTest
    @EnumSource(value=Material.class,names={"COBBLESTONE_WALL","OAK_FENCE"})
    void belowFeetWallOrFenceProtrudingIntoTheBodyMustBeDetected(Material material) {
        // Vanilla wall collision is 24/16 high, fence likewise. A lateral post below the
        // walking floor therefore extends .5 into the NPC despite having its block at Y=63.
        List<BoundingBox> boxes=material==Material.OAK_FENCE
                ? List.of(new BoundingBox(.375,0,0,.625,1.5,1)) : WALL_NORTH_SOUTH;
        put(1,63,0,material,boxes);
        Location at=new Location(world,1.1,64,1.1);
        assertNotNull(physicalCollision(at),"fixture overlaps only the shoulder, not the support probe");
        assertEquals(64,terrain.supportHeight(at.getX(),at.getY(),at.getZ()),1e-6);
        assertFalse(terrain.fits(at.getX(),at.getY(),at.getZ(),false),"collision extends above its source block");
    }

    @Test void diagonalReplayMayNotClipTheTopOfABelowFloorWall() {
        put(1,63,0,Material.COBBLESTONE_WALL,WALL_NORTH_SOUTH);
        path(raw(0,64,0),raw(1,64,1),raw(2,64,1));
        walk(new Location(world,2.5,64,1.5));
    }
}
