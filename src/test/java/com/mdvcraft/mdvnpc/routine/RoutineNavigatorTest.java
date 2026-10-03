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
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("npcs." + id + ".location.world", "world"); yaml.set("npcs." + id + ".location.y", 64);
        Villager entity = mock(Villager.class); Pathfinder finder = mock(Pathfinder.class);
        when(entity.getWorld()).thenReturn(world); when(entity.isValid()).thenReturn(true);
        when(entity.isOnGround()).thenReturn(true); when(entity.getPathfinder()).thenReturn(finder);
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

    @Test void failedNativeRouteWaitsFiveSecondsBeforeRetry() {
        ActiveNpc npc = npc("blocked"); Pathfinder finder = pathfinders.get("blocked");
        when(finder.findPath(any(Location.class))).thenReturn(null);
        for (int tick = 0; tick < 100; tick += 2)
            assertEquals(RoutineNavigator.Result.WAITING, navigator.move(npc, destination(), 2.4, tick, 2));
        verify(finder, times(1)).findPath(any(Location.class));
        navigator.move(npc, destination(), 2.4, 100, 2);
        verify(finder, times(2)).findPath(any(Location.class));
    }

    @Test void unloadedDestinationNeverRequestsPaperPathOrReadsItsBlocks() {
        ActiveNpc npc = npc("unloaded"); when(world.isChunkLoaded(2, 0)).thenReturn(false);
        assertEquals(RoutineNavigator.Result.WAITING, navigator.move(npc, new Location(world, 32.5, 64, .5), 2.4, 0, 2));
        verify(pathfinders.get("unloaded"), never()).findPath(any(Location.class));
        verify(world, never()).getBlockAt(anyInt(), anyInt(), anyInt());
        verify(world, never()).getChunkAt(anyInt(), anyInt());
    }

    @Test void unloadedChunkInsideNativeSearchRegionPreventsTheQuery() {
        ActiveNpc npc = npc("boundary"); when(world.isChunkLoaded(-2, -2)).thenReturn(false);
        assertEquals(RoutineNavigator.Result.WAITING, navigator.move(npc, destination(), 2.4, 0, 2));
        verify(pathfinders.get("boundary"), never()).findPath(any(Location.class));
        verify(world, never()).getChunkAt(anyInt(), anyInt());
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

    @Test void groundingAndCancellationRestoreGravityWithoutStartingAi() {
        ActiveNpc npc = npc("grounding"); when(npc.entity().isOnGround()).thenReturn(false);
        assertEquals(RoutineNavigator.Result.WAITING, navigator.move(npc, destination(), 2.4, 0, 2));
        verify(npc.entity()).setGravity(true); verify(npc.entity()).setVelocity(new Vector(0, -.08, 0));
        verify(pathfinders.get("grounding"), never()).findPath(any(Location.class));
        navigator.cancel("grounding");
        verify(npc.entity()).setGravity(false); verify(npc.entity()).setVelocity(new Vector());
        verify(npc.entity(), never()).setAI(anyBoolean()); verify(npc.entity(), never()).setAware(anyBoolean());
        assertEquals(0, navigator.searches());
    }

    @Test void groundingAllowsNativeSearchOnlyAfterPhysicsConfirmsTheFloor() {
        ActiveNpc npc = npc("settle"); when(npc.entity().isOnGround()).thenReturn(false);
        navigator.move(npc, destination(), 2.4, 0, 2);
        when(npc.entity().isOnGround()).thenReturn(true);
        assertEquals(RoutineNavigator.Result.MOVING, navigator.move(npc, destination(), 2.4, 2, 2));
        verify(pathfinders.get("settle")).findPath(any(Location.class)); verify(npc.entity()).setGravity(false);
    }
}
