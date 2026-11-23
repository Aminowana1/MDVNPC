package com.mdvcraft.mdvnpc.routine;

import com.destroystokyo.paper.entity.Pathfinder;
import com.mdvcraft.mdvnpc.config.NpcParser;
import com.mdvcraft.mdvnpc.runtime.ActiveNpc;
import com.mdvcraft.mdvnpc.runtime.NpcSpacing;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Villager;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.VoxelShape;

import java.util.*;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Flat stone ground with optional two-block walls, many NPCs and the shared spacing. Paper's
 * search is a grid breadth-first walk round the walls (it knows nothing of other NPCs).
 */
final class CrowdWorld {
    record Column(int x, int z) {}
    final World world = mock(World.class, withSettings().stubOnly());
    final RoutineNavigator navigator;
    final NpcSpacing spacing;
    long tick;
    final Set<Column> walls = new HashSet<>();
    final Map<String, Location> feet = new LinkedHashMap<>();
    final Map<String, ActiveNpc> npcs = new LinkedHashMap<>();
    final Map<String, Location> goals = new LinkedHashMap<>();
    final Set<String> arrived = new HashSet<>(), idle = new HashSet<>();
    int searches;
    /** The closest two NPCs ever came, horizontally, while on the same floor. */
    double closest = Double.MAX_VALUE;
    String closestPair = "";

    CrowdWorld(double minDistance, double passThroughSeconds) {
        spacing = new NpcSpacing(() -> tick);
        spacing.configure(true, minDistance, true, passThroughSeconds);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        when(world.getMinHeight()).thenReturn(0);
        when(world.getMaxHeight()).thenReturn(128);
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        Block air = block(Material.AIR, null), stone = block(Material.STONE, new BoundingBox(0, 0, 0, 1, 1, 1));
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(call -> {
            int x = call.getArgument(0), y = call.getArgument(1), z = call.getArgument(2);
            if (y <= 63) return stone;
            return y <= 65 && walls.contains(new Column(x, z)) ? stone : air;
        });
        DoorController doors = mock(DoorController.class, withSettings().stubOnly());
        when(doors.openNear(any(), any(), anyLong())).thenReturn(true);
        navigator = new RoutineNavigator(doors, (npc, to) -> {
            Location before = feet.get(npc.definition().id());
            feet.put(npc.definition().id(), to.clone());
            check(npc.definition().id(), before);
            spacing.moved(npc);
            return true;
        });
        navigator.configureNodes(false, 12, 16, 24, 256, 8192, 256);
        navigator.spacing(spacing);
    }

    Location at(double x, double z) { return new Location(world, x + .5, 64, z + .5); }
    void wall(int x, int z) { walls.add(new Column(x, z)); }

    /** An NPC walking to {@code goal}; a null goal stands still (pushable when {@code idle}). */
    ActiveNpc npc(String id, Location where, Location goal, boolean idle) {
        feet.put(id, where.clone());
        Villager villager = mock(Villager.class, withSettings().stubOnly());
        UUID uuid = UUID.randomUUID();
        when(villager.getUniqueId()).thenReturn(uuid);
        when(villager.isValid()).thenReturn(true);
        when(villager.isOnGround()).thenReturn(true);
        when(villager.getWorld()).thenReturn(world);
        when(villager.getLocation()).thenAnswer(call -> feet.get(id).clone());
        when(villager.getEyeLocation()).thenAnswer(call -> feet.get(id).clone().add(0, 1.6, 0));
        Pathfinder finder = mock(Pathfinder.class, withSettings().stubOnly());
        when(finder.findPath(any(Location.class))).thenAnswer(call -> { searches++; return path(feet.get(id), call.getArgument(0)); });
        when(villager.getPathfinder()).thenReturn(finder);
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("npcs." + id + ".location.world", "world");
        yaml.set("npcs." + id + ".location.y", 64);
        ActiveNpc npc = new ActiveNpc(NpcParser.parse(yaml).get(id), where.clone(), villager, null);
        npcs.put(id, npc);
        if (goal != null) goals.put(id, goal.clone());
        if (idle) this.idle.add(id);
        spacing.track(npc);
        return npc;
    }

    private Pathfinder.PathResult path(Location from, Location to) {
        Column start = new Column(from.getBlockX(), from.getBlockZ()), goal = new Column(to.getBlockX(), to.getBlockZ());
        Map<Column, Column> parent = new HashMap<>();
        ArrayDeque<Column> open = new ArrayDeque<>();
        parent.put(start, start); open.add(start);
        while (!open.isEmpty() && !parent.containsKey(goal)) {
            Column c = open.poll();
            for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                Column n = new Column(c.x() + d[0], c.z() + d[1]);
                if (parent.containsKey(n) || walls.contains(n) || Math.abs(n.x()) > 40 || Math.abs(n.z()) > 40) continue;
                parent.put(n, c); open.add(n);
            }
        }
        List<Location> points = new LinkedList<>();
        boolean whole = parent.containsKey(goal);
        if (whole) for (Column c = goal; ; c = parent.get(c)) {
            points.addFirst(at(c.x(), c.z()));
            if (c.equals(start)) break;
        }
        else points.add(at(start.x(), start.z()));
        Pathfinder.PathResult result = mock(Pathfinder.PathResult.class, withSettings().stubOnly());
        doReturn(new ArrayList<>(points)).when(result).getPoints();
        doReturn(whole).when(result).canReachFinalPoint();
        return result;
    }

    /** Every NPC with a goal walks; true once all have arrived. */
    boolean step(int cadence) {
        tick += cadence;
        navigator.beginTick(tick, 8, Long.MAX_VALUE);
        spacing.refresh(npcs.values());
        if (tick % 10 < cadence) spacing.separate(w -> new RoutineTerrain(w, mock(DoorController.class, withSettings().stubOnly())));
        for (var e : goals.entrySet()) {
            String id = e.getKey();
            if (arrived.contains(id)) continue;
            if (navigator.move(npcs.get(id), e.getValue(), 2.5, tick, cadence) == RoutineNavigator.Result.ARRIVED) {
                arrived.add(id); navigator.cancel(id);
            }
        }
        BiPredicateMover mover = new BiPredicateMover();
        for (var e : npcs.entrySet()) {
            String id = e.getKey();
            boolean standing = !goals.containsKey(id) || arrived.contains(id);
            spacing.idle(e.getValue(), standing && idle.contains(id), standing, mover);
        }
        measure();
        return arrived.size() == goals.size();
    }
    private final class BiPredicateMover implements java.util.function.BiPredicate<ActiveNpc, Location> {
        @Override public boolean test(ActiveNpc npc, Location to) {
            Location before = feet.get(npc.definition().id());
            feet.put(npc.definition().id(), to.clone()); check(npc.definition().id(), before); spacing.moved(npc); return true;
        }
    }
    /** The first move that ended inside someone, with where it came from. */
    String firstViolation;
    private void check(String id, Location before) {
        if (firstViolation != null) return;
        Location a = feet.get(id);
        for (var e : feet.entrySet()) {
            if (e.getKey().equals(id)) continue;
            Location b = e.getValue();
            if (Math.abs(a.getY() - b.getY()) >= NpcSpacing.SAME_LEVEL) continue;
            double d = Math.hypot(a.getX() - b.getX(), a.getZ() - b.getZ());
            double was = Math.hypot(before.getX() - b.getX(), before.getZ() - b.getZ());
            if (d < spacing.minDistance() - .03 && d < was - 1e-6) {
                java.io.StringWriter out = new java.io.StringWriter();
                new Throwable().printStackTrace(new java.io.PrintWriter(out));
                firstViolation = "tick " + tick + ": " + id + " moved from " + before + " to " + a + ", " + d + " from " + e.getKey()
                        + " at " + b + System.lineSeparator()
                        + out.toString().lines().limit(12).reduce("", (x, y) -> x + System.lineSeparator() + y);
                return;
            }
        }
    }
    private void measure() {
        List<String> ids = new ArrayList<>(feet.keySet());
        for (int i = 0; i < ids.size(); i++) for (int j = i + 1; j < ids.size(); j++) {
            Location a = feet.get(ids.get(i)), b = feet.get(ids.get(j));
            if (Math.abs(a.getY() - b.getY()) >= NpcSpacing.SAME_LEVEL) continue;
            double d = Math.hypot(a.getX() - b.getX(), a.getZ() - b.getZ());
            if (d < closest) { closest = d; closestPair = ids.get(i) + "/" + ids.get(j) + " at tick " + tick; }
        }
    }
    long run(int cadence, long limit) {
        while (tick < limit) if (step(cadence)) return tick;
        return -1;
    }

    private static Block block(Material material, BoundingBox box) {
        Block block = mock(Block.class, withSettings().stubOnly());
        when(block.getType()).thenReturn(material);
        when(block.isPassable()).thenReturn(box == null);
        VoxelShape shape = mock(VoxelShape.class, withSettings().stubOnly());
        when(shape.getBoundingBoxes()).thenReturn(box == null ? List.of() : List.of(box));
        when(block.getCollisionShape()).thenReturn(shape);
        return block;
    }
}
