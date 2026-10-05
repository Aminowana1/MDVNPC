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


    @Test void oneAndHalfBlockRiseIncludingSlabIsTraversedWithoutReplanning() {
        // 64.0 -> full block at y=64 + bottom slab at y=65 = 65.5 (exactly +1.5).
        for(int x=1;x<=2;x++) {
            put(x,64,0,Material.STONE,CUBE);
            put(x,65,0,Material.STONE_SLAB,List.of(new BoundingBox(0,0,0,1,.5,1)));
        }
        path(raw(0,64,0),raw(1,66,0),raw(2,66,0));
        List<Location> visited=walk(new Location(world,2.5,65.5,.5));
        assertTrue(visited.stream().anyMatch(at -> Math.abs(at.getY()-65.5)<1e-6));
        verify(finder,times(1)).findPath(any(Location.class));
    }

    @Test void oneBlockRiseWithCarpetOnTopUsesTheCarpetPhysicalHeight() {
        for(int x=1;x<=2;x++) {
            put(x,64,0,Material.STONE,CUBE);
            put(x,65,0,Material.WHITE_CARPET,List.of(new BoundingBox(0,0,0,1,.0625,1)));
        }
        path(raw(0,64,0),raw(1,66,0),raw(2,66,0));
        List<Location> visited=walk(new Location(world,2.5,65.0625,.5));
        assertTrue(visited.stream().anyMatch(at -> Math.abs(at.getY()-65.0625)<1e-6));
        verify(finder,times(1)).findPath(any(Location.class));
    }

    @Test void twoAndQuarterBlockDropFollowsLowerPaperWaypointAndKeepsTheRoute() {
        // Start on a 65.5 platform, land at 63.25: a safe 2.25-block descent.
        for(int x=-1;x<=0;x++) {
            put(x,64,0,Material.STONE,CUBE);
            put(x,65,0,Material.STONE_SLAB,List.of(new BoundingBox(0,0,0,1,.5,1)));
        }
        for(int x=1;x<=3;x++) {
            put(x,63,0,Material.STONE_SLAB,List.of(new BoundingBox(0,0,0,1,.25,1)));
        }
        position=new Location(world,.5,65.5,.5);
        path(raw(0,66,0),raw(1,64,0),raw(2,64,0),raw(3,64,0));
        List<Location> visited=walk(new Location(world,3.5,63.25,.5));
        assertTrue(visited.stream().anyMatch(at -> at.getY()<64.0));
        assertEquals(63.25,position.getY(),1e-6);
        verify(finder,times(1)).findPath(any(Location.class));
    }

    @Test void twoBlockPitIsNotEnteredWhenPaperDidNotAnnounceADescent() {
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

    static Stream<Arguments> mixedSurfaceCases() {
        return Stream.of(BlockFace.EAST,BlockFace.SOUTH,BlockFace.WEST,BlockFace.NORTH)
                .flatMap(direction -> Stream.of(false,true).map(reverse -> Arguments.of(direction,reverse)));
    }

    static Stream<Arguments> surroundedStairCases() {
        return Stream.of(BlockFace.EAST,BlockFace.SOUTH,BlockFace.WEST,BlockFace.NORTH)
                .flatMap(direction -> Stream.of(false,true).flatMap(reverse ->
                        Stream.of("straight","inner-right","outer-right")
                                .map(shape -> Arguments.of(direction,reverse,shape))));
    }

    /** An actual replay, including collision checks independent of RoutineTerrain, through
     * fractional floors in a one-block lane. The stair has slabs on both sides. */
    @ParameterizedTest @MethodSource("surroundedStairCases")
    void mixedStairsSurroundedBySlabsAndCarpetsFinishInBothDirections(BlockFace direction,boolean reverse,String shape) {
        List<BoundingBox> half=List.of(new BoundingBox(0,0,0,1,.5,1));
        List<BoundingBox> stair=new ArrayList<>(half);
        stair.add(shape.equals("outer-right")?new BoundingBox(.5,.5,.5,1,1,1):new BoundingBox(.5,.5,0,1,1,1));
        if(shape.equals("inner-right"))stair.add(new BoundingBox(0,.5,.5,.5,1,1));
        double[] heights={64.5,65,65,65.0625,65.5,65,64.875,64.875,64.9375,65,64.5};
        for(int i=0;i<heights.length;i++) {
            int[] tile=rotate(i,0,direction);
            Material material;
            List<BoundingBox> boxes;
            if(i==0 || i==10) {material=Material.STONE_SLAB;boxes=half;}
            else if(i==1) {material=Material.OAK_STAIRS;boxes=stair;}
            else if(i==5) {material=Material.STONE_SLAB;boxes=List.of(new BoundingBox(0,.5,0,1,1,1));}
            else if(i==6 || i==7) {material=i==6?Material.MUD:Material.SOUL_SAND;boxes=List.of(new BoundingBox(0,0,0,1,.875,1));}
            else if(i==8) {material=Material.DIRT_PATH;boxes=List.of(new BoundingBox(0,0,0,1,.9375,1));}
            else {material=Material.STONE;boxes=CUBE;}
            put(tile[0],64,tile[1],material,boxes.stream().map(box -> rotate(box,direction)).toList());
            if(i==3)put(tile[0],65,tile[1],Material.WHITE_CARPET,List.of(new BoundingBox(0,0,0,1,.0625,1)));
            if(i==4)put(tile[0],65,tile[1],Material.STONE_SLAB,half);
            for(int side:new int[]{-1,1}) {
                int[] adjacent=rotate(i,side,direction);
                put(adjacent[0],64,adjacent[1],Material.STONE_SLAB,half);
            }
        }
        List<Location> points=new ArrayList<>();
        for(int i=0;i<heights.length;i++) {
            int[] tile=rotate(i,0,direction);
            points.add(raw(tile[0],(int)Math.ceil(heights[i]),tile[1]));
        }
        if(reverse)Collections.reverse(points);
        int start=reverse?heights.length-1:0, finish=reverse?0:heights.length-1;
        int[] first=rotate(start,0,direction),last=rotate(finish,0,direction);
        position=new Location(world,first[0]+.5,heights[start],first[1]+.5);
        path(points);
        List<Location> visited=walk(new Location(world,last[0]+.5,heights[finish],last[1]+.5));
        assertTrue(visited.stream().anyMatch(at -> Math.abs(at.getY()-64.875)<1e-6),"mud/soul sand are actually visited");
        assertTrue(visited.stream().anyMatch(at -> Math.abs(at.getY()-65.0625)<1e-6),"carpet physical surface is visited");
        verify(finder,times(1)).findPath(any(Location.class));
    }

    @ParameterizedTest @MethodSource("mixedSurfaceCases")
    void repeatedFractionalBumpsOmittedBetweenNativeNodesDoNotCreateABackAndForthLoop(BlockFace direction,boolean reverse) {
        // Two consecutive mud cells leave enough room for the entire body to clear both
        // neighbouring ledges; a one-cell dip may legitimately be bridged during replay.
        double[] heights={64,64.5,64,64.0625,63.875,63.875,64,64.5,64};
        for(int i=1;i<heights.length-1;i++) {
            int[] tile=rotate(i,0,direction);
            if(heights[i]==64.5)put(tile[0],64,tile[1],Material.STONE_SLAB,List.of(new BoundingBox(0,0,0,1,.5,1)));
            if(heights[i]==64.0625)put(tile[0],64,tile[1],Material.WHITE_CARPET,List.of(new BoundingBox(0,0,0,1,.0625,1)));
            if(heights[i]==63.875)put(tile[0],63,tile[1],Material.MUD,List.of(new BoundingBox(0,0,0,1,.875,1)));
        }
        int last=heights.length-1;
        int[] first=rotate(reverse?last:0,0,direction),end=rotate(reverse?0:last,0,direction);
        position=new Location(world,first[0]+.5,64,first[1]+.5);
        path(raw(first[0],64,first[1]),raw(end[0],64,end[1]));
        List<Location> visited=walk(new Location(world,end[0]+.5,64,end[1]+.5));
        assertTrue(visited.stream().anyMatch(at -> Math.abs(at.getY()-64.5)<1e-6));
        assertTrue(visited.stream().anyMatch(at -> Math.abs(at.getY()-63.875)<1e-6));
        verify(finder,times(1)).findPath(any(Location.class));
    }

    @ParameterizedTest @MethodSource("mixedSurfaceCases")
    void bendsFromMudThroughCarpetAndStairsDoNotOrbitAtTheCorner(BlockFace direction,boolean reverse) {
        int[][] tiles={{0,0},{1,0},{2,0},{2,1},{2,2},{2,3},{3,3}};
        double[] heights={64,64.5,63.875,64.0625,65,65,65};
        List<Location> points=new ArrayList<>();
        for(int i=0;i<tiles.length;i++) {
            int[] tile=rotate(tiles[i][0],tiles[i][1],direction);
            if(i==1)put(tile[0],64,tile[1],Material.STONE_SLAB,List.of(new BoundingBox(0,0,0,1,.5,1)));
            if(i==2)put(tile[0],63,tile[1],Material.MUD,List.of(new BoundingBox(0,0,0,1,.875,1)));
            if(i==3)put(tile[0],64,tile[1],Material.WHITE_CARPET,List.of(new BoundingBox(0,0,0,1,.0625,1)));
            if(i==4)put(tile[0],64,tile[1],Material.OAK_STAIRS,
                    List.of(new BoundingBox(0,0,0,1,.5,1),new BoundingBox(0,.5,.5,1,1,1))
                            .stream().map(box -> rotate(box,direction)).toList());
            if(i==5)put(tile[0],64,tile[1],Material.STONE,CUBE);
            if(i==6)put(tile[0],64,tile[1],Material.STONE_SLAB,List.of(new BoundingBox(0,.5,0,1,1,1)));
            points.add(raw(tile[0],(int)Math.ceil(heights[i]),tile[1]));
        }
        if(reverse)Collections.reverse(points);
        int start=reverse?tiles.length-1:0,finish=reverse?0:tiles.length-1;
        int[] first=rotate(tiles[start][0],tiles[start][1],direction),last=rotate(tiles[finish][0],tiles[finish][1],direction);
        position=new Location(world,first[0]+.5,heights[start],first[1]+.5);
        path(points);
        walk(new Location(world,last[0]+.5,heights[finish],last[1]+.5));
        verify(finder,times(1)).findPath(any(Location.class));
    }

    @ParameterizedTest @EnumSource(value=Material.class,names={"COBBLESTONE_WALL","OAK_FENCE","GLASS_PANE","IRON_BARS"})
    void aFlatRouteCannotTreatAProtrudingBarrierAsAnUnannouncedSlab(Material material) {
        boolean tall=material==Material.COBBLESTONE_WALL || material==Material.OAK_FENCE;
        put(1,64,0,material,List.of(new BoundingBox(tall?.375:.4375,0,0,tall?.625:.5625,tall?1.5:1,1)));
        path(raw(0,64,0),raw(2,64,0));
        for(int tick=0;tick<100;tick+=2) {
            Location previous=position.clone();
            assertNotEquals(RoutineNavigator.Result.ARRIVED,navigator.move(npc,new Location(world,2.5,64,.5),2.4,tick,2));
            assertStep(previous);
            assertEquals(64,position.getY(),1e-6,"a barrier must not become a riser in a flat route");
        }
    }
}
