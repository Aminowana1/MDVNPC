package com.mdvcraft.mdvnpc.work;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Levelled;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.VoxelShape;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiPredicate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BoatNavigatorTest {
    private record Cell(int x,int y,int z) {}
    private World world;
    private BoatNavigator navigator;
    private BiPredicate<Integer,Integer> water=(x,z)->Math.abs(x)<12 && Math.abs(z)<12;
    private BiPredicate<Integer,Integer> loaded=(x,z)->true;
    private final Map<Cell,Block> blocks=new HashMap<>();
    @BeforeEach void setup() {
        MockBukkit.mock();world=mock(World.class);navigator=new BoatNavigator(world);
        when(world.getUID()).thenReturn(UUID.randomUUID());when(world.getName()).thenReturn("lake");
        when(world.getMinHeight()).thenReturn(0);when(world.getMaxHeight()).thenReturn(128);
        when(world.isChunkLoaded(anyInt(),anyInt())).thenAnswer(c->loaded.test(c.getArgument(0),c.getArgument(1)));
        when(world.getBlockAt(anyInt(),anyInt(),anyInt())).thenAnswer(c->{
            int x=c.getArgument(0),y=c.getArgument(1),z=c.getArgument(2);
            assertTrue(loaded.test(x>>4,z>>4),"Never read an unloaded chunk");
            Cell cell=new Cell(x,y,z);
            return blocks.computeIfAbsent(cell,k->block(k,y==63 && water.test(x,z)?Material.WATER:y<=63?Material.STONE:Material.AIR));
        });
    }
    @AfterEach void cleanup(){MockBukkit.unmock();}
    private Block block(Cell cell,Material material) {
        Block b=mock(Block.class);when(b.getType()).thenReturn(material);
        VoxelShape shape=mock(VoxelShape.class);
        when(shape.getBoundingBoxes()).thenReturn(material.isAir() || material==Material.WATER?List.of():List.of(new BoundingBox(0,0,0,1,1,1)));
        when(b.getCollisionShape()).thenReturn(shape);
        if(material==Material.WATER){Levelled level=mock(Levelled.class);when(level.getLevel()).thenReturn(0);when(b.getBlockData()).thenReturn(level);}
        return b;
    }
    private Location at(double x,double z){return new Location(world,x,63.7,z);}
    private void obstruction(int x,int y,int z,BoundingBox shape) {
        Cell key=new Cell(x,y,z);Block b=block(key,Material.GLASS_PANE);VoxelShape voxel=mock(VoxelShape.class);
        when(voxel.getBoundingBoxes()).thenReturn(List.of(shape));when(b.getCollisionShape()).thenReturn(voxel);blocks.put(key,b);
    }
    private void assertRouteClear(Location from,List<Location> route) {
        assertFalse(route.isEmpty());Location prior=from;
        for(Location next:route){assertTrue(navigator.segment(prior,next),"Every sailed segment clears the hull and rider");prior=next;}
    }
    @Test void positionUsesTheActualWaterSurfaceAndAcceptsOneBlockDeepWater() {
        Location position=navigator.position(new Location(world,.5,65,.5,90,0));
        assertNotNull(position);assertEquals(63.7,position.getY(),1e-8);assertEquals(90,position.getYaw());
        assertTrue(navigator.valid(position));
    }
    @Test void bobberCanLandOnWaterButNotLandOrUnderASolidCover() {
        assertEquals(64.04,navigator.waterTarget(4.5,.5,64).getY(),1e-8);
        assertNull(navigator.waterTarget(20,.5,64));
        obstruction(4,64,0,new BoundingBox(0,0,0,1,1,1));assertNull(navigator.waterTarget(4.5,.5,64));
    }
    @Test void banksAreCheckedAcrossTheFullBoatFootprint() {
        water=(x,z)->x>=0 && x<=2 && Math.abs(z)<10;
        assertFalse(navigator.valid(at(.5,0)));assertTrue(navigator.valid(at(1.5,0)));
    }
    @Test void aTwoBlockWideChannelSupportsARealBoatWithoutRoundingItIntoTheBank() {
        water=(x,z)->x>=0 && x<=1 && z>=-4 && z<=10;
        Location start=at(1,0),end=at(1,7);
        assertTrue(navigator.valid(start));List<Location> route=navigator.route(start,end);assertRouteClear(start,route);
        assertEquals(end,route.getLast());for(Location node:route)assertEquals(1,node.getX(),1e-8);
    }
    @Test void oneBlockWideChannelCannotFitTheBoat() {
        water=(x,z)->x==0 && Math.abs(z)<10;
        assertTrue(navigator.route(at(.5,0),at(.5,6)).isEmpty());
    }
    @Test void routeDetoursAroundAnIslandRatherThanCrossingIt() {
        water=(x,z)->Math.abs(x)<8 && Math.abs(z)<8 && !(x>=-1 && x<=1 && z>=-1 && z<=1);
        Location start=at(-4,0),end=at(5,0);
        assertFalse(navigator.segment(start,end));List<Location> route=navigator.route(start,end);assertRouteClear(start,route);
        assertTrue(route.stream().anyMatch(p->Math.abs(p.getZ())>1.7));assertEquals(end,route.getLast());
    }
    @Test void aDisconnectedLakeReturnsNoRoute() {
        water=(x,z)->Math.abs(x)<7 && Math.abs(z)<7 && x!=0;
        assertTrue(navigator.route(at(-3,0),at(4,0)).isEmpty());
    }
    @Test void aGlassPaneAndALowBridgeBlockTheActualHullOrRiderVolume() {
        obstruction(0,64,0,new BoundingBox(.45,0,0,.55,1,1));assertFalse(navigator.valid(at(.5,.5)));
        blocks.remove(new Cell(0,64,0));obstruction(0,65,0,new BoundingBox(0,.5,0,1,1,1));
        assertFalse(navigator.valid(at(.5,.5)));
    }
    @Test void thinWallCannotBeSkippedBetweenTwoLegalBoatPositions() {
        obstruction(0,64,0,new BoundingBox(.49,0,0,.51,1,1));
        Location from=at(-.4,.5),to=at(1.4,.5);
        assertTrue(navigator.valid(from));assertTrue(navigator.valid(to));assertFalse(navigator.segment(from,to));
    }
    @Test void unloadedFootprintOrTargetNeverReadsOrLoadsThatChunk() {
        water=(x,z)->true;loaded=(x,z)->x==0 && z==0;
        assertFalse(navigator.valid(at(15.5,8)));assertTrue(navigator.route(at(8,8),at(20,8)).isEmpty());
        verify(world,never()).getChunkAt(anyInt(),anyInt());
        verify(world,never()).getBlockAt(intThat(x->x>=16),anyInt(),anyInt());
    }
    @Test void changedWaterAndNewObstaclesAreRecheckedOnTheNextQuery() {
        assertFalse(navigator.route(at(-2,0),at(2,0)).isEmpty());
        water=(x,z)->false;blocks.clear();assertTrue(navigator.route(at(-2,0),at(2,0)).isEmpty());
    }
    @Test void flowingWaterIsNotUsedAsAStableSailingSurface() {
        Block moving=block(new Cell(0,63,0),Material.WATER);Levelled level=mock(Levelled.class);when(level.getLevel()).thenReturn(4);
        when(moving.getBlockData()).thenReturn(level);blocks.put(new Cell(0,63,0),moving);
        assertFalse(navigator.valid(at(.5,.5)));
    }
    @Test void underwaterPlantsStillProvideWaterAndDifferentLevelsCannotBeCrossed() {
        blocks.put(new Cell(0,63,0),block(new Cell(0,63,0),Material.SEAGRASS));assertTrue(navigator.valid(at(.5,.5)));
        assertFalse(navigator.segment(at(0,0),new Location(world,2,64.7,0)));
    }
    @Test void wrongWorldNonFiniteAndOverlongSegmentsAreRejected() {
        assertFalse(navigator.valid(new Location(mock(World.class),.5,63.7,.5)));
        assertFalse(navigator.valid(new Location(world,Double.NaN,63.7,.5)));
        assertFalse(navigator.segment(at(0,0),at(10000000,0)));
    }
    @Test void castingArcCannotPassThroughAWallEvenWhenItsWaterEndpointIsValid() {
        Location start=new Location(world,-3.5,65.25,.5),end=navigator.waterTarget(1,.5,64);
        assertNotNull(end);assertTrue(navigator.castClear(start,end));
        obstruction(-1,64,0,new BoundingBox(.49,0,0,.51,1,1));
        obstruction(-1,65,0,new BoundingBox(.49,0,0,.51,1,1));
        assertNotNull(navigator.waterTarget(1,.5,64));assertFalse(navigator.castClear(start,end));
    }
}
