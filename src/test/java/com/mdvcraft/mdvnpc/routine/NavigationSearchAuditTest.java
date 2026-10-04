package com.mdvcraft.mdvnpc.routine;

import com.destroystokyo.paper.entity.Pathfinder;
import com.mdvcraft.mdvnpc.config.NpcParser;
import com.mdvcraft.mdvnpc.runtime.ActiveNpc;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Villager;
import org.bukkit.util.BoundingBox;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.util.*;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Audit of the plugin's treatment of legal Paper replies, not an integration test of Paper.
 * A separate graph oracle finds shortest routes and returns the nearest reachable partial
 * endpoint inside the requested travel range. Paper's real evaluator includes diagonals,
 * penalties and a node budget; these controlled replies deliberately do not simulate those.
 */
class NavigationSearchAuditTest {
    private record Tile(int x, int y, int z) {
        Location location(World world) { return new Location(world, x + .5, y, z + .5); }
        double distanceSquared(Tile other) {
            double dx = x - other.x, dy = y - other.y, dz = z - other.z;
            return dx * dx + dy * dy + dz * dz;
        }
    }
    private record Reply(List<Tile> tiles, boolean reached) {}
    private World world;
    private RoutineNavigator navigator;
    private ActiveNpc npc;
    private Pathfinder finder;
    private Location position;
    private double requestedRange;
    private final List<Integer> queries = new ArrayList<>();
    private final Map<Tile, Block> blockCache = new HashMap<>();
    private Predicate<Tile> solid = tile -> tile.y == 63;

    @BeforeEach void setup() {
        MockBukkit.mock();
        world = mock(World.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        when(world.getMinHeight()).thenReturn(-64);
        when(world.getMaxHeight()).thenReturn(320);
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(call -> {
            Tile tile = new Tile(call.getArgument(0), call.getArgument(1), call.getArgument(2));
            return blockCache.computeIfAbsent(tile, key -> {
                boolean filled = solid.test(key);
                Block block = mock(Block.class);
                when(block.getType()).thenReturn(filled ? Material.STONE : Material.AIR);
                when(block.isPassable()).thenReturn(!filled);
                when(block.getBoundingBox()).thenReturn(filled
                        ? new BoundingBox(key.x, key.y, key.z, key.x + 1, key.y + 1, key.z + 1)
                        : new BoundingBox(key.x, key.y, key.z, key.x, key.y, key.z));
                return block;
            });
        });
        DoorController doors = mock(DoorController.class);
        when(doors.openNear(any(), any(), anyLong())).thenReturn(true);
        navigator = new RoutineNavigator(doors, (active, destination) -> {
            position = destination.clone();
            return true;
        });
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("npcs.audit.location.world", "world");
        yaml.set("npcs.audit.location.y", 64);
        Villager entity = mock(Villager.class);
        finder = mock(Pathfinder.class);
        when(entity.getWorld()).thenReturn(world);
        when(entity.isValid()).thenReturn(true);
        when(entity.isOnGround()).thenReturn(true);
        when(entity.getPathfinder()).thenReturn(finder);
        when(entity.getLocation()).thenAnswer(call -> position.clone());
        AttributeInstance range = mock(AttributeInstance.class);
        when(entity.getAttribute(Attribute.FOLLOW_RANGE)).thenReturn(range);
        when(range.getBaseValue()).thenReturn(48.0);
        doAnswer(call -> { requestedRange = call.getArgument(0); return null; })
                .when(range).setBaseValue(anyDouble());
        position = new Location(world, .5, 64, .5);
        npc = new ActiveNpc(NpcParser.parse(yaml).get("audit"), position.clone(), entity, null);
    }

    @AfterEach void cleanup() { navigator.clear(); MockBukkit.unmock(); }

    private Tile currentTile() {
        return new Tile(position.getBlockX(), (int) Math.round(position.getY()), position.getBlockZ());
    }
    private Pathfinder.PathResult paperReply(Reply reply) {
        Pathfinder.PathResult result = mock(Pathfinder.PathResult.class);
        when(result.getPoints()).thenReturn(reply.tiles.stream().map(tile -> tile.location(world)).toList());
        when(result.canReachFinalPoint()).thenReturn(reply.reached);
        return result;
    }
    private void useGraph(Map<Tile, List<Tile>> graph, Tile goal) {
        when(finder.findPath(any(Location.class))).thenAnswer(call -> {
            queries.add((int) requestedRange);
            return paperReply(search(graph, currentTile(), goal, requestedRange));
        });
    }

    /** Dijkstra over a fixture graph, choosing the nearest explored endpoint if incomplete. */
    private Reply search(Map<Tile, List<Tile>> graph, Tile from, Tile goal, double range) {
        Map<Tile, Double> costs = new HashMap<>();
        Map<Tile, Tile> parents = new HashMap<>();
        PriorityQueue<Tile> open = new PriorityQueue<>(Comparator.comparingDouble(costs::get));
        costs.put(from, 0.0); open.add(from);
        Tile nearest = from;
        boolean reached = false;
        while (!open.isEmpty()) {
            Tile at = open.remove();
            if (at.distanceSquared(goal) < nearest.distanceSquared(goal)) nearest = at;
            if (at.equals(goal)) { nearest = at; reached = true; break; }
            for (Tile next : graph.getOrDefault(at, List.of())) {
                double cost = costs.get(at) + Math.sqrt(at.distanceSquared(next));
                if (cost >= range || cost >= costs.getOrDefault(next, Double.POSITIVE_INFINITY)) continue;
                if (open.contains(next)) open.remove(next);
                costs.put(next, cost); parents.put(next, at); open.add(next);
            }
        }
        LinkedList<Tile> path = new LinkedList<>();
        for (Tile at = nearest; at != null; at = parents.get(at)) path.addFirst(at);
        return new Reply(List.copyOf(path), reached);
    }
    private Map<Tile, List<Tile>> plane(int minX, int maxX, int minZ, int maxZ,
                                         Predicate<Tile> allowed) {
        Map<Tile, List<Tile>> graph = new HashMap<>();
        for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) {
            Tile at = new Tile(x, 64, z);
            if (!allowed.test(at)) continue;
            ArrayList<Tile> next = new ArrayList<>();
            for (int[] offset : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                Tile candidate = new Tile(x + offset[0], 64, z + offset[1]);
                if (candidate.x >= minX && candidate.x <= maxX && candidate.z >= minZ
                        && candidate.z <= maxZ && allowed.test(candidate)) next.add(candidate);
            }
            graph.put(at, List.copyOf(next));
        }
        return graph;
    }
    private Map<Tile, List<Tile>> corridor(List<Tile> tiles) {
        Map<Tile, List<Tile>> graph = new HashMap<>();
        for (int index = 0; index < tiles.size(); index++) {
            ArrayList<Tile> adjacent = new ArrayList<>();
            if (index > 0) adjacent.add(tiles.get(index - 1));
            if (index + 1 < tiles.size()) adjacent.add(tiles.get(index + 1));
            graph.put(tiles.get(index), List.copyOf(adjacent));
        }
        return graph;
    }
    private RoutineNavigator.Result walk(Tile goal, int maxTicks) {
        RoutineNavigator.Result result = RoutineNavigator.Result.WAITING;
        for (int tick = 0; tick <= maxTicks; tick += 2) {
            Location previous = position.clone();
            navigator.beginTick(tick, 2, Long.MAX_VALUE);
            result = navigator.move(npc, goal.location(world), 2.4, tick, 2);
            assertTrue(position.distance(previous) <= .240001, "every update respects walking speed");
            if (result == RoutineNavigator.Result.ARRIVED) break;
        }
        return result;
    }

    @Test void openTerrainCanCompleteA120BlockTripThroughSuccessivePartialRoutes() {
        Tile goal = new Tile(120, 64, 0);
        useGraph(plane(0, 121, 0, 0, tile -> true), goal);
        assertEquals(RoutineNavigator.Result.ARRIVED, walk(goal, 1400));
        assertTrue(queries.size() >= 8, "the trip needs several fresh native segments");
        assertEquals(Set.of(16), new HashSet<>(queries));
    }

    @Test void reachableShortWallDetourIsWidenedAndWalkedAround() {
        Tile goal = new Tile(4, 64, 0);
        Predicate<Tile> wall = tile -> tile.x == 2 && Math.abs(tile.z) <= 8;
        solid = tile -> tile.y == 63 || (tile.y == 64 || tile.y == 65) && wall.test(tile);
        useGraph(plane(-1, 5, -10, 10, tile -> !wall.test(tile)), goal);
        assertEquals(RoutineNavigator.Result.ARRIVED, walk(goal, 500));
        assertTrue(queries.contains(24), "the first small query cannot discover the complete detour");
        assertFalse(navigator.exhausted("audit"));
    }

    @Test void sameFloorEnclosureWithTheOnlyOppositeDoorWidensAndArrives() {
        Tile start = new Tile(10, 64, -1), goal = new Tile(10, 64, 2);
        position = start.location(world);
        Predicate<Tile> wall = tile -> tile.x >= 0 && tile.x <= 20 && tile.z >= 0 && tile.z <= 20
                && (tile.x == 0 || tile.x == 20 || tile.z == 0 || tile.z == 20)
                && !(tile.x == 10 && tile.z == 20);
        solid = tile -> tile.y == 63 || (tile.y == 64 || tile.y == 65) && wall.test(tile);
        Map<Tile, List<Tile>> graph = plane(-2, 22, -2, 22, tile -> !wall.test(tile));
        assertTrue(search(graph, start, goal, 64).reached, "the enclosure has a valid opposite doorway");
        assertFalse(search(graph, start, goal, 32).reached);
        useGraph(graph, goal);
        assertEquals(RoutineNavigator.Result.ARRIVED, walk(goal, 1400),"must walk around to the opposite doorway");
        assertTrue(queries.contains(64),"allow the necessary detour rather than exhausting at 32");
        assertFalse(navigator.exhausted("audit"));
    }

    private Map<Tile, List<Tile>> upperFloorCorridor(int goalX) {
        List<Tile> route = new ArrayList<>();
        for (int x = 0; x >= -20; x--) route.add(new Tile(x, 64, 0));
        for (int stair = 1; stair <= 4; stair++) route.add(new Tile(-20, 64 + stair, stair));
        for (int x = -19; x <= goalX; x++) route.add(new Tile(x, 68, 4));
        solid = tile -> tile.y == 63
                || (tile.z == -1 || tile.z == 1 && tile.x != -20)
                    && tile.x >= -21 && tile.x <= 1 && tile.y >= 64 && tile.y <= 67
                || tile.z == 0 && tile.x == -21 && tile.y >= 64 && tile.y <= 67
                || tile.z == 0 && tile.x == 1 && tile.y >= 64 && tile.y <= 66
                || (tile.x == -21 || tile.x == -19) && tile.z >= 1 && tile.z <= 3
                    && tile.y >= 64 && tile.y <= 70
                || tile.x == -20 && tile.z >= 1 && tile.z <= 3
                    && tile.y >= 64 && tile.y < 64 + tile.z
                || tile.z == 4 && tile.x >= -20 && tile.x <= goalX && tile.y == 67;
        Map<Tile, List<Tile>> graph = corridor(route);
        RoutineTerrain terrain = new RoutineTerrain(world, mock(DoorController.class));
        for (Tile tile : graph.keySet()) assertTrue(terrain.stand(
                new BoundedPathfinder.Node(tile.x, tile.y, tile.z)),
                "the route oracle must supply a physically standable tile: " + tile);
        return graph;
    }

    @Test void offsetUpperFloorGoalWidensToItsReachableStairDetourAndArrives() {
        Tile goal = new Tile(9, 68, 4), start = currentTile();
        Map<Tile, List<Tile>> graph = upperFloorCorridor(9);
        assertTrue(search(graph, start, goal, 64).reached);
        assertEquals(List.of(start), search(graph, start, goal, 32).tiles);
        useGraph(graph, goal);
        assertEquals(RoutineNavigator.Result.ARRIVED, walk(goal, 1400));
        assertTrue(queries.stream().anyMatch(range->range>32),"must escape the old cap; a useful cheaper partial segment is sufficient");
        assertFalse(navigator.exhausted("audit"));
    }

    @Test void nearbyUpperFloorGoalIsAllowedToFindAndReplayTheSameLongStairDetour() {
        Tile goal = new Tile(0, 68, 4);
        useGraph(upperFloorCorridor(0), goal);
        RoutineNavigator.Result result = walk(goal, 600);
        assertTrue(queries.contains(48), "a nearby upper floor must permit a wider native query");
        assertEquals(RoutineNavigator.Result.ARRIVED, result,
                "ranges=" + queries + "; final position=" + position);
        assertEquals(List.of(16, 24, 32, 48), queries);
        assertEquals(68, position.getY(), .000001);
    }

    @Test void anAscendingPartialUnderTheGoalIsFollowedWhenItActuallyImprovesHeight() {
        Tile start=currentTile(),endpoint=new Tile(1,68,4),goal=new Tile(1,70,7);
        Map<Tile,List<Tile>> firstGraph=upperFloorCorridor(1);
        List<Tile> first=search(firstGraph,start,endpoint,64).tiles;
        Predicate<Tile> original=solid;
        solid=tile->original.test(tile) || tile.x==1 && (tile.z==5 && tile.y==68
                || (tile.z==6 || tile.z==7) && tile.y==69);
        blockCache.clear();
        List<Tile> continuation=List.of(endpoint,new Tile(1,69,5),new Tile(1,70,6),goal);
        RoutineTerrain terrain=new RoutineTerrain(world,mock(DoorController.class));
        for(Tile tile:continuation)assertTrue(terrain.stand(new BoundedPathfinder.Node(tile.x,tile.y,tile.z)));
        when(finder.findPath(any(Location.class))).thenAnswer(call->{
            queries.add((int)requestedRange);
            if(currentTile().y>=68)return paperReply(new Reply(continuation,true));
            if(requestedRange<64)return paperReply(new Reply(List.of(currentTile()),false));
            return paperReply(new Reply(first,false));
        });
        assertEquals(RoutineNavigator.Result.ARRIVED,walk(goal,1000),
                "a real rise towards the destination must not be discarded as a below-floor dead end");
        assertEquals(70,position.getY(),.000001);
        assertEquals(6,queries.size(),"one completed partial segment, then its continuation");
    }

    @Test void unloadedRouteWaypointIsRetriedWithoutReadingOrLoadingTheMissingChunk() {
        Tile goal = new Tile(40, 64, 0);
        when(world.isChunkLoaded(1, 0)).thenReturn(false);
        when(finder.findPath(any(Location.class))).thenAnswer(call -> {
            queries.add((int) requestedRange);
            return paperReply(new Reply(List.of(new Tile(0, 64, 0), new Tile(16, 64, 0), goal), true));
        });
        assertEquals(RoutineNavigator.Result.WAITING, walk(goal, 18));
        assertEquals(List.of(16), queries);
        navigator.move(npc, goal.location(world), 2.4, 20, 2);
        assertEquals(List.of(16, 16), queries);
        verify(world, never()).getBlockAt(eq(16), anyInt(), anyInt());
        verify(world, never()).getChunkAt(anyInt(), anyInt());
        assertFalse(navigator.exhausted("audit"));
    }

    @Test void movingPartialReplyCycleIsDetectedAndUsesBoundedRecovery() {
        Tile goal = new Tile(20, 64, 0);
        when(finder.findPath(any(Location.class))).thenAnswer(call -> {
            queries.add((int) requestedRange);
            Tile from = currentTile();
            int endX = from.x < 1 ? 2 : 0;
            List<Tile> segment = new ArrayList<>();
            for (int x = from.x; x != endX; x += Integer.compare(endX, from.x)) segment.add(new Tile(x, 64, 0));
            segment.add(new Tile(endX, 64, 0));
            return paperReply(new Reply(List.copyOf(segment), false));
        });
        assertNotEquals(RoutineNavigator.Result.ARRIVED, walk(goal, 1400));
        assertTrue(queries.size() < 25,"partial cycles need a cooldown rather than continual movement/search: "+queries);
        assertTrue(position.getX() >= .4 && position.getX() <= 2.6);
        assertTrue(navigator.exhausted("audit"), "repeated endpoints without new goal progress must be detected");
    }

    @Test void aSuccessfulTeleportThatLeavesTheNpcInPlaceEventuallyTriggersStuckRecovery() {
        DoorController doors = mock(DoorController.class);
        when(doors.openNear(any(), any(), anyLong())).thenReturn(true);
        navigator = new RoutineNavigator(doors, (active, destination) -> true);
        Tile goal = new Tile(2, 64, 0);
        useGraph(plane(0, 2, 0, 0, tile -> true), goal);
        assertEquals(RoutineNavigator.Result.WAITING, walk(goal, 240));
        assertEquals(new Location(world, .5, 64, .5), position);
        assertTrue(queries.contains(64),"recovery may widen, but must stop at its bounded maximum");
        assertTrue(navigator.exhausted("audit"));
    }
}
