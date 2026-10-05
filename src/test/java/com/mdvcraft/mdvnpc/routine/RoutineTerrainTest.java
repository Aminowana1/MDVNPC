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
import org.junit.jupiter.params.provider.MethodSource;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Arrays;
import java.util.stream.Stream;

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

    private static Stream<Material> fenceMaterials() {
        return Arrays.stream(Material.values()).filter(material->!material.isLegacy() && material.name().endsWith("_FENCE"));
    }

    private static Stream<Material> gateMaterials() {
        return Arrays.stream(Material.values()).filter(material->!material.isLegacy() && material.name().endsWith("_FENCE_GATE"));
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

    @Test void supportProbeDoesNotRequireAnUnrelatedNeighbourChunkButBodyClearanceStillDoes() {
        when(world.isChunkLoaded(eq(1),anyInt())).thenReturn(false);
        assertEquals(64,terrain.supportHeight(15.85,64,.5),.000001);
        assertFalse(terrain.fits(15.85,64,.5,false));
        assertNull(terrain.near(new org.bukkit.Location(world,16.5,64,.5)));
        verify(world,never()).getBlockAt(eq(16),anyInt(),anyInt());
        verify(world,never()).getChunkAt(anyInt(),anyInt());
    }

    @Test void sideWallTouchingTheBodyIsNotMistakenForAHigherFloor() {
        put(1,64,0,Material.STONE,List.of(new BoundingBox(0,0,0,1,1,1)));
        assertEquals(64,terrain.supportHeight(.72,64,.5),.000001);
        assertFalse(terrain.fits(.72,64,.5,false),"the shoulder may collide even though the feet remain on the floor");
    }

    @ParameterizedTest
    @EnumSource(value=Material.class,names={"OAK_FENCE","COBBLESTONE_WALL"})
    void protrudingBarriersRemainPhysicalSupportButNeverBecomeAnUnannouncedRiser(Material material) {
        put(0,64,0,material,List.of(new BoundingBox(.25,0,.25,.75,1.5,.75)));
        if(material==Material.OAK_FENCE)assertTrue(Double.isNaN(terrain.supportHeight(.5,64,.5,1.5,.10)),"fence tops are forbidden supports");
        else assertEquals(65.5,terrain.supportHeight(.5,64,.5,1.5,.10),1e-8);
        assertEquals(64,terrain.climbSupportHeight(.5,64,.5,1.5,.10),1e-8,
                "the ordinary floor remains visible while the barrier top is excluded from climbs");
        assertFalse(terrain.fits(.5,64,.5,false),"the excluded riser is still a collision obstacle");
        assertTrue(terrain.fits(.5,65.5,.5,false));
        assertFalse(terrain.recoveryHopFits(.5,65.5,.5,64,false),"elevating the body does not permit a projected barrier crossing");
    }

    @ParameterizedTest
    @MethodSource("fenceMaterials")
    void everyWoodAndNetherBrickFenceRejectsSupportsAndHighProjectedCrossings(Material material) {
        put(0,64,0,material,List.of(new BoundingBox(0,0,.375,1,1.5,.625)));
        assertFalse(RoutineTerrain.walkingSurface(world.getBlockAt(0,64,0)));
        assertFalse(terrain.stand(new Node(0,65,0)));
        assertFalse(terrain.partialSupport(new Node(0,65,0)));
        assertTrue(Double.isNaN(terrain.supportHeight(.5,65.5,.5,.02)));
        assertTrue(Double.isNaN(terrain.safeSupportBelow(.5,68,.5,8)),"a fall cannot select the fence top as its landing");
        assertTrue(Double.isNaN(terrain.recoveryHopLanding(.5,64,.5,.6,4)));
        assertTrue(terrain.fits(.5,68,.5,false),"the fixture is physically clear above the 1.5-block fence");
        assertFalse(terrain.recoveryHopFits(.5,68,.5,64,false),"the maximum configured climb cannot clear a fence");
        assertFalse(terrain.recoveryHopFits(.5,70.25,.5,64,false),"even the high arc of a rising recovery hop cannot clear it");
    }

    @ParameterizedTest
    @MethodSource("gateMaterials")
    void everyClosedGateBlocksProjectedCrossingsButOpenGatesRemainPassable(Material material) {
        Block gate=block(0,64,0,material,List.of(new BoundingBox(0,0,.375,1,1.5,.625)));
        var data=mock(org.bukkit.block.data.type.Gate.class);when(data.isOpen()).thenReturn(false);
        when(gate.getBlockData()).thenReturn(data);blocks.put(key(0,64,0),gate);
        assertFalse(terrain.recoveryHopFits(.5,68,.5,64,false));
        assertTrue(Double.isNaN(terrain.recoveryHopLanding(.5,64,.5,.6,4)));
        assertFalse(terrain.stand(new Node(0,65,0)));
        when(data.isOpen()).thenReturn(true);
        when(gate.getCollisionShape().getBoundingBoxes()).thenReturn(List.of());
        assertTrue(terrain.fits(.5,64,.5,false));
        assertTrue(terrain.recoveryHopFits(.5,64,.5,64,false));
        assertEquals(64,terrain.recoveryHopLanding(.5,64,.5,.6,4),1e-8);
        assertTrue(terrain.stand(new Node(0,64,0)),"the real floor below the open gate remains a destination");
    }

    @Test void aLegitimateBridgeAboveAFenceSupportsWalkingAndRecovery() {
        put(0,64,0,Material.NETHER_BRICK_FENCE,List.of(new BoundingBox(0,0,.375,1,1.5,.625)));
        put(0,66,0,Material.STONE,List.of(new BoundingBox(0,0,0,1,1,1)));
        assertTrue(terrain.stand(new Node(0,67,0)));
        assertEquals(67,terrain.supportHeight(.5,67,.5,.02),1e-8);
        assertEquals(67,terrain.safeSupportBelow(.5,67,.5,8),1e-8);
        assertEquals(67,terrain.recoveryHopLanding(.5,67,.5,.6,4),1e-8);
        assertTrue(terrain.recoveryHopFits(.5,67,.5,67,false));
        assertTrue(terrain.recoveryHopFits(.5,69,.5,67,false));
        assertFalse(terrain.recoveryHopFits(.5,67,.5,64,false),"an airborne climb from below must retain its original grounded height");
    }

    @Test void hopProjectionUsesRealFenceVoxelsAndAllowsTheClearSide() {
        put(0,64,0,Material.OAK_FENCE,List.of(new BoundingBox(.4375,0,0,.5625,1.5,1)));
        assertFalse(terrain.recoveryHopFits(.5,67,.5,64,false));
        assertTrue(terrain.recoveryHopFits(.9,67,.5,64,false),"the body lies beyond the actual fence panel");
    }

    @Test void levelReplayKeepsFenceCollisionWithoutRepeatingTheProjectionScan() {
        put(0,64,0,Material.OAK_FENCE,List.of(new BoundingBox(0,0,.375,1,1.5,.625)));
        assertFalse(terrain.recoveryHopFits(.5,64,.5,64,false),"a level body still collides with the fence");
        put(0,64,0,Material.AIR,List.of());
        clearInvocations(world);
        assertTrue(terrain.recoveryHopFits(.5,64,.5,64,false));
        verify(world,times(1)).getBlockAt(0,63,0);
        verify(world,times(1)).getBlockAt(0,64,0);
        verify(world,times(1)).getBlockAt(0,65,0);
    }

    @ParameterizedTest
    @EnumSource(value=Material.class,names={"COBBLESTONE_WALL","GLASS_PANE","RED_STAINED_GLASS_PANE","IRON_BARS"})
    void recoveryLandingsRejectBarrierSurfaces(Material material) {
        double height=material==Material.COBBLESTONE_WALL?1.5:1;
        put(0,64,0,material,List.of(new BoundingBox(.4375,0,0,.5625,height,1)));
        assertTrue(Double.isNaN(terrain.recoveryHopLanding(.5,64,.5,.6,4)));
    }

    @Test void hopLandingsUseActualFractionalSupportAndRejectHazardsAndLowCeilings() {
        put(0,64,0,Material.STONE_SLAB,List.of(new BoundingBox(0,0,0,1,.5,1)));
        assertEquals(64.5,terrain.recoveryHopLanding(.5,64,.5,.6,1),1e-8);
        put(0,66,0,Material.STONE,List.of(new BoundingBox(0,0,0,1,1,1)));
        assertTrue(Double.isNaN(terrain.recoveryHopLanding(.5,64,.5,.6,1)));
        put(0,66,0,Material.AIR,List.of());
        put(0,64,0,Material.AIR,List.of());
        put(0,63,0,Material.MAGMA_BLOCK,List.of(new BoundingBox(0,0,0,1,1,1)));
        assertTrue(Double.isNaN(terrain.recoveryHopLanding(.5,64,.5,.6,1)));
    }

    @Test void hopChecksNeverReadAnUnloadedPartOfTheBodyOrMalformedEnvelope() {
        when(world.isChunkLoaded(eq(1),anyInt())).thenReturn(false);
        assertFalse(terrain.recoveryHopFits(15.85,66,.5,64,false));
        assertTrue(Double.isNaN(terrain.recoveryHopLanding(15.85,64,.5,.6,1)));
        assertFalse(terrain.recoveryHopFits(.5,66,.5,Double.NaN,false));
        assertFalse(terrain.recoveryHopFits(.5,100,.5,64,false));
        assertTrue(Double.isNaN(terrain.recoveryHopLanding(.5,64,.5,.6,Double.POSITIVE_INFINITY)));
        verify(world,never()).getBlockAt(eq(16),anyInt(),anyInt());
        verify(world,never()).getChunkAt(anyInt(),anyInt());
    }

    @ParameterizedTest
    @EnumSource(value=Material.class,names={"GLASS_PANE","LIME_STAINED_GLASS_PANE","IRON_BARS"})
    void integerHeightPanesAndBarsCannotBecomeAnUnannouncedRiser(Material material) {
        put(0,64,0,material,List.of(new BoundingBox(.4375,0,0,.5625,1,1)));
        assertEquals(65,terrain.supportHeight(.5,64,.5,1.5,.10),1e-8);
        assertEquals(64,terrain.climbSupportHeight(.5,64,.5,1.5,.10),1e-8);
        assertFalse(terrain.fits(.5,64,.5,false),"a pane or bar still blocks the actual body");
        assertTrue(terrain.fits(.5,65,.5,false));
    }

    @Test void aFullGlassBlockRemainsAnOrdinaryCubeRiser() {
        put(0,64,0,Material.GLASS,List.of(new BoundingBox(0,0,0,1,1,1)));
        assertEquals(65,terrain.climbSupportHeight(.5,64,.5,1.5,.10),1e-8);
    }

    @Test void riserProbeAcceptsAFullBlockWithAHalfSlabAboveIt() {
        put(0,64,0,Material.STONE,List.of(new BoundingBox(0,0,0,1,1,1)));
        put(0,65,0,Material.STONE_SLAB,List.of(new BoundingBox(0,0,0,1,.5,1)));
        assertEquals(65.5,terrain.climbSupportHeight(.5,64,.5,1.5,.10),1e-8,
                "a 1.5-block riser made from ordinary source-local collision remains eligible");
    }

    @Test void anEligibleFloorAboveALowerProtrudingBarrierStillWinsTheRiserProbe() {
        put(0,63,0,Material.COBBLESTONE_WALL,List.of(new BoundingBox(.25,0,.25,.75,1.5,.75)));
        put(0,64,0,Material.STONE,List.of(new BoundingBox(0,0,0,1,1,1)));
        assertEquals(65,terrain.supportHeight(.5,64,.5,1.5,.10),1e-8);
        assertEquals(65,terrain.climbSupportHeight(.5,64,.5,1.5,.10),1e-8);
        assertTrue(terrain.fits(.5,65,.5,false));
    }

    @Test void carpetCollisionShapeIsAValidThinWalkingSurface() {
        put(0,64,0,Material.WHITE_CARPET,List.of(new BoundingBox(0,0,0,1,.0625,1)));
        Node carpet=new Node(0,65,0);
        assertEquals(64.0625,terrain.height(carpet),.000001);
        assertEquals(64.0625,terrain.supportHeight(.5,64.0625,.5),.000001);
        assertTrue(terrain.stand(carpet));
        assertEquals(carpet,terrain.near(new org.bukkit.Location(world,.5,64.0625,.5)));
    }


    @Test void editorAcceptsCollisionBasedThinWalkingSurfaces() {
        Block carpet=block(0,64,0,Material.WHITE_CARPET,List.of(new BoundingBox(0,0,0,1,.0625,1)));
        Block slab=block(1,64,0,Material.STONE_SLAB,List.of(new BoundingBox(0,0,0,1,.5,1)));
        Block air=block(2,64,0,Material.AIR,List.of());
        assertTrue(RoutineTerrain.walkingSurface(carpet));
        assertTrue(RoutineTerrain.walkingSurface(slab));
        assertFalse(RoutineTerrain.walkingSurface(air));
    }

    @Test void partialSupportRecoveryNeverClassifiesAFullCubeAsThinGround() {
        put(0,63,0,Material.STONE,List.of(new BoundingBox(0,0,0,1,1,1)));
        put(1,64,0,Material.WHITE_CARPET,List.of(new BoundingBox(0,0,0,1,.0625,1)));
        assertFalse(terrain.partialSupport(new Node(0,64,0)));
        assertTrue(terrain.partialSupport(new Node(1,65,0)));
    }

    @Test void topAndBottomSlabsArePartialButDoubleSlabsAndFullStairColumnsAreNot() {
        put(0,64,0,Material.STONE_SLAB,List.of(new BoundingBox(0,.5,0,1,1,1)));
        put(1,64,0,Material.STONE_SLAB,List.of(new BoundingBox(0,0,0,1,.5,1)));
        put(2,64,0,Material.STONE_SLAB,List.of(new BoundingBox(0,0,0,1,1,1)));
        put(3,64,0,Material.OAK_STAIRS,List.of(new BoundingBox(0,0,0,1,.5,1),
                new BoundingBox(.5,.5,0,1,1,1)));
        assertTrue(terrain.partialSupport(new Node(0,65,0)),"a top slab has half-block collision despite integer surface Y");
        assertTrue(terrain.partialSupport(new Node(1,65,0)));
        assertFalse(terrain.partialSupport(new Node(2,65,0)),"a double slab is a complete cube");
        assertFalse(terrain.partialSupport(new Node(3,65,0)),"the central stair column occupies the full block height");
    }

    @Test void shallowTopSlabEmbeddingHasAVerifiedBoundedEscapeToItsSurface() {
        put(0,64,0,Material.STONE_SLAB,List.of(new BoundingBox(0,.5,0,1,1,1)));
        Node support=terrain.near(new org.bukkit.Location(world,.5,64.95,.5));
        assertEquals(new Node(0,65,0),support);
        assertFalse(terrain.fits(.5,64.95,.5,false),"fixture starts inside the slab's actual collision");
        try(var ignored=terrain.beginUpdate()) {
            assertTrue(terrain.fitsPartialEscape(.5,64.95,.5,support));
            assertTrue(terrain.fitsPartialEscape(.5,64.98,.5,support));
            assertTrue(terrain.fits(.5,65,.5,false),"the final landing uses normal clearance");
        }
        assertFalse(terrain.fitsPartialEscape(.5,64.4,.5,support),"an escape cannot tunnel more than .51 blocks");
        assertFalse(terrain.fitsPartialEscape(1.5,64.95,.5,support),"an unrelated support block cannot justify an escape");
    }

    @ParameterizedTest
    @EnumSource(value=BlockFace.class,names={"NORTH","SOUTH","EAST","WEST"})
    void partialEscapeUsesTheActualLowerStairTreadRatherThanItsTileCentre(BlockFace facing) {
        BoundingBox high=switch(facing) {
            case NORTH->new BoundingBox(0,.5,0,1,1,.5);
            case SOUTH->new BoundingBox(0,.5,.5,1,1,1);
            case EAST->new BoundingBox(.5,.5,0,1,1,1);
            case WEST->new BoundingBox(0,.5,0,.5,1,1);
            default->throw new AssertionError(facing);
        };
        stair(facing,Stairs.Shape.STRAIGHT,List.of(new BoundingBox(0,0,0,1,.5,1),high));
        double lowX=.5-facing.getModX()*.35,lowZ=.5-facing.getModZ()*.35;
        Node support=terrain.near(new org.bukkit.Location(world,lowX,64.45,lowZ));
        assertEquals(new Node(0,65,0),support);
        assertEquals(65,terrain.height(support),1e-8,"Paper still uses the tile-centre upper surface");
        assertFalse(terrain.partialSupport(support));
        assertEquals(64.5,terrain.supportHeightAtNode(support,lowX,lowZ),1e-8);
        assertTrue(terrain.partialSupport(support,lowX,lowZ));
        assertFalse(terrain.fits(lowX,64.45,lowZ,false));
        assertTrue(terrain.fitsPartialEscape(lowX,64.45,lowZ,support));
        assertTrue(terrain.fitsPartialEscape(lowX,64.48,lowZ,support));
        assertTrue(terrain.fits(lowX,64.5,lowZ,false));
    }

    @Test void lowerStairEscapeCannotIgnoreTheHigherRiserTouchingTheShoulder() {
        stair(BlockFace.EAST,Stairs.Shape.STRAIGHT,List.of(new BoundingBox(0,0,0,1,.5,1),
                new BoundingBox(.5,.5,0,1,1,1)));
        Node support=new Node(0,65,0);
        assertEquals(64.5,terrain.supportHeightAtNode(support,.3,.5),1e-8);
        assertTrue(terrain.partialSupport(support,.3,.5),"the narrow feet remain above the low tread");
        assertFalse(terrain.fitsPartialEscape(.3,64.45,.5,support),
                "only the low base may be ignored; the higher riser still intersects the body");
        assertFalse(terrain.fits(.3,64.5,.5,false));
    }

    @Test void partialEscapeAcrossABlockBorderAllowsMatchingCarpetFloors() {
        put(0,64,0,Material.WHITE_CARPET,List.of(new BoundingBox(0,0,0,1,.0625,1)));
        put(1,64,0,Material.WHITE_CARPET,List.of(new BoundingBox(0,0,0,1,.0625,1)));
        Node support=new Node(0,65,0);
        assertFalse(terrain.fits(.98,64,.5,false),"both adjacent carpets overlap the initial body");
        assertTrue(terrain.fitsPartialEscape(.98,64,.5,support));
        assertTrue(terrain.fitsPartialEscape(.98,64.03,.5,support));
        assertTrue(terrain.fits(.98,64.0625,.5,false),"the final landing uses complete ordinary collision");
    }

    @Test void borderEscapeStillRejectsAFullCubeOrHigherStairRiser() {
        put(0,64,0,Material.STONE_SLAB,List.of(new BoundingBox(0,0,0,1,.5,1)));
        Node support=new Node(0,65,0);
        put(1,64,0,Material.STONE,List.of(new BoundingBox(0,0,0,1,1,1)));
        assertFalse(terrain.fitsPartialEscape(.98,64.45,.5,support),"a neighbour cube cannot be masked");
        put(1,64,0,Material.OAK_STAIRS,List.of(new BoundingBox(0,0,0,1,.5,1),
                new BoundingBox(0,.5,0,.5,1,1)));
        assertFalse(terrain.fitsPartialEscape(.98,64.45,.5,support),"the neighbour's higher stair riser remains solid");
        put(1,64,0,Material.STONE_SLAB,List.of(new BoundingBox(0,0,0,1,.5,1)));
        assertTrue(terrain.fitsPartialEscape(.98,64.45,.5,support),"matching partial slabs may be exited together");
        assertTrue(terrain.fits(.98,64.5,.5,false));
    }

    @Test void borderEscapeAllowsANeighbourStairsActualLowerTread() {
        put(0,64,0,Material.STONE_SLAB,List.of(new BoundingBox(0,0,0,1,.5,1)));
        put(1,64,0,Material.OAK_STAIRS,List.of(new BoundingBox(0,0,0,1,.5,1),
                new BoundingBox(.5,.5,0,1,1,1)));
        Node support=new Node(0,65,0),neighbour=new Node(1,65,0);
        assertFalse(terrain.partialSupport(neighbour),"the neighbouring stair's centre includes its upper riser");
        assertTrue(terrain.partialSupport(neighbour,.98,.5));
        assertEquals(64.5,terrain.supportHeightAtNode(neighbour,.98,.5),1e-8);
        assertFalse(terrain.fits(.98,64,.5,false),"the saved body overlaps both lower partial surfaces");
        assertTrue(terrain.fitsPartialEscape(.98,64,.5,support));
        assertTrue(terrain.fitsPartialEscape(.98,64.2,.5,support));
        assertTrue(terrain.fits(.98,64.5,.5,false),"the stair's upper half starts beyond the final body footprint");
    }

    @ParameterizedTest
    @EnumSource(value=Material.class,names={"WHITE_CARPET","STONE_SLAB","MUD","DIRT_PATH","SOUL_SAND"})
    void partialFloorEscapeIsLimitedToTheActualFloorCollision(Material material) {
        int floorY=material==Material.WHITE_CARPET || material==Material.STONE_SLAB?64:63;
        double top=switch(material) {
            case WHITE_CARPET -> .0625;
            case STONE_SLAB -> .5;
            case DIRT_PATH -> .9375;
            default -> .875;
        };
        put(0,floorY,0,material,List.of(new BoundingBox(0,0,0,1,top,1)));
        Node support=new Node(0,floorY+1,0);
        double embedded=floorY+Math.max(0,top-.05);
        assertFalse(terrain.fits(.5,embedded,.5,false));
        assertTrue(terrain.fitsPartialEscape(.5,embedded,.5,support));
        assertTrue(terrain.fits(.5,floorY+top,.5,false));
        put(1,floorY,0,Material.STONE,List.of(new BoundingBox(0,0,0,1,1,1)));
        assertFalse(terrain.fitsPartialEscape(.75,embedded,.5,support),"the adjacent complete block is never ignored");
    }

    @Test void partialEscapeStillChecksCeilingsHazardsAndFullCubeEmbedding() {
        put(0,64,0,Material.STONE_SLAB,List.of(new BoundingBox(0,0,0,1,.5,1)));
        Node support=new Node(0,65,0);
        assertTrue(terrain.fitsPartialEscape(.5,64.2,.5,support));
        put(0,66,0,Material.STONE,List.of(new BoundingBox(0,0,0,1,1,1)));
        assertFalse(terrain.fitsPartialEscape(.5,64.2,.5,support),"escaping the floor cannot pass through a ceiling");
        put(0,66,0,Material.AIR,List.of());
        put(0,65,0,Material.LAVA,List.of());
        assertFalse(terrain.fitsPartialEscape(.5,64.2,.5,support));
        put(0,65,0,Material.AIR,List.of());
        put(0,64,0,Material.STONE,List.of(new BoundingBox(0,0,0,1,1,1)));
        assertFalse(terrain.fitsPartialEscape(.5,64.9,.5,support),"full stone remains protected from recovery tunnelling");
    }

    @Test void partialEscapeNeverReadsAnUnloadedPartOfTheBody() {
        put(15,64,0,Material.STONE_SLAB,List.of(new BoundingBox(0,0,0,1,.5,1)));
        when(world.isChunkLoaded(eq(1),anyInt())).thenReturn(false);
        assertFalse(terrain.fitsPartialEscape(15.85,64.2,.5,new Node(15,65,0)));
        verify(world,never()).getBlockAt(eq(16),anyInt(),anyInt());
    }

    @Test void mixedMudCarpetBottomAndTopSlabNodesKeepTheirPhysicalHeights() {
        put(0,63,0,Material.MUD,List.of(new BoundingBox(0,0,0,1,.875,1)));
        put(1,64,0,Material.WHITE_CARPET,List.of(new BoundingBox(0,0,0,1,.0625,1)));
        put(2,64,0,Material.STONE_SLAB,List.of(new BoundingBox(0,0,0,1,.5,1)));
        put(3,64,0,Material.STONE_SLAB,List.of(new BoundingBox(0,.5,0,1,1,1)));
        Node[] nodes={new Node(0,64,0),new Node(1,65,0),new Node(2,65,0),new Node(3,65,0)};
        double[] heights={63.875,64.0625,64.5,65};
        try(var ignored=terrain.beginUpdate()) {
            for(int i=0;i<nodes.length;i++) {
                assertTrue(terrain.stand(nodes[i]));
                assertEquals(heights[i],terrain.height(nodes[i]),1e-8);
                assertEquals(heights[i],terrain.supportHeight(i+.5,heights[i],.5),1e-8);
                assertEquals(nodes[i],terrain.near(new org.bukkit.Location(world,i+.5,heights[i],.5)));
                if(i>0) {
                    assertTrue(terrain.edge(nodes[i-1],nodes[i]));
                    assertTrue(terrain.edge(nodes[i],nodes[i-1]));
                }
            }
        }
    }

    @Test void lowestWorldFloorStillSupportsWalkingWithoutInspectingBelowItsBoundary() {
        put(0,-64,0,Material.STONE,List.of(new BoundingBox(0,0,0,1,1,1)));
        assertEquals(-63,terrain.supportHeight(.5,-63,.5),.000001);
        assertTrue(terrain.stand(new Node(0,-63,0)));
        assertTrue(terrain.fits(.5,-63,.5,false));
        assertEquals(new Node(0,-63,0),terrain.near(new org.bukkit.Location(world,.5,-63,.5)));
        verify(world,never()).getBlockAt(anyInt(),intThat(y->y < -64),anyInt());
    }

    @Test void repeatedMovementProbesShareVoxelShapesOnlyWithinTheCurrentUpdate() {
        Block floor=world.getBlockAt(0,63,0);clearInvocations(floor);
        try(var scope=terrain.beginUpdate()) {
            for(int i=0;i<12;i++) {
                assertEquals(64,terrain.supportHeight(.5,64,.5),.000001);
                assertTrue(terrain.fits(.5,64,.5,false));
            }
        }
        verify(floor,times(1)).getCollisionShape();
        put(0,63,0,Material.AIR,List.of());
        try(var scope=terrain.beginUpdate()) {
            assertTrue(Double.isNaN(terrain.supportHeight(.5,64,.5)),"removed floor must not survive into the next update");
        }
    }

    @Test void changedBlockDataRefreshesTheShapeDuringAnUpdate() {
        Block step=world.getBlockAt(0,64,0);
        var low=mock(VoxelShape.class);when(low.getBoundingBoxes()).thenReturn(List.of(new BoundingBox(0,0,0,1,.5,1)));
        var high=mock(VoxelShape.class);when(high.getBoundingBoxes()).thenReturn(List.of(new BoundingBox(0,0,0,1,1,1)));
        when(step.getType()).thenReturn(Material.STONE_SLAB);when(step.isPassable()).thenReturn(false);
        when(step.getBlockData()).thenReturn(mock(org.bukkit.block.data.type.Slab.class));
        when(step.getCollisionShape()).thenReturn(low);
        try(var scope=terrain.beginUpdate()) {
            assertEquals(64.5,terrain.supportHeight(.5,64,.5),.000001);
            when(step.getBlockData()).thenReturn(mock(org.bukkit.block.data.type.Slab.class));
            when(step.getCollisionShape()).thenReturn(high);
            assertEquals(65,terrain.supportHeight(.5,64,.5),.000001);
        }
    }

    @Test void anOpenDoorStillBlocksItsActualSwingingLeaf() {
        var data=mock(org.bukkit.block.data.type.Door.class);when(data.isOpen()).thenReturn(true);
        Block door=block(0,64,0,Material.OAK_DOOR,List.of(new BoundingBox(0,0,0,.1875,1,1)));
        when(door.getBlockData()).thenReturn(data);blocks.put(key(0,64,0),door);
        assertFalse(terrain.fits(.35,64,.5,false),"open leaf can still occupy the shoulder");
        assertTrue(terrain.fits(.5,64,.5,false),"the clear centre of the doorway remains passable");
    }
}
