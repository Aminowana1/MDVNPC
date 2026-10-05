package com.mdvcraft.mdvnpc.routine;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Door;
import org.bukkit.block.data.type.Gate;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.bukkit.util.VoxelShape;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Physical execution: attempted hops need no support and each obstructed axis stops independently. */
class RoutineForcedHopTerrainTest {
    private record Key(int x,int y,int z) {}
    private static final List<BoundingBox> CUBE=List.of(new BoundingBox(0,0,0,1,1,1));
    private World world;
    private RoutineTerrain terrain;
    private final Map<Key,Block> blocks=new HashMap<>();

    @BeforeEach void setup() {
        MockBukkit.mock();world=mock(World.class);
        when(world.getMinHeight()).thenReturn(-64);when(world.getMaxHeight()).thenReturn(320);
        when(world.isChunkLoaded(anyInt(),anyInt())).thenReturn(true);
        when(world.getBlockAt(anyInt(),anyInt(),anyInt())).thenAnswer(call->{
            Key key=new Key(call.getArgument(0),call.getArgument(1),call.getArgument(2));
            assertTrue(world.isChunkLoaded(key.x()>>4,key.z()>>4),"clip must not read an unloaded chunk");
            assertTrue(key.y()>=-64 && key.y()<320,"clip must not read outside world bounds");
            return blocks.computeIfAbsent(key,ignored->block(key,Material.AIR,List.of(),null));
        });
        terrain=new RoutineTerrain(world,mock(DoorController.class));
    }
    @AfterEach void cleanup() {MockBukkit.unmock();}

    private Block block(Key key,Material material,List<BoundingBox> boxes,BlockData state) {
        Block result=mock(Block.class);when(result.getType()).thenReturn(material);
        when(result.getBlockData()).thenReturn(state);when(result.getWorld()).thenReturn(world);
        when(result.getX()).thenReturn(key.x());when(result.getY()).thenReturn(key.y());when(result.getZ()).thenReturn(key.z());
        VoxelShape shape=mock(VoxelShape.class);when(shape.getBoundingBoxes()).thenReturn(boxes);
        when(result.getCollisionShape()).thenReturn(shape);return result;
    }
    private void put(int x,int y,int z,Material material,List<BoundingBox> boxes,BlockData state) {
        Key key=new Key(x,y,z);blocks.put(key,block(key,material,boxes,state));
    }
    private void put(int x,int y,int z,Material material,List<BoundingBox> boxes) {put(x,y,z,material,boxes,null);}
    private Vector clip(double x,double y,double z,Vector requested,double baseY) {
        return terrain.clipRecoveryHop(new Location(world,x,y,z),requested,baseY);
    }
    private static void assertMovement(double x,double y,double z,Vector result) {
        assertEquals(x,result.getX(),1e-7);assertEquals(y,result.getY(),1e-7);assertEquals(z,result.getZ(),1e-7);
    }

    @Test void airborneHopNeedsNeitherFloorNorLanding() {
        assertMovement(1,.6,0,clip(.5,90,.5,new Vector(1,.6,0),90));
        assertMovement(1,-.6,0,clip(.5,90,.5,new Vector(1,-.6,0),90));
    }

    @Test void wallStopsForwardAxisAndStillAllowsLift() {
        for(int y=64;y<=67;y++)put(1,y,0,Material.STONE,CUBE);
        assertMovement(.2,.6,0,clip(.5,64,.5,new Vector(1,.6,0),64));
    }

    @Test void ceilingClipsOnlyLiftAndDoesNotCancelForwardMovement() {
        put(0,66,0,Material.STONE,CUBE);
        assertMovement(1,.05,0,clip(.5,64,.5,new Vector(1,.6,0),64));
    }

    @Test void descendingCollisionStopsAtExactFeetHeight() {
        put(0,63,0,Material.STONE,CUBE);
        assertMovement(.1,0,0,clip(.5,64,.5,new Vector(.1,-.6,0),64));
    }

    @Test void shallowSlabEmbeddingCanExitUpwardButCannotSinkFarther() {
        put(0,64,0,Material.STONE_SLAB,List.of(new BoundingBox(0,0,0,1,.5,1)));
        assertMovement(0,.3,0,clip(.5,64.4,.5,new Vector(0,.3,0),64.4));
        assertMovement(0,0,0,clip(.5,64.4,.5,new Vector(0,-.3,0),64.4));
    }

    @Test void sideEmbeddingCanExitAndCannotTravelThroughTheWall() {
        put(1,64,0,Material.STONE,CUBE);
        assertMovement(-.4,0,0,clip(.8,64,.5,new Vector(-.4,0,0),64));
        assertMovement(0,0,0,clip(.8,64,.5,new Vector(2,0,0),64));
    }

    @Test void nearestEscapeDoesNotTunnelIntoTheNextObstacle() {
        put(1,64,0,Material.STONE,CUBE);put(-1,64,0,Material.STONE,CUBE);
        assertMovement(-.5,0,0,clip(.8,64,.5,new Vector(-2,0,0),64));
    }

    @Test void lowFenceAndSlabCapStillStopHorizontalCrossingAtTheApex() {
        put(1,62,0,Material.OAK_FENCE,List.of(new BoundingBox(.375,0,.375,.625,1.5,.625)));
        put(1,63,0,Material.STONE_SLAB,List.of(new BoundingBox(0,.5,0,1,1,1)));
        assertMovement(.2,.1,0,clip(.5,65,.5,new Vector(1,.1,0),64));
    }

    @Test void fenceBelowCurrentFeetDoesNotVetoVerticalAttempt() {
        put(0,62,0,Material.OAK_FENCE,List.of(new BoundingBox(.375,0,.375,.625,1.5,.625)));
        put(0,63,0,Material.OAK_TRAPDOOR,List.of(new BoundingBox(0,.8125,0,1,1,1)));
        assertMovement(0,.6,0,clip(.5,64,.5,new Vector(0,.6,0),64));
    }

    @Test void uncappedFenceCannotBeCrossedUsingAnElevatedBody() {
        put(1,63,0,Material.NETHER_BRICK_FENCE,List.of(new BoundingBox(.375,0,.375,.625,1.5,.625)));
        assertMovement(.575,0,0,clip(.5,66,.5,new Vector(1,0,0),64));
    }

    @Test void genuinelyHigherBridgeCanPassAboveLowerFence() {
        put(1,61,0,Material.OAK_FENCE,List.of(new BoundingBox(.375,0,.375,.625,1.5,.625)));
        put(1,63,0,Material.STONE,CUBE);
        assertMovement(1,.6,0,clip(.5,64,.5,new Vector(1,.6,0),64));
    }

    @Test void closedGateProjectsAndOpenGateUsesOnlyItsLiveVoxels() {
        Gate closed=mock(Gate.class);when(closed.isOpen()).thenReturn(false);
        put(1,63,0,Material.OAK_FENCE_GATE,List.of(new BoundingBox(0,0,.375,1,1.5,.625)),closed);
        assertMovement(.2,.1,0,clip(.5,66,.5,new Vector(1,.1,0),64));
        Gate open=mock(Gate.class);when(open.isOpen()).thenReturn(true);
        put(1,63,0,Material.OAK_FENCE_GATE,List.of(),open);
        assertMovement(1,.1,0,clip(.5,66,.5,new Vector(1,.1,0),64));
    }

    @Test void closedDoorPanelClipsAtItsVoxelAndAllowsLift() {
        Door closed=mock(Door.class);when(closed.isOpen()).thenReturn(false);
        for(int y=64;y<=66;y++)put(1,y,0,Material.OAK_DOOR,List.of(new BoundingBox(0,0,0,.1875,1,1)),closed);
        assertMovement(.2,.6,0,clip(.5,64,.5,new Vector(1,.6,0),64));
    }

    @Test void openDoorKeepsItsSwungPanelButAllowsTheOpenPassage() {
        Door open=mock(Door.class);when(open.isOpen()).thenReturn(true);
        for(int y=64;y<=66;y++)put(1,y,0,Material.OAK_DOOR,List.of(new BoundingBox(0,0,0,1,1,.1875)),open);
        assertMovement(1,.6,0,clip(.5,64,.5,new Vector(1,.6,0),64));
        assertMovement(.2,.6,0,clip(.5,64,.1,new Vector(1,.6,0),64));
    }

    @Test void fluidsAndUnsafeLandingsDoNotPreventTheAttempt() {
        put(1,64,0,Material.LAVA,List.of());put(1,63,0,Material.MAGMA_BLOCK,CUBE);
        assertMovement(1,.6,0,clip(.5,64,.5,new Vector(1,.6,0),64));
    }

    @Test void unloadedNeighbourStopsCrossingWithoutCancellingVerticalImpulse() {
        when(world.isChunkLoaded(1,0)).thenReturn(false);
        assertMovement(.2,.6,0,clip(15.5,64,.5,new Vector(1,.6,0),64));
        verify(world,never()).getBlockAt(eq(16),anyInt(),anyInt());
    }

    @Test void touchingUnknownChunkBoundaryStillAllowsInPlaceLift() {
        when(world.isChunkLoaded(1,0)).thenReturn(false);
        assertMovement(0,.6,0,clip(15.7,64,.5,new Vector(0,.6,0),64));
        verify(world,never()).getBlockAt(eq(16),anyInt(),anyInt());
    }

    @Test void worldCeilingStopsYAndKeepsHorizontalImpulse() {
        assertMovement(.5,.05,0,clip(.5,318,.5,new Vector(.5,.6,0),318));
    }

    @Test void bodyAlreadyInsideUnloadedChunkDoesNotQueryBlocksOrMove() {
        when(world.isChunkLoaded(1,0)).thenReturn(false);
        assertMovement(0,0,0,clip(16.5,64,.5,new Vector(1,.6,0),64));
        verify(world,never()).getBlockAt(anyInt(),anyInt(),anyInt());
    }

    @Test void malformedImpulseCannotCreateAnUnboundedBlockScan() {
        assertMovement(0,0,0,clip(.5,64,.5,new Vector(Double.NaN,.6,0),64));
        assertMovement(0,0,0,clip(Integer.MAX_VALUE-3.0,64,.5,new Vector(4,.6,0),64));
        verify(world,never()).getBlockAt(anyInt(),anyInt(),anyInt());
    }
}
