package com.mdvcraft.mdvnpc.routine;

import com.destroystokyo.paper.entity.Pathfinder;
import com.mdvcraft.mdvnpc.config.NpcParser;
import com.mdvcraft.mdvnpc.runtime.ActiveNpc;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Villager;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RoutineNavigatorTest {
    public interface NativeHandleAccess { NativeHandle getHandle(); }
    public static final class NativeHandle {
        boolean onGround;
        final List<Boolean> changes = new ArrayList<>();
        public void setOnGround(boolean value) { onGround = value; changes.add(value); }
    }
    World world;
    RoutineNavigator navigator;
    final Map<String, Location> positions = new HashMap<>();
    final Map<String, Pathfinder> pathfinders = new HashMap<>();

    @BeforeEach void setup() {
        MockBukkit.mock();
        world = mock(World.class); when(world.getUID()).thenReturn(UUID.randomUUID());
        when(world.getMinHeight()).thenReturn(-64); when(world.getMaxHeight()).thenReturn(320);
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(call -> {
            int x = call.getArgument(0), y = call.getArgument(1), z = call.getArgument(2);
            Block block = mock(Block.class);
            when(block.getType()).thenReturn(y == 63 ? Material.STONE : Material.AIR);
            when(block.getBoundingBox()).thenReturn(y == 63 ? new BoundingBox(x, y, z, x + 1, y + 1, z + 1)
                    : new BoundingBox(x, y, z, x, y, z));
            when(block.isPassable()).thenReturn(y != 63);
            return block;
        });
        DoorController doors = mock(DoorController.class);
        when(doors.openNear(any(), any(), anyLong())).thenReturn(true);
        navigator = new RoutineNavigator(doors, (npc, to) -> {
            positions.put(npc.definition().id(), to.clone()); return true;
        });
    }
    @AfterEach void cleanup() { navigator.clear(); MockBukkit.unmock(); }

    private ActiveNpc npc(String id) {
        return npc(id, null);
    }
    private ActiveNpc npc(String id, NativeHandle handle) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("npcs." + id + ".location.world", "world"); yaml.set("npcs." + id + ".location.y", 64);
        Villager entity = handle == null ? mock(Villager.class)
                : mock(Villager.class, withSettings().extraInterfaces(NativeHandleAccess.class));
        Pathfinder finder = mock(Pathfinder.class);
        when(entity.getWorld()).thenReturn(world); when(entity.isValid()).thenReturn(true);
        when(entity.isOnGround()).thenReturn(true); when(entity.getPathfinder()).thenReturn(finder);
        if (handle != null) {
            when(((NativeHandleAccess)entity).getHandle()).thenReturn(handle);
            when(entity.isOnGround()).thenAnswer(call -> handle.onGround);
        }
        Location at = new Location(world, .5, 64, .5); positions.put(id, at);
        when(entity.getLocation()).thenAnswer(call -> positions.get(id).clone()); pathfinders.put(id, finder);
        Pathfinder.PathResult path = mock(Pathfinder.PathResult.class);
        when(path.getPoints()).thenReturn(List.of(new Location(world, 0, 64, 0), new Location(world, 1, 64, 1), new Location(world, 2, 64, 2)));
        when(path.canReachFinalPoint()).thenReturn(true); when(finder.findPath(any(Location.class))).thenReturn(path);
        return new ActiveNpc(NpcParser.parse(yaml).get(id), at.clone(), entity, null);
    }
    private Location destination() { return new Location(world, 2.5, 64, 2.5); }

    @Test void nativeDiagonalRouteIsCalculatedOnceAndFollowedWithoutWakingVillagerBrain() {
        ActiveNpc npc = npc("one");
        assertEquals(RoutineNavigator.Result.MOVING, navigator.move(npc, destination(), 2.4, 0, 2));
        Location step = positions.get("one");
        assertTrue(step.getX() > .5 && step.getZ() > .5);
        assertEquals(.24, step.distance(npc.anchor()), .00001);
        RoutineNavigator.Result result = RoutineNavigator.Result.MOVING;
        for (int tick = 2; tick < 80 && result != RoutineNavigator.Result.ARRIVED; tick += 2)
            result = navigator.move(npc, destination(), 2.4, tick, 2);
        assertEquals(RoutineNavigator.Result.ARRIVED, result);
        verify(pathfinders.get("one"), times(1)).findPath(any(Location.class));
        verify(npc.entity(), never()).setAI(anyBoolean()); verify(npc.entity(), never()).setAware(anyBoolean());
        assertEquals(0, navigator.activeRoutes()); assertEquals(0, navigator.searches());
    }

    @Test void twentyFiveDeparturesShareTwoNativeSearchesPerUpdate() {
        List<ActiveNpc> npcs = new ArrayList<>();
        for (int i = 0; i < 25; i++) npcs.add(npc("npc" + i));
        navigator.beginTick(0, 2, Long.MAX_VALUE);
        for (ActiveNpc npc : npcs) navigator.move(npc, destination(), 2.4, 0, 2);
        assertEquals(2, navigator.activeRoutes()); assertEquals(23, navigator.searches());
        navigator.beginTick(2, 2, Long.MAX_VALUE);
        for (ActiveNpc npc : npcs) navigator.move(npc, destination(), 2.4, 2, 2);
        assertEquals(4, navigator.activeRoutes()); assertEquals(21, navigator.searches());
        int calls = pathfinders.values().stream().mapToInt(f -> (int)mockingDetails(f).getInvocations().stream()
                .filter(call -> call.getMethod().getName().equals("findPath")).count()).sum();
        assertEquals(4, calls);
    }

    @Test void failedNativeRouteWidensQuicklyBeforeUsingTheLongImpossibleRetry() {
        ActiveNpc npc = npc("blocked"); Pathfinder finder = pathfinders.get("blocked");
        when(finder.findPath(any(Location.class))).thenReturn(null);
        assertEquals(RoutineNavigator.Result.WAITING,navigator.move(npc,destination(),2.4,0,2));
        assertEquals(RoutineNavigator.Result.WAITING,navigator.move(npc,destination(),2.4,4,2));
        assertEquals(RoutineNavigator.Result.WAITING,navigator.move(npc,destination(),2.4,8,2));
        verify(finder,times(3)).findPath(any(Location.class));
        assertTrue(navigator.exhausted("blocked"));
        navigator.move(npc,destination(),2.4,100,2);
        verify(finder,times(3)).findPath(any(Location.class));
        navigator.move(npc,destination(),2.4,108,2);
        verify(finder,times(4)).findPath(any(Location.class));
    }

    @Test void unloadedDestinationNeverRequestsPaperPathOrReadsItsBlocks() {
        ActiveNpc npc = npc("unloaded"); when(world.isChunkLoaded(2, 0)).thenReturn(false);
        assertEquals(RoutineNavigator.Result.WAITING, navigator.move(npc, new Location(world, 32.5, 64, .5), 2.4, 0, 2));
        verify(pathfinders.get("unloaded"), never()).findPath(any(Location.class));
        verify(world, never()).getBlockAt(anyInt(), anyInt(), anyInt());
        verify(world, never()).getChunkAt(anyInt(), anyInt());
    }

    @Test void unrelatedUnloadedNeighbourChunkDoesNotBlockTheNativeQuery() {
        ActiveNpc npc = npc("boundary"); when(world.isChunkLoaded(-2, -2)).thenReturn(false);
        assertEquals(RoutineNavigator.Result.MOVING, navigator.move(npc, destination(), 2.4, 0, 2));
        verify(pathfinders.get("boundary"), times(1)).findPath(any(Location.class));
        verify(world, never()).getChunkAt(anyInt(), anyInt());
    }


    @Test void differentFloorPartialPathIsWidenedInsteadOfWalkingUnderTheGoal() {
        ActiveNpc npc=npc("upstairs");
        var range=mock(org.bukkit.attribute.AttributeInstance.class);
        when(npc.entity().getAttribute(org.bukkit.attribute.Attribute.FOLLOW_RANGE)).thenReturn(range);
        when(range.getBaseValue()).thenReturn(48.0);
        Pathfinder.PathResult partial=mock(Pathfinder.PathResult.class);
        when(partial.getPoints()).thenReturn(List.of(new Location(world,0,64,0),new Location(world,1,64,0)));
        when(partial.canReachFinalPoint()).thenReturn(false);
        when(pathfinders.get("upstairs").findPath(any(Location.class))).thenReturn(partial);
        Location upstairs=new Location(world,.5,68,.5);

        assertEquals(RoutineNavigator.Result.WAITING,navigator.move(npc,upstairs,2.4,0,2));
        assertEquals(new Location(world,.5,64,.5),positions.get("upstairs"));
        verify(range).setBaseValue(32.0);

        assertEquals(RoutineNavigator.Result.WAITING,navigator.move(npc,upstairs,2.4,4,2));
        assertEquals(new Location(world,.5,64,.5),positions.get("upstairs"));
        verify(range,atLeastOnce()).setBaseValue(48.0);

        assertEquals(RoutineNavigator.Result.WAITING,navigator.move(npc,upstairs,2.4,8,2));
        assertEquals(new Location(world,.5,64,.5),positions.get("upstairs"));
        verify(range).setBaseValue(64.0);
        assertTrue(navigator.exhausted("upstairs"),"at max range the vertical dead-end must be rejected, not walked into");
    }

    @Test void nativeSearchRangeIsBoundedAndRestoredAfterTheQuery() {
        ActiveNpc npc = npc("range"); var range = mock(org.bukkit.attribute.AttributeInstance.class);
        when(npc.entity().getAttribute(org.bukkit.attribute.Attribute.FOLLOW_RANGE)).thenReturn(range);
        when(range.getBaseValue()).thenReturn(48.0); when(range.getValue()).thenReturn(16.0);
        navigator.move(npc, destination(), 2.4, 0, 2);
        var order = inOrder(range, pathfinders.get("range"));
        order.verify(range).setBaseValue(16.0);
        order.verify(pathfinders.get("range")).findPath(any(Location.class));
        order.verify(range).setBaseValue(48.0);
    }

    @Test void partialRouteThatDoesNotGetCloserWidensTheNextSearchInsteadOfBeingRejected() {
        ActiveNpc npc=npc("detour");var range=mock(org.bukkit.attribute.AttributeInstance.class);
        when(npc.entity().getAttribute(org.bukkit.attribute.Attribute.FOLLOW_RANGE)).thenReturn(range);
        when(range.getBaseValue()).thenReturn(48.0);
        Pathfinder.PathResult first=mock(Pathfinder.PathResult.class);
        when(first.getPoints()).thenReturn(List.of(new Location(world,0,64,0),new Location(world,-1,64,0)));
        when(first.canReachFinalPoint()).thenReturn(false);
        Pathfinder.PathResult second=mock(Pathfinder.PathResult.class);
        when(second.getPoints()).thenReturn(List.of(new Location(world,-1,64,0),new Location(world,2,64,2)));
        when(second.canReachFinalPoint()).thenReturn(true);
        when(pathfinders.get("detour").findPath(any(Location.class))).thenReturn(first,second);
        Location goal=destination();
        RoutineNavigator.Result result=RoutineNavigator.Result.MOVING;
        for(int tick=0;tick<80 && mockingDetails(pathfinders.get("detour")).getInvocations().stream()
                .filter(call->call.getMethod().getName().equals("findPath")).count()<2;tick+=2)
            result=navigator.move(npc,goal,2.4,tick,2);
        assertNotEquals(RoutineNavigator.Result.ARRIVED,result);
        verify(range,atLeastOnce()).setBaseValue(16.0);
        verify(range,atLeastOnce()).setBaseValue(24.0);
    }

    @Test void usefulPartialRouteDoesNotReportArrivalAtItsEndpoint() {
        ActiveNpc npc = npc("partial"); Pathfinder.PathResult path = mock(Pathfinder.PathResult.class);
        when(path.getPoints()).thenReturn(List.of(new Location(world, 0, 64, 0), new Location(world, 1, 64, 1)));
        when(pathfinders.get("partial").findPath(any(Location.class))).thenReturn(path);
        Location far = new Location(world, 8.5, 64, 8.5);
        for (int tick = 0; tick <= 14; tick += 2)
            assertNotEquals(RoutineNavigator.Result.ARRIVED, navigator.move(npc, far, 2.4, tick, 2));
        verify(pathfinders.get("partial"), times(1)).findPath(any(Location.class));
        assertEquals(0, navigator.activeRoutes()); assertEquals(1, navigator.searches());
    }

    @Test void disabledPhysicsUsesScopedGroundFlagAndStartsOnTheFirstUpdate() {
        NativeHandle handle = new NativeHandle(); ActiveNpc npc = npc("grounding", handle);
        when(pathfinders.get("grounding").findPath(any(Location.class))).thenAnswer(call -> {
            assertTrue(handle.onGround);
            Pathfinder.PathResult path = mock(Pathfinder.PathResult.class);
            when(path.getPoints()).thenReturn(List.of(new Location(world, 0, 64, 0), new Location(world, 2, 64, 2)));
            return path;
        });
        assertEquals(RoutineNavigator.Result.MOVING, navigator.move(npc, destination(), 2.4, 0, 2));
        assertFalse(handle.onGround); assertEquals(List.of(true, false), handle.changes);
        verify(pathfinders.get("grounding")).findPath(any(Location.class));
        verify(npc.entity(), never()).setAI(anyBoolean()); verify(npc.entity(), never()).setAware(anyBoolean());
        verify(npc.entity(), never()).setGravity(anyBoolean()); verify(npc.entity(), never()).setVelocity(any(Vector.class));
        navigator.cancel("grounding");
        assertEquals(0, navigator.searches());
    }

    @Test void nativeGroundFlagIsRestoredEvenWhenPaperThrows() {
        NativeHandle handle = new NativeHandle(); ActiveNpc npc = npc("throwing", handle);
        IllegalStateException problem = new IllegalStateException("native path error");
        when(pathfinders.get("throwing").findPath(any(Location.class))).thenAnswer(call -> {
            assertTrue(handle.onGround); throw problem;
        });
        assertSame(problem, assertThrows(IllegalStateException.class,
                () -> navigator.move(npc, destination(), 2.4, 0, 2)));
        assertFalse(handle.onGround); assertEquals(List.of(true, false), handle.changes);
        verify(npc.entity(), never()).setAI(anyBoolean()); verify(npc.entity(), never()).setGravity(anyBoolean());
    }

    @Test void unavailableNativeBridgeFailsExplicitlyRatherThanWaitingForDisabledPhysics() {
        ActiveNpc npc = npc("unsupported"); when(npc.entity().isOnGround()).thenReturn(false);
        IllegalStateException problem = assertThrows(IllegalStateException.class,
                () -> navigator.move(npc, destination(), 2.4, 0, 2));
        assertTrue(problem.getMessage().contains("getHandle/setOnGround"));
        verify(pathfinders.get("unsupported"), never()).findPath(any(Location.class));
        verify(npc.entity(), never()).setGravity(anyBoolean()); verify(npc.entity(), never()).setVelocity(any(Vector.class));
    }

    @Test void nonexistentFloorCannotBeMarkedGroundedOrStartNativeSearch() {
        NativeHandle handle = new NativeHandle(); ActiveNpc npc = npc("floating", handle);
        Block air = mock(Block.class); when(air.getType()).thenReturn(Material.AIR);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenReturn(air);
        assertEquals(RoutineNavigator.Result.WAITING, navigator.move(npc, destination(), 2.4, 0, 2));
        assertTrue(handle.changes.isEmpty()); verify(pathfinders.get("floating"), never()).findPath(any(Location.class));
    }

    @Test void floorPreparationSettlesVerticallyAtWalkingSpeedBeforeQueryingPaper() {
        ActiveNpc npc=npc("settling");Location previous=new Location(world,.5,64.8,.5);
        positions.put("settling",previous.clone());
        assertEquals(RoutineNavigator.Result.MOVING,navigator.move(npc,destination(),2.4,0,2));
        Location step=positions.get("settling");assertEquals(64.56,step.getY(),.000001);
        assertTrue(previous.distance(step)<=.240001);verify(pathfinders.get("settling"),never()).findPath(any(Location.class));
        previous=step;
        for(int tick=2;tick<=10;tick+=2) {
            navigator.move(npc,destination(),2.4,tick,2);step=positions.get("settling");
            assertTrue(previous.distance(step)<=.240001,"vertical preparation must not add a hidden full-block snap");previous=step;
        }
        assertEquals(64,step.getY(),.000001);verify(pathfinders.get("settling"),times(1)).findPath(any(Location.class));
    }

    @Test void floorPreparationDoesNotSnapAnEmbeddedNpcThroughSolidBlocks() {
        ActiveNpc npc=npc("embedded");Location original=new Location(world,.5,63.3,.5);positions.put("embedded",original.clone());
        assertEquals(RoutineNavigator.Result.WAITING,navigator.move(npc,destination(),2.4,0,2));
        assertEquals(original,positions.get("embedded"));verify(pathfinders.get("embedded"),never()).findPath(any(Location.class));
    }

    private void steppedTerrain(boolean stairs,boolean lowCeiling) {
        when(world.getBlockAt(anyInt(),anyInt(),anyInt())).thenAnswer(call->{
            int x=call.getArgument(0),y=call.getArgument(1),z=call.getArgument(2);
            List<BoundingBox> boxes=List.of();Material material=Material.AIR;
            if(y==63 || x>=3 && y<=65 && y>=64 || x==2 && y==64 || lowCeiling && y==66) {
                material=Material.STONE;boxes=List.of(new BoundingBox(0,0,0,1,1,1));
            } else if((x==1 && y==64 || x==2 && y==65) && z==0) {
                material=stairs?Material.OAK_STAIRS:Material.STONE;
                boxes=stairs?List.of(new BoundingBox(0,0,0,1,.5,1),new BoundingBox(.5,.5,0,1,1,1))
                        :List.of(new BoundingBox(0,0,0,1,1,1));
            }
            Block block=mock(Block.class);when(block.getType()).thenReturn(material);
            when(block.isPassable()).thenReturn(boxes.isEmpty());
            var shape=mock(org.bukkit.util.VoxelShape.class);when(shape.getBoundingBoxes()).thenReturn(boxes);
            when(block.getCollisionShape()).thenReturn(shape);
            // Enclosing box deliberately loses the stair treads; production must use voxels.
            when(block.getBoundingBox()).thenReturn(new BoundingBox(x,y,z,x+1,y+1,z+1));
            return block;
        });
    }
    private void staircasePath(ActiveNpc npc,boolean reversed) {
        var path=mock(Pathfinder.PathResult.class);
        List<Location> points=new ArrayList<>(List.of(new Location(world,0,64,0),new Location(world,1,65,0),
                new Location(world,2,66,0),new Location(world,3,66,0)));
        if(reversed)Collections.reverse(points);
        when(path.getPoints()).thenReturn(points);when(pathfinders.get(npc.definition().id()).findPath(any(Location.class))).thenReturn(path);
    }
    private List<Location> walkTo(ActiveNpc npc,Location goal) {
        List<Location> visited=new ArrayList<>();Location previous=npc.entity().getLocation();
        RoutineNavigator.Result result=RoutineNavigator.Result.MOVING;
        for(int tick=0;tick<240 && result!=RoutineNavigator.Result.ARRIVED;tick+=2) {
            result=navigator.move(npc,goal,2.4,tick,2);Location next=npc.entity().getLocation();
            assertNotEquals(RoutineNavigator.Result.WAITING,result,"valid steps must not discard the route");
            assertTrue(previous.distance(next)<=.240001,"horizontal and vertical legs share the walking allowance");
            assertTrue(new RoutineTerrain(world,mock(DoorController.class)).fits(next.getX(),next.getY(),next.getZ(),false));
            visited.add(next.clone());previous=next;
        }
        assertEquals(RoutineNavigator.Result.ARRIVED,result);return visited;
    }
    @Test void diagonalPaperWaypointSlidesAlongAnAxisInsteadOfStickingOnACorner() {
        ActiveNpc npc=npc("corner");
        when(world.getBlockAt(anyInt(),anyInt(),anyInt())).thenAnswer(call->{
            int x=call.getArgument(0),y=call.getArgument(1),z=call.getArgument(2);
            boolean solid=y==63 || x==1 && y==64 && z==0;
            Block block=mock(Block.class);when(block.getType()).thenReturn(solid?Material.STONE:Material.AIR);
            when(block.isPassable()).thenReturn(!solid);
            when(block.getBoundingBox()).thenReturn(solid?new BoundingBox(x,y,z,x+1,y+1,z+1):new BoundingBox(x,y,z,x,y,z));
            return block;
        });
        Pathfinder.PathResult path=mock(Pathfinder.PathResult.class);
        when(path.getPoints()).thenReturn(List.of(new Location(world,0,64,0),new Location(world,1,64,1),new Location(world,2,64,2)));
        when(path.canReachFinalPoint()).thenReturn(true);
        when(pathfinders.get("corner").findPath(any(Location.class))).thenReturn(path);
        RoutineNavigator.Result result=RoutineNavigator.Result.MOVING;
        boolean axisSlide=false;
        Location previous=positions.get("corner").clone();
        for(int tick=0;tick<120 && result!=RoutineNavigator.Result.ARRIVED;tick+=2) {
            result=navigator.move(npc,destination(),2.4,tick,2);
            assertNotEquals(RoutineNavigator.Result.WAITING,result,"a legal route must recover from a tight diagonal corner locally");
            Location now=positions.get("corner");
            double dx=Math.abs(now.getX()-previous.getX()),dz=Math.abs(now.getZ()-previous.getZ());
            if(dx<.001 && dz>.001 || dz<.001 && dx>.001)axisSlide=true;
            previous=now.clone();
        }
        assertEquals(RoutineNavigator.Result.ARRIVED,result);
        assertTrue(axisSlide,"the replay should use an axis slide when the direct diagonal clips the wall");
        verify(pathfinders.get("corner"),times(1)).findPath(any(Location.class));
    }


    private void partialHeightTerrain(Material lowered,double top) {
        when(world.getBlockAt(anyInt(),anyInt(),anyInt())).thenAnswer(call->{
            int x=call.getArgument(0),y=call.getArgument(1),z=call.getArgument(2);
            boolean floor=y==63;
            Material material=floor?(x==1?lowered:Material.STONE):Material.AIR;
            List<BoundingBox> boxes=floor?List.of(new BoundingBox(0,0,0,1,x==1?top:1,1)):List.of();
            Block block=mock(Block.class);when(block.getType()).thenReturn(material);
            when(block.isPassable()).thenReturn(boxes.isEmpty());
            var shape=mock(org.bukkit.util.VoxelShape.class);when(shape.getBoundingBoxes()).thenReturn(boxes);
            when(block.getCollisionShape()).thenReturn(shape);
            when(block.getBoundingBox()).thenReturn(boxes.isEmpty()?new BoundingBox(x,y,z,x,y,z)
                    :new BoundingBox(x,y,z,x+1,y+(x==1?top:1),z+1));
            return block;
        });
    }

    private void straightThreeBlockPath(ActiveNpc npc) {
        var path=mock(Pathfinder.PathResult.class);
        when(path.getPoints()).thenReturn(List.of(new Location(world,0,64,0),new Location(world,1,64,0),
                new Location(world,2,64,0)));
        when(path.canReachFinalPoint()).thenReturn(true);
        when(pathfinders.get(npc.definition().id()).findPath(any(Location.class))).thenReturn(path);
    }

    @Test void dirtPathDipSettlesAndClimbsOutWithoutReplanningOrSpinning() {
        ActiveNpc npc=npc("pathdip");partialHeightTerrain(Material.DIRT_PATH,.9375);straightThreeBlockPath(npc);
        List<Location> visited=walkTo(npc,new Location(world,2.5,64,.5));
        assertTrue(visited.stream().anyMatch(p->p.getY()<63.99),"the NPC must settle onto the path surface");
        verify(pathfinders.get("pathdip"),times(1)).findPath(any(Location.class));
    }

    @Test void mudDipSettlesAndClimbsOutWithoutReplanningOrSpinning() {
        ActiveNpc npc=npc("muddip");partialHeightTerrain(Material.MUD,.875);straightThreeBlockPath(npc);
        List<Location> visited=walkTo(npc,new Location(world,2.5,64,.5));
        assertTrue(visited.stream().anyMatch(p->p.getY()<63.95),"the NPC must settle onto the mud surface");
        verify(pathfinders.get("muddip"),times(1)).findPath(any(Location.class));
    }


    private void oneBlockDipTerrain() {
        when(world.getBlockAt(anyInt(),anyInt(),anyInt())).thenAnswer(call->{
            int x=call.getArgument(0),y=call.getArgument(1),z=call.getArgument(2);
            boolean solid=x==1?y==62:y==63;
            Block block=mock(Block.class);when(block.getType()).thenReturn(solid?Material.STONE:Material.AIR);
            when(block.isPassable()).thenReturn(!solid);
            var shape=mock(org.bukkit.util.VoxelShape.class);
            when(shape.getBoundingBoxes()).thenReturn(solid?List.of(new BoundingBox(0,0,0,1,1,1)):List.of());
            when(block.getCollisionShape()).thenReturn(shape);
            when(block.getBoundingBox()).thenReturn(solid?new BoundingBox(x,y,z,x+1,y+1,z+1):new BoundingBox(x,y,z,x,y,z));
            return block;
        });
    }

    @Test void oneBlockDipDescendsAndClimbsOutWithoutGettingStuck() {
        ActiveNpc npc=npc("onedip");oneBlockDipTerrain();straightThreeBlockPath(npc);
        List<Location> visited=walkTo(npc,new Location(world,2.5,64,.5));
        assertTrue(visited.stream().anyMatch(p->p.getY()<63.2),"the NPC must descend into the one-block dip");
        verify(pathfinders.get("onedip"),times(1)).findPath(any(Location.class));
    }

    @Test void stairsUseBothTreadsAndApproachTheRiserBeforeLifting() {
        ActiveNpc npc=npc("stairs");steppedTerrain(true,false);staircasePath(npc,false);
        List<Location> visited=walkTo(npc,new Location(world,3.5,66,.5));
        assertTrue(visited.getFirst().getX()>.5,"do not lift a full stair at the center of the previous block");
        assertTrue(visited.stream().anyMatch(p->p.getY()>64.4 && p.getY()<64.7),"must follow the lower tread");
        verify(pathfinders.get("stairs"),times(1)).findPath(any(Location.class));
    }
    @Test void stairsDescendOnlyAfterTheBodyClearsEachUpperTread() {
        ActiveNpc npc=npc("down");steppedTerrain(true,false);staircasePath(npc,true);
        positions.put("down",new Location(world,3.5,66,.5));
        List<Location> visited=walkTo(npc,new Location(world,.5,64,.5));
        assertTrue(visited.stream().anyMatch(p->p.getY()>65.4 && p.getY()<65.7));
        verify(pathfinders.get("down"),times(1)).findPath(any(Location.class));
    }
    @Test void ordinaryWalkingCanClimbFullBlocksOutsideDance() {
        ActiveNpc npc=npc("blocks");steppedTerrain(false,false);staircasePath(npc,false);
        walkTo(npc,new Location(world,3.5,66,.5));
    }
    @Test void aLowCeilingStillPreventsClimbingThroughSolidBlocks() {
        ActiveNpc npc=npc("ceiling");steppedTerrain(true,true);staircasePath(npc,false);
        Location before=npc.entity().getLocation();
        assertEquals(RoutineNavigator.Result.WAITING,navigator.move(npc,new Location(world,3.5,66,.5),2.4,0,2));
        assertEquals(before,npc.entity().getLocation());
    }
}
