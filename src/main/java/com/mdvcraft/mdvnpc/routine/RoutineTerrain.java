package com.mdvcraft.mdvnpc.routine;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Door;
import org.bukkit.util.BoundingBox;
import com.mdvcraft.mdvnpc.routine.BoundedPathfinder.Node;

/** Conservative pedestrian geometry. Never asks Bukkit for an unloaded block. */
public final class RoutineTerrain implements BoundedPathfinder.Grid {
    private final World world;
    private final DoorController doors;
    public RoutineTerrain(World world, DoorController doors) { this.world = world; this.doors = doors; }
    public boolean loaded(int x, int z) { return world.isChunkLoaded(x >> 4, z >> 4); }
    public Block block(int x, int y, int z) { return y >= world.getMinHeight() && y < world.getMaxHeight() && loaded(x,z) ? world.getBlockAt(x,y,z) : null; }
    public static boolean hazard(Material m) { return m == Material.LAVA || m == Material.WATER || m == Material.FIRE || m == Material.SOUL_FIRE || m == Material.CACTUS || m == Material.MAGMA_BLOCK || m == Material.CAMPFIRE || m == Material.SOUL_CAMPFIRE || m == Material.SWEET_BERRY_BUSH || m == Material.POWDER_SNOW || m == Material.POINTED_DRIPSTONE || m == Material.NETHER_PORTAL || m == Material.END_PORTAL; }
    public double height(Node n) {
        Block floor = block(n.x(), n.y()-1, n.z());
        return floor == null ? n.y() : floor.getBoundingBox().getMaxY();
    }
    public Location location(Node n) { return new Location(world, n.x()+.5, height(n), n.z()+.5); }
    @Override public boolean stand(Node n) {
        Block floor = block(n.x(), n.y()-1, n.z());
        if (floor == null || !floor.getType().isSolid() || hazard(floor.getType()) || floor.getBlockData() instanceof Door) return false;
        double y = height(n);
        return y > n.y()-1 && y <= n.y() + .01 && fits(n.x()+.5,y,n.z()+.5, true);
    }
    @Override public boolean edge(Node from, Node to) {
        double a = height(from), b = height(to);
        return Math.abs(a-b) <= 1.01 && fits(from.x()+.5,Math.max(a,b),from.z()+.5,true)
                && fits(to.x()+.5,Math.max(a,b),to.z()+.5,true);
    }
    public boolean fits(double x, double y, double z, boolean planned) {
        BoundingBox body = new BoundingBox(x-.30,y+.015,z-.30,x+.30,y+1.95,z+.30);
        for (int bx=(int)Math.floor(body.getMinX()); bx <= (int)Math.floor(body.getMaxX()); bx++)
            for (int bz=(int)Math.floor(body.getMinZ()); bz <= (int)Math.floor(body.getMaxZ()); bz++)
                for (int by=(int)Math.floor(body.getMinY()); by <= (int)Math.floor(body.getMaxY()); by++) {
                    Block block = block(bx,by,bz);
                    if (block == null || hazard(block.getType())) return false;
                    if (block.getBlockData() instanceof Door door) {
                        if (door.isOpen() || planned && doors.canOpen(block)) continue;
                        return false;
                    }
                    if (block.getBoundingBox().overlaps(body) && !block.isPassable()) return false;
                }
        return true;
    }
    public Node near(Location l) {
        int y = (int)Math.ceil(l.getY()-.02);
        for (int dy : new int[]{0,1,-1}) { Node n = new Node(l.getBlockX(),y+dy,l.getBlockZ()); if (stand(n)) return n; }
        return null;
    }
    public Node approach(RoutineGoal.Point point, boolean furniture) {
        if (!furniture) return near(point.location(world));
        for (int[] d : new int[][]{{0,1},{1,0},{0,-1},{-1,0}}) {
            Node n = near(new Location(world, point.x()+d[0]+.5, point.y(), point.z()+d[1]+.5));
            if (n != null) return n;
        }
        return null;
    }
}
