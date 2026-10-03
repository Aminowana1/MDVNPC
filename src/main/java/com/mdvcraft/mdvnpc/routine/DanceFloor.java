package com.mdvcraft.mdvnpc.routine;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Door;
import org.bukkit.block.data.type.Stairs;

import java.util.HashMap;
import java.util.Map;

/** Small, level dance-floor cache; fresh body collision checks precede every step. */
final class DanceFloor {
    private static final double RADIUS_SQUARED = 9, INNER_RADIUS_SQUARED = 1;
    private static final double[] FOOT = {-.28, .28}, BODY = {-.30, .30};
    final Location center;
    final double y;
    private final RoutineTerrain terrain;
    private final Map<Long, Boolean> support = new HashMap<>();
    private final Map<Long, Boolean> walkable = new HashMap<>();
    private long refreshAt;

    DanceFloor(Location center, double y, DoorController doors, long tick) {
        this.center = center.clone(); this.y = y;
        terrain = new RoutineTerrain(center.getWorld(), doors); refreshAt = tick + 40;
    }
    void refresh(long tick) { if (tick >= refreshAt) { support.clear(); walkable.clear(); refreshAt = tick + 40; } }
    void centerOn(Location source) { center.setX(source.getX()); center.setY(source.getY()); center.setZ(source.getZ()); }

    boolean inside(Location point) {
        if (point.getWorld() != center.getWorld() || Math.abs(point.getY() - y) > .06) return false;
        double dx = point.getX() - center.getX(), dz = point.getZ() - center.getZ();
        double distance = dx * dx + dz * dz;
        double dy = y - center.getY();
        return distance >= INNER_RADIUS_SQUARED && distance + dy * dy <= RADIUS_SQUARED;
    }
    boolean stand(Location point) {
        return inside(point) && physicalStand(point);
    }
    boolean physicalStand(Location point) {
        if (point.getWorld() != center.getWorld() || Math.abs(point.getY() - y) > .06) return false;
        // Full foot-width must have support at this exact height: never near()'s +/-1 adjustment.
        for (double dx : FOOT) for (double dz : FOOT)
            if (!floorSupport((int)Math.floor(point.getX() + dx), (int)Math.floor(point.getZ() + dz))) return false;
        return terrain.fits(point.getX(), y, point.getZ(), false);
    }
    private boolean supported(int x, int z) {
        if (!terrain.loaded(x, z)) return false;
        long key = ((long)x << 32) ^ (z & 0xffffffffL);
        return support.computeIfAbsent(key, ignored -> floorSupport(x, z));
    }
    private boolean floorSupport(int x, int z) {
        Block floor = terrain.block(x, (int)Math.ceil(y - .02) - 1, z);
        return floor != null && floor.getType().isSolid() && !RoutineTerrain.hazard(floor.getType())
                && !(floor.getBlockData() instanceof Door) && !(floor.getBlockData() instanceof Stairs)
                && Math.abs(floor.getBoundingBox().getMaxY() - y) <= .02;
    }
    boolean plannedStand(Location point) {
        if (!inside(point)) return false;
        for (double dx : BODY) for (double dz : BODY) {
            int x = (int)Math.floor(point.getX() + dx), z = (int)Math.floor(point.getZ() + dz);
            if (!terrain.loaded(x, z)) return false;
            long key = ((long)x << 32) ^ (z & 0xffffffffL);
            if (!walkable.computeIfAbsent(key, ignored -> supported(x, z) && terrain.fits(x + .5, y, z + .5, false))) return false;
        }
        return true;
    }
    boolean segment(Location from, Location to) {
        return segment(from, to, true);
    }
    boolean plannedSegment(Location from, Location to) { return segment(from, to, false); }
    private boolean segment(Location from, Location to, boolean fresh) {
        if (!inside(from) || !inside(to)) return false;
        int samples = Math.max(1, (int)Math.ceil(Math.sqrt(from.distanceSquared(to)) / .3));
        for (int i = 1; i <= samples; i++) {
            double ratio = i / (double)samples;
            Location point = from.clone().add((to.getX() - from.getX()) * ratio, 0, (to.getZ() - from.getZ()) * ratio);
            if (!(fresh ? stand(point) : plannedStand(point))) return false;
        }
        return true;
    }
    boolean jumpClear(Location point, double height) {
        return stand(point) && terrain.fits(point.getX(), y + height / 2, point.getZ(), false)
                && terrain.fits(point.getX(), y + height, point.getZ(), false);
    }
    boolean airborneClear(Location point) {
        return point.getWorld() == center.getWorld() && point.getY() >= y && point.getY() <= y + .71
                && terrain.fits(point.getX(), point.getY(), point.getZ(), false);
    }
    World world() { return center.getWorld(); }
}
