package com.mdvcraft.mdvnpc.routine;

import com.mdvcraft.mdvnpc.MdvNpcPlugin;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.VoxelShape;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Executes controlled recovery against actual fractional collision boxes, without AI. */
class GravityMixedTerrainTest {
    @TempDir Path folder;
    RoutineTransitionTest f;
    RoutineGravity gravity;
    RoutineGravity.State state;
    MdvNpcPlugin plugin;

    @BeforeEach void setup() throws Exception {
        f=new RoutineTransitionTest();f.folder=folder;f.setup();
        when(f.entity.isInsideVehicle()).thenReturn(false);
        var field=RoutineService.class.getDeclaredField("plugin");field.setAccessible(true);
        plugin=(MdvNpcPlugin)field.get(f.service);
        plugin.settings().messages().set("routines.occasional-looking",false);
        gravity=new RoutineGravity(new DoorController(plugin),(npc,target)->npc.entity().teleport(target));
        state=new RoutineGravity.State();
    }
    @AfterEach void cleanup(){if(f!=null)f.cleanup();}

    private void shape(int x,int y,int z,Material material,BoundingBox... boxes) {
        Block block=mock(Block.class);when(block.getType()).thenReturn(material);
        when(block.isPassable()).thenReturn(material.isAir());
        VoxelShape shape=mock(VoxelShape.class);when(shape.getBoundingBoxes()).thenReturn(List.of(boxes));
        when(block.getCollisionShape()).thenReturn(shape);
        f.blocks.put(x+","+y+","+z,block);
    }
    private void floor(int y,Material material,double bottom,double top) {
        shape(0,y,0,material,new BoundingBox(0,bottom,0,1,top,1));
    }
    private RoutineGravity.Result settle(double speed,int cadence,int updates) {
        RoutineGravity.Result result=RoutineGravity.Result.LIFTING;
        for(int n=0;n<updates && result!=RoutineGravity.Result.STABLE;n++)
            result=gravity.tick(f.npc,state,(long)n*cadence,cadence,speed);
        return result;
    }
    private void assertVerticalSteps(Location initial,double maximum) {
        Location previous=initial;
        for(Location move:f.moves) {
            assertEquals(initial.getX(),move.getX(),1e-9);assertEquals(initial.getZ(),move.getZ(),1e-9);
            assertTrue(move.getY()>previous.getY(),"recovery must not alternate between two floors");
            assertTrue(move.getY()-previous.getY()<=maximum+1e-8,"upward recovery exceeds the configured speed");
            previous=move;
        }
        verify(f.entity,never()).setAI(anyBoolean());verify(f.entity,never()).setAware(anyBoolean());
    }

    @ParameterizedTest
    @CsvSource({"WHITE_CARPET,64,0,.0625,64,64.0625", "STONE_SLAB,64,0,.5,64,64.5",
            "STONE_SLAB,64,.5,1,64.5,65", "MUD,63,0,.875,63.75,63.875"})
    void shallowFractionalEmbeddingExitsOnlyThroughItsVerifiedSupport(Material material,int y,
            double bottom,double top,double feet,double target) {
        floor(y,material,bottom,top);f.position.setY(feet);Location initial=f.position.clone();
        assertEquals(RoutineGravity.Result.LIFTING,gravity.tick(f.npc,state,0,2,2.4));
        assertEquals(RoutineGravity.Result.STABLE,settle(2.4,2,30));
        assertEquals(target,f.position.getY(),1e-8);assertFalse(f.moves.isEmpty());
        assertVerticalSteps(initial,.24);
        assertTrue(new RoutineTerrain(f.world,new DoorController(plugin)).fits(.5,target,.5,false));
    }

    @Test void minimumSpeedFinishesTheCarpetLiftEvenAfterBodyClearanceBecomesValid() {
        floor(64,Material.WHITE_CARPET,0,.0625);Location initial=f.position.clone();
        assertEquals(RoutineGravity.Result.STABLE,settle(.2,1,30));
        assertEquals(64.0625,f.position.getY(),1e-8);
        assertEquals(7,f.moves.size(),"the final small remainder must not be mistaken for stable ground");
        assertVerticalSteps(initial,.01);
    }

    @Test void aMudSurfaceBelowTheFeetSettlesOnceAndDoesNotOscillate() {
        floor(63,Material.MUD,0,.875);
        assertEquals(RoutineGravity.Result.FALLING,gravity.tick(f.npc,state,0,2));
        assertEquals(63.875,f.position.getY(),1e-8);
        assertEquals(RoutineGravity.Result.STABLE,gravity.tick(f.npc,state,2,2));
        int count=f.moves.size();
        for(long tick=4;tick<100;tick+=2)assertEquals(RoutineGravity.Result.STABLE,gravity.tick(f.npc,state,tick,2));
        assertEquals(count,f.moves.size());assertEquals(63.875,f.position.getY(),1e-8);
    }

    @Test void shallowSupportRecoveryCannotRaiseTheBodyThroughACeiling() {
        floor(64,Material.STONE_SLAB,0,.5);
        floor(66,Material.STONE,0,1);
        assertEquals(RoutineGravity.Result.BLOCKED,gravity.tick(f.npc,state,0,2));
        assertEquals(64,f.position.getY());assertTrue(f.moves.isEmpty());
    }

    @ParameterizedTest @CsvSource({"STONE", "STONE_SLAB"})
    void aFullCubeOrDoubleSlabDoesNotBecomeAnEscapeFloor(Material material) {
        floor(64,material,0,1);f.position.setY(64.5);
        assertEquals(RoutineGravity.Result.BLOCKED,gravity.tick(f.npc,state,0,2));
        assertEquals(64.5,f.position.getY());assertTrue(f.moves.isEmpty());
    }

    @Test void aNoOpUpwardTeleportDoesNotClaimMovementOrContinuouslyProbe() {
        floor(64,Material.WHITE_CARPET,0,.0625);
        gravity=new RoutineGravity(new DoorController(plugin),(npc,target)->true);
        assertEquals(RoutineGravity.Result.BLOCKED,gravity.tick(f.npc,state,0,2));
        clearInvocations(f.world);
        for(int tick=1;tick<20;tick++)assertEquals(RoutineGravity.Result.BLOCKED,gravity.tick(f.npc,state,tick,1));
        verify(f.world,never()).getBlockAt(anyInt(),anyInt(),anyInt());assertEquals(64,f.position.getY());
    }

    @Test void removingThePartialFloorDuringALiftCancelsTheTargetAndReturnsToActualGround() {
        floor(64,Material.STONE_SLAB,0,.5);
        assertEquals(RoutineGravity.Result.LIFTING,gravity.tick(f.npc,state,0,2));
        assertEquals(64.24,f.position.getY(),1e-8);
        shape(0,64,0,Material.AIR);
        assertEquals(RoutineGravity.Result.FALLING,gravity.tick(f.npc,state,2,2));
        assertEquals(RoutineGravity.Result.STABLE,settle(2.4,2,30));
        assertTrue(Math.abs(f.position.getY()-64)<=.02,"retain the existing stable-floor contact tolerance");
    }

    private void eastStair() {
        shape(0,64,0,Material.STONE_STAIRS,new BoundingBox(0,0,0,1,.5,1),
                new BoundingBox(.5,.5,0,1,1,1));
    }

    @Test void aRestoredLowerTreadUsesTheSurfaceUnderItsActualFeetRatherThanTheTileCentre() {
        eastStair();f.position=new Location(f.world,.15,64.45,.5);Location initial=f.position.clone();
        assertEquals(RoutineGravity.Result.LIFTING,gravity.tick(f.npc,state,0,1,.2));
        assertEquals(RoutineGravity.Result.STABLE,settle(.2,1,30));
        assertEquals(64.5,f.position.getY(),1e-8);assertVerticalSteps(initial,.01);
        assertTrue(new RoutineTerrain(f.world,new DoorController(plugin)).fits(.15,64.5,.5,false));
    }

    @Test void aLowerTreadEscapeStillStopsWhenTheBodyOverlapsTheHigherRiser() {
        eastStair();f.position=new Location(f.world,.3,64.45,.5);
        assertEquals(RoutineGravity.Result.BLOCKED,gravity.tick(f.npc,state,0,1,.2));
        assertEquals(64.45,f.position.getY());assertTrue(f.moves.isEmpty());
    }

    @Test void aStraddlingNpcCanExitMatchingCarpetSurfacesOnBothSidesOfATileBoundary() {
        floor(64,Material.WHITE_CARPET,0,.0625);
        shape(1,64,0,Material.WHITE_CARPET,new BoundingBox(0,0,0,1,.0625,1));
        f.position=new Location(f.world,.98,64,.5);Location initial=f.position.clone();
        assertEquals(RoutineGravity.Result.STABLE,settle(.2,1,30));
        assertEquals(64.0625,f.position.getY(),1e-8);assertFalse(f.moves.isEmpty());
        assertVerticalSteps(initial,.01);
        assertTrue(new RoutineTerrain(f.world,new DoorController(plugin)).fits(.98,64.0625,.5,false));
    }

    @Test void aWorkerBlockedByANewPartialFloorRecoversAndRejoinsAtTheFractionalHeight() throws Exception {
        f.service.repository().put("thurg",f.goal(1,RoutineGoal.Type.WORK,RoutineGoal.WalkMode.CYCLE,0,0,0).withSpeed(.2));
        plugin.settings().messages().set("routines.movement-interval-ticks",1);
        f.service.start();f.advance(1);assertTrue(f.service.canInteract(f.npc));
        floor(64,Material.WHITE_CARPET,0,.0625);f.moves.clear();Location initial=f.position.clone();
        f.advance(1);assertFalse(f.service.canInteract(f.npc));
        f.advance(60);
        assertEquals(64.0625,f.position.getY(),1e-8);
        assertTrue(f.service.canInteract(f.npc),f.service.status("thurg"));
        assertVerticalSteps(initial,.01);
    }

    @Test void gravityLeavesANavigatorsHeldVerticalStepInControlDuringWaiting() throws Exception {
        f.service.repository().put("thurg",f.goal(1,RoutineGoal.Type.WALK,RoutineGoal.WalkMode.CYCLE,0,0,8));
        f.service.start();f.advance(2);
        RoutineNavigator navigator=mock(RoutineNavigator.class);
        when(navigator.move(any(),any(),anyDouble(),anyLong(),anyInt())).thenReturn(RoutineNavigator.Result.WAITING);
        when(navigator.controlsVerticalStep("thurg")).thenReturn(true);
        var field=RoutineService.class.getDeclaredField("navigator");field.setAccessible(true);field.set(f.service,navigator);
        f.position.setY(64.3);Location staged=f.position.clone();f.moves.clear();clearInvocations(f.world);
        f.advance(12);
        assertEquals(staged,f.position,"gravity must not lower a held stair frame to the old floor");
        assertTrue(f.moves.isEmpty());verify(navigator,never()).cancel("thurg");
        verify(f.world,never()).getBlockAt(anyInt(),anyInt(),anyInt());
    }
}
