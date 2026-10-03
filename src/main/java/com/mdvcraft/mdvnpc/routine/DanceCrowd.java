package com.mdvcraft.mdvnpc.routine;

import org.bukkit.Location;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Active dancers only. Each spacing query visits nine local buckets. */
final class DanceCrowd {
    private record Cell(UUID world, int x, int z) {}
    private record Entry(Cell cell) {}
    private final Map<Cell, Map<String, Location>> positions = new HashMap<>(), reservations = new HashMap<>();
    private final Map<String, Entry> located = new HashMap<>(), reserved = new HashMap<>();
    private static Cell cell(Location point) {
        return new Cell(point.getWorld().getUID(), (int)Math.floor(point.getX() / 2), (int)Math.floor(point.getZ() / 2));
    }
    void position(String id, Location point) { put(id, point, positions, located); }
    void reserve(String id, Location point) { put(id, point, reservations, reserved); }
    void release(String id) { remove(id, reservations, reserved); }
    void remove(String id) { remove(id, positions, located); release(id); }
    private static void put(String id, Location point, Map<Cell, Map<String, Location>> buckets, Map<String, Entry> index) {
        Cell cell = cell(point); Entry previous = index.get(id);
        if (previous != null && previous.cell().equals(cell)) { buckets.get(cell).put(id, point.clone()); return; }
        remove(id, buckets, index);
        buckets.computeIfAbsent(cell, ignored -> new HashMap<>()).put(id, point.clone()); index.put(id, new Entry(cell));
    }
    private static void remove(String id, Map<Cell, Map<String, Location>> buckets, Map<String, Entry> index) {
        Entry previous = index.remove(id); if (previous == null) return;
        var bucket = buckets.get(previous.cell()); bucket.remove(id); if (bucket.isEmpty()) buckets.remove(previous.cell());
    }
    boolean free(String id, Location point, double distance) {
        return free(id, point, distance, positions) && free(id, point, distance, reservations);
    }
    private static boolean free(String id, Location point, double distance, Map<Cell, Map<String, Location>> buckets) {
        Cell center = cell(point);
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            var bucket = buckets.get(new Cell(center.world(), center.x() + dx, center.z() + dz));
            if (bucket == null) continue;
            for (var other : bucket.entrySet()) {
                Location p = other.getValue();
                if (other.getKey().equals(id) || Math.abs(p.getY() - point.getY()) > 1) continue;
                double x = p.getX() - point.getX(), z = p.getZ() - point.getZ();
                if (x * x + z * z < distance * distance) return false;
            }
        }
        return true;
    }
}
