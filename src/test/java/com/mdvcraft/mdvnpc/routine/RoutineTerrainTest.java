package com.mdvcraft.mdvnpc.routine;

import com.mdvcraft.mdvnpc.routine.BoundedPathfinder.Node;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.VoxelShape;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RoutineTerrainTest {
    World world;
    RoutineTerrain terrain;
    final Map<String,Block> blocks=new HashMap<>();

    @BeforeEach void setup() {
        MockBukkit.mock();
        world=mock(World.class);
        when(world.getMinHeight()).thenReturn(-64);when(world.getMaxHeight()).thenReturn(320);
        when(world.isChunkLoaded(anyInt(),anyInt())).thenReturn(true);
        when(world.getBlockAt(anyInt(),anyInt(),anyInt())).thenAnswer(call->{
            int x=call.getArgument(0),y=call.getArgument(1),z=call.getArgument(2);
            return blocks.computeIfAbsent(key(x,y,z),ignored->block(x,y,z,y==63?Material.STONE:Material.AIR,
                    y==63?List.of(new BoundingBox(0,0,0,1,1,1)):List.of()));
        });
        terrain=new RoutineTerrain(world,mock(DoorController.class));
    }
    @AfterEach void cleanup() {MockBukkit.unmock();}

    private static String key(int x,int y,int z) {return x+","+y+","+z;}
    private Block block(int x,int y,int z,Material type,List<BoundingBox> localBoxes) {
        Block block=mock(Block.class);when(block.getType()).thenReturn(type);
        when(block.isPassable()).thenReturn(localBoxes.isEmpty());
        VoxelShape shape=mock(VoxelShape.class);when(shape.getBoundingBoxes()).thenReturn(localBoxes);
        when(block.getCollisionShape()).thenReturn(shape);
        // Deliberately use the enclosing cube: the terrain must inspect the voxel boxes instead.
        when(block.getBoundingBox()).thenReturn(localBoxes.isEmpty()?new BoundingBox(x,y,z,x,y,z)
                :new BoundingBox(x,y,z,x+1,y+1,z+1));
        return block;
    }
    private void put(int x,int y,int z,Material type,List<BoundingBox> localBoxes) {
        blocks.put(key(x,y,z),block(x,y,z,type,localBoxes));
    }
    private void stair(BlockFace facing,Stairs.Shape form,List<BoundingBox> boxes) {
        Block step=block(0,64,0,Material.OAK_STAIRS,boxes);
        Stairs data=mock(Stairs.class);when(data.getFacing()).thenReturn(facing);
        when(data.getHalf()).thenReturn(Bisected.Half.BOTTOM);when(data.getShape()).thenReturn(form);
        when(step.getBlockData()).thenReturn(data);blocks.put(key(0,64,0),step);
    }

    @ParameterizedTest
    @EnumSource(value=BlockFace.class,names={"NORTH","SOUTH","EAST","WEST"})
    void straightStairsUseTheLowAndHighTreadsInTheirActualOrientation(BlockFace facing) {
        BoundingBox high=switch(facing) {
            case NORTH->new BoundingBox(0,.5,0,1,1,.5);
            case SOUTH->new BoundingBox(0,.5,.5,1,1,1);
            case EAST->new BoundingBox(.5,.5,0,1,1,1);
            case WEST->new BoundingBox(0,.5,0,.5,1,1);
            default->throw new AssertionError(facing);
        };
        BoundingBox base=new BoundingBox(0,0,0,1,.5,1);
        stair(facing,Stairs.Shape.STRAIGHT,List.of(base,high));
        double highX=.5+facing.getModX()*.35,highZ=.5+facing.getModZ()*.35;
        double lowX=.5-facing.getModX()*.35,lowZ=.5-facing.getModZ()*.35;

        assertEquals(64.5,terrain.supportHeight(lowX,64,lowZ),.000001);
        assertEquals(65,terrain.supportHeight(highX,64,highZ),.000001);
        assertTrue(terrain.fits(lowX,64.5,lowZ,false));
        assertFalse(terrain.fits(highX,64.5,highZ,false));
        assertTrue(terrain.fits(highX,65,highZ,false));
        assertEquals(65,terrain.height(new Node(0,65,0)),.000001);
        assertTrue(terrain.stand(new Node(0,65,0)));
        assertEquals(new BoundingBox(0,0,0,1,.5,1),base,"queries must not shift shared local boxes in place");
    }

    @Test void innerCornerPreservesTheLowerQuadrantAndBothUpperArms() {
        stair(BlockFace.EAST,Stairs.Shape.INNER_RIGHT,List.of(new BoundingBox(0,0,0,1,.5,1),
                new BoundingBox(.5,.5,0,1,1,1),new BoundingBox(0,.5,.5,.5,1,1)));
        assertEquals(64.5,terrain.supportHeight(.15,64,.15),.000001);
        assertTrue(terrain.fits(.15,64.5,.15,false));
        assertEquals(65,terrain.supportHeight(.85,64,.15),.000001);
        assertEquals(65,terrain.supportHeight(.15,64,.85),.000001);
        assertFalse(terrain.fits(.15,64.5,.85,false));
    }

    @Test void outerCornerOnlyRaisesTheQuarterWithTheUpperTread() {
        stair(BlockFace.EAST,Stairs.Shape.OUTER_RIGHT,List.of(new BoundingBox(0,0,0,1,.5,1),
                new BoundingBox(.5,.5,.5,1,1,1)));
        assertEquals(64.5,terrain.supportHeight(.15,64,.85),.000001);
        assertEquals(64.5,terrain.supportHeight(.85,64,.15),.000001);
        assertTrue(terrain.fits(.15,64.5,.85,false));
        assertEquals(65,terrain.supportHeight(.85,64,.85),.000001);
        assertFalse(terrain.fits(.85,64.5,.85,false));
        assertTrue(terrain.fits(.85,65,.85,false));
    }

    @Test void halfSlabProvidesHalfBlockHeightAtNonzeroWorldCoordinates() {
        put(8,64,-3,Material.STONE_SLAB,List.of(new BoundingBox(0,0,0,1,.5,1)));
        Node slab=new Node(8,65,-3);
        assertEquals(64.5,terrain.height(slab),.000001);
        assertEquals(64.5,terrain.supportHeight(8.5,64,-2.5),.000001);
        assertTrue(terrain.stand(slab));assertTrue(terrain.fits(8.5,64.5,-2.5,false));
        assertFalse(terrain.fits(8.5,64,-2.5,false));
    }

    @Test void lowCeilingBlocksTheBodyWithoutBecomingItsFloor() {
        put(0,64,0,Material.STONE_SLAB,List.of(new BoundingBox(0,0,0,1,.5,1)));
        put(0,66,0,Material.STONE,List.of(new BoundingBox(0,0,0,1,1,1)));
        assertEquals(64.5,terrain.supportHeight(.5,64.5,.5),.000001);
        assertFalse(terrain.fits(.5,64.5,.5,false));assertFalse(terrain.stand(new Node(0,65,0)));
    }

    @Test void dangerousSupportAndAirborneHazardsAreRejected() {
        put(0,63,0,Material.MAGMA_BLOCK,List.of(new BoundingBox(0,0,0,1,1,1)));
        assertTrue(Double.isNaN(terrain.supportHeight(.5,64,.5)));
        assertFalse(terrain.stand(new Node(0,64,0)));
        put(0,63,0,Material.STONE,List.of(new BoundingBox(0,0,0,1,1,1)));
        put(0,64,0,Material.LAVA,List.of());
        assertFalse(terrain.fits(.5,64,.5,false));
    }

    @Test void footprintAtAnUnloadedChunkBoundaryNeverReadsItsBlocks() {
        when(world.isChunkLoaded(eq(1),anyInt())).thenReturn(false);
        assertTrue(Double.isNaN(terrain.supportHeight(15.85,64,.5)));
        assertFalse(terrain.fits(15.85,64,.5,false));
        assertNull(terrain.near(new org.bukkit.Location(world,16.5,64,.5)));
        verify(world,never()).getBlockAt(eq(16),anyInt(),anyInt());
        verify(world,never()).getChunkAt(anyInt(),anyInt());
    }

    @Test void lowestWorldFloorStillSupportsWalkingWithoutInspectingBelowItsBoundary() {
        put(0,-64,0,Material.STONE,List.of(new BoundingBox(0,0,0,1,1,1)));
        assertEquals(-63,terrain.supportHeight(.5,-63,.5),.000001);
        assertTrue(terrain.stand(new Node(0,-63,0)));
        assertTrue(terrain.fits(.5,-63,.5,false));
        assertEquals(new Node(0,-63,0),terrain.near(new org.bukkit.Location(world,.5,-63,.5)));
        verify(world,never()).getBlockAt(anyInt(),intThat(y->y < -64),anyInt());
    }
}
