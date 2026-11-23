package com.mdvcraft.mdvnpc.routine;

import com.destroystokyo.paper.entity.Pathfinder;
import com.mdvcraft.mdvnpc.config.NpcParser;
import com.mdvcraft.mdvnpc.runtime.ActiveNpc;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Villager;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.VoxelShape;

import java.util.*;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Endless flat ground with buried markers. Paper's search is replaced by a straight grid walk. */
public final class FlatTripWorld {
    private record Column(int x, int z) {}
    public final World world = mock(World.class, withSettings().stubOnly());
    public final RoutineNavigator navigator;
    public final ActiveNpc npc;
    public Location position;
    public int nativeSearches;
    public double walked, minX, firstNativeSearchX;
    public boolean loaded = true, rejectOnce;
    /** When positive, Paper returns segments of at most this many blocks, as a long trip does. */
    public int segment;
    private final Set<Column> markers = new HashSet<>(), panels = new HashSet<>();
    /** Further NPCs that do move, each with its own feet position. */
    private final Map<String, Location[]> walkers = new HashMap<>();
    /** Paper searches requested by {@link #walker} NPCs. */
    public int walkerSearches;

    public FlatTripWorld(int blocksPerUpdate) {
        when(world.getUID()).thenReturn(UUID.randomUUID());
        when(world.getMinHeight()).thenReturn(0);
        when(world.getMaxHeight()).thenReturn(128);
        when(world.isChunkLoaded(anyInt(), anyInt())).thenAnswer(call -> loaded);
        Block air = block(Material.AIR, null);
        Block stone = block(Material.STONE, new BoundingBox(0, 0, 0, 1, 1, 1));
        Block marker = block(Material.NETHERITE_BLOCK, new BoundingBox(0, 0, 0, 1, 1, 1));
        // The shape of an open trapdoor standing on the border between two cells.
        Block panel = block(Material.IRON_BARS, new BoundingBox(.8125, 0, 0, 1, 1, 1));
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(call -> {
            Column at = new Column(call.getArgument(0), call.getArgument(2));
            int y = call.getArgument(1);
            if (y == 62 && markers.contains(at)) return marker;
            return y <= 63 ? stone : y <= 65 && panels.contains(at) ? panel : air;
        });
        Map<Column, Chunk> chunks = new HashMap<>();
        when(world.getChunkAt(anyInt(), anyInt())).thenAnswer(call -> {
            Column key = new Column(call.getArgument(0), call.getArgument(1));
            Chunk known = chunks.get(key);
            if (known != null) return known;
            ChunkSnapshot snapshot = mock(ChunkSnapshot.class, withSettings().stubOnly());
            doAnswer(q -> markers.stream().anyMatch(m -> m.x() >> 4 == key.x() && m.z() >> 4 == key.z()))
                    .when(snapshot).contains(any(BlockData.class));
            doReturn(63).when(snapshot).getHighestBlockYAt(anyInt(), anyInt());
            doAnswer(q -> {
                int x = q.getArgument(0), y = q.getArgument(1), z = q.getArgument(2);
                return y == 62 && markers.contains(new Column((key.x() << 4) + x, (key.z() << 4) + z))
                        ? Material.NETHERITE_BLOCK : y <= 63 ? Material.STONE : Material.AIR;
            }).when(snapshot).getBlockType(anyInt(), anyInt(), anyInt());
            Chunk chunk = mock(Chunk.class, withSettings().stubOnly());
            doReturn(snapshot).when(chunk).getChunkSnapshot(anyBoolean(), anyBoolean(), anyBoolean());
            chunks.put(key, chunk);
            return chunk;
        });
        DoorController doors = mock(DoorController.class, withSettings().stubOnly());
        when(doors.openNear(any(), any(), anyLong())).thenReturn(true);
        navigator = new RoutineNavigator(doors, (active, to) -> {
            Location[] feet = walkers.get(active.definition().id());
            if (feet != null) {
                feet[0] = to.clone();
                return true;
            }
            if (!active.definition().id().equals("trip")) return false; // bystanders never move
            if (rejectOnce && to.getX() > 3) {
                rejectOnce = false;
                return false;
            }
            moveTo(to);
            return true;
        });
        navigator.configureNodes(true, 12, 16, 24, 256, blocksPerUpdate, 256);
        position = at(0, 0);
        minX = position.getX();
        Villager villager = mock(Villager.class, withSettings().stubOnly());
        when(villager.isValid()).thenReturn(true);
        when(villager.isOnGround()).thenReturn(true);
        when(villager.getWorld()).thenReturn(world);
        when(villager.getLocation()).thenAnswer(call -> position.clone());
        when(villager.getEyeLocation()).thenAnswer(call -> position.clone().add(0, 1.6, 0));
        Pathfinder finder = mock(Pathfinder.class, withSettings().stubOnly());
        when(finder.findPath(any(Location.class))).thenAnswer(call -> {
            if (nativeSearches++ == 0) firstNativeSearchX = position.getX();
            return straightPath(position, call.getArgument(0));
        });
        when(villager.getPathfinder()).thenReturn(finder);
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("npcs.trip.location.world", "world");
        yaml.set("npcs.trip.location.y", 64);
        npc = new ActiveNpc(NpcParser.parse(yaml).get("trip"), position.clone(), villager, null);
    }

    private Pathfinder.PathResult straightPath(Location from, Location to) {
        List<Location> points = new ArrayList<>();
        int x = from.getBlockX(), z = from.getBlockZ();
        points.add(at(x, z));
        while ((x != to.getBlockX() || z != to.getBlockZ()) && (segment <= 0 || points.size() <= segment)) {
            if (x != to.getBlockX()) x += Integer.signum(to.getBlockX() - x);
            else z += Integer.signum(to.getBlockZ() - z);
            points.add(at(x, z));
        }
        boolean whole = x == to.getBlockX() && z == to.getBlockZ();
        Pathfinder.PathResult result = mock(Pathfinder.PathResult.class, withSettings().stubOnly());
        doReturn(points).when(result).getPoints();
        doReturn(whole).when(result).canReachFinalPoint();
        return result;
    }

    /** Another NPC that this navigator can move, with a Paper search of its own. */
    public ActiveNpc walker(String id, Location where) {
        Location[] feet = { where.clone() };
        walkers.put(id, feet);
        Villager villager = mock(Villager.class, withSettings().stubOnly());
        when(villager.isValid()).thenReturn(true);
        when(villager.isOnGround()).thenReturn(true);
        when(villager.getWorld()).thenReturn(world);
        when(villager.getLocation()).thenAnswer(call -> feet[0].clone());
        when(villager.getEyeLocation()).thenAnswer(call -> feet[0].clone().add(0, 1.6, 0));
        Pathfinder finder = mock(Pathfinder.class, withSettings().stubOnly());
        when(finder.findPath(any(Location.class))).thenAnswer(call -> {
            walkerSearches++;
            return straightPath(feet[0], call.getArgument(0));
        });
        when(villager.getPathfinder()).thenReturn(finder);
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("npcs." + id + ".location.world", "world");
        yaml.set("npcs." + id + ".location.y", 64);
        return new ActiveNpc(NpcParser.parse(yaml).get(id), where.clone(), villager, null);
    }
    /** Moves a {@link #walker} as an external teleport would. */
    public void place(ActiveNpc walker, Location to) { walkers.get(walker.definition().id())[0] = to.clone(); }

    private static Block block(Material material, BoundingBox box) {
        Block block = mock(Block.class, withSettings().stubOnly());
        when(block.getType()).thenReturn(material);
        when(block.isPassable()).thenReturn(box == null);
        VoxelShape shape = mock(VoxelShape.class, withSettings().stubOnly());
        when(shape.getBoundingBoxes()).thenReturn(box == null ? List.of() : List.of(box));
        when(block.getCollisionShape()).thenReturn(shape);
        return block;
    }

    public Location at(int x, int z) { return new Location(world, x + .5, 64, z + .5); }
    /** Another NPC in the same world and navigator, which stays where it is. */
    public ActiveNpc bystander(String id, Location where) {
        Villager villager = mock(Villager.class, withSettings().stubOnly());
        when(villager.isValid()).thenReturn(true);
        when(villager.isOnGround()).thenReturn(true);
        when(villager.getWorld()).thenReturn(world);
        when(villager.getLocation()).thenAnswer(call -> where.clone());
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("npcs." + id + ".location.world", "world");
        yaml.set("npcs." + id + ".location.y", 64);
        return new ActiveNpc(NpcParser.parse(yaml).get(id), where.clone(), villager, null);
    }
    public void marker(int x, int z) { markers.add(new Column(x, z)); }
    public void panel(int x, int z) { panels.add(new Column(x, z)); }
    public void moveTo(Location to) {
        walked += position.distance(to);
        minX = Math.min(minX, to.getX());
        position = to.clone();
    }

    /** @return the tick of arrival, or -1 */
    public long walk(Location goal, double speed, int cadence, long limit) {
        for (long tick = 0; tick < limit; tick += cadence) {
            navigator.beginTick(tick, 2, Long.MAX_VALUE);
            if (navigator.move(npc, goal, speed, tick, cadence) == RoutineNavigator.Result.ARRIVED) return tick;
        }
        return -1;
    }
}
