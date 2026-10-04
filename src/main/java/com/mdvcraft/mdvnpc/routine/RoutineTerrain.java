package com.mdvcraft.mdvnpc.routine;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Door;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.VoxelShape;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import com.mdvcraft.mdvnpc.routine.BoundedPathfinder.Node;

/** Conservative pedestrian geometry. Never asks Bukkit for an unloaded block. */
public final class RoutineTerrain implements BoundedPathfinder.Grid {
    private static final double BODY_RADIUS = .30;
    /** A narrow support probe prevents a wall touching the shoulder from becoming a fake stair. */
    private static final double SUPPORT_RADIUS = .10;
    private final World world;
    private final DoorController doors;
    public RoutineTerrain(World world, DoorController doors) { this.world = world; this.doors = doors; }
    public boolean loaded(int x, int z) { return world.isChunkLoaded(x >> 4, z >> 4); }
    public Block block(int x, int y, int z) { return y >= world.getMinHeight() && y < world.getMaxHeight() && loaded(x,z) ? world.getBlockAt(x,y,z) : null; }
    public static boolean hazard(Material m) { return m == Material.LAVA || m == Material.WATER || m == Material.FIRE || m == Material.SOUL_FIRE || m == Material.CACTUS || m == Material.MAGMA_BLOCK || m == Material.CAMPFIRE || m == Material.SOUL_CAMPFIRE || m == Material.SWEET_BERRY_BUSH || m == Material.POWDER_SNOW || m == Material.POINTED_DRIPSTONE || m == Material.NETHER_PORTAL || m == Material.END_PORTAL; }

    public double height(Node n) {
        Block floor = block(n.x(), n.y()-1, n.z());
        if (floor == null) return n.y();
        double base = n.y()-1;
        double height = base;
        for (BoundingBox box : collision(floor,n.x(),n.y()-1,n.z()))
            if (overFootprint(box,n.x()+.5,n.z()+.5,SUPPORT_RADIUS)) height=Math.max(height,box.getMaxY());
        return height;
    }
    public Location location(Node n) { return new Location(world, n.x()+.5, height(n), n.z()+.5); }

    @Override public boolean stand(Node n) {
        Block floor = block(n.x(), n.y()-1, n.z());
        if (floor == null || hazard(floor.getType()) || floor.getBlockData() instanceof Door) return false;
        // Carpet, snow layers and other thin collision shapes are valid supports even though
        // Bukkit does not classify them as full solid blocks.
        double y = height(n);
        return y > n.y()-1 + 1e-7 && y <= n.y() + .01 && fits(n.x()+.5,y,n.z()+.5, true);
    }
    @Override public boolean edge(Node from, Node to) {
        double a = height(from), b = height(to);
        return Math.abs(a-b) <= 1.01 && fits(from.x()+.5,Math.max(a,b),from.z()+.5,true)
                && fits(to.x()+.5,Math.max(a,b),to.z()+.5,true);
    }

    public boolean fits(double x, double y, double z, boolean planned) {
        BoundingBox body = new BoundingBox(x-BODY_RADIUS,y+.015,z-BODY_RADIUS,x+BODY_RADIUS,y+1.95,z+BODY_RADIUS);
        for (int bx=(int)Math.floor(body.getMinX()); bx <= (int)Math.floor(body.getMaxX()); bx++)
            for (int bz=(int)Math.floor(body.getMinZ()); bz <= (int)Math.floor(body.getMaxZ()); bz++)
                for (int by=(int)Math.floor(body.getMinY()); by <= (int)Math.floor(body.getMaxY()); by++) {
                    Block block = block(bx,by,bz);
                    if (block == null || hazard(block.getType())) return false;
                    if (block.getBlockData() instanceof Door door) {
                        if (door.isOpen() || planned && doors.canOpen(block)) continue;
                        return false;
                    }
                    if (!block.isPassable()) for (BoundingBox box : collision(block,bx,by,bz))
                        if (box.overlaps(body)) return false;
                }
        return true;
    }

    /** Ground directly below the feet. The support probe is intentionally narrower than
     * the body: side walls are collision obstacles, not surfaces that the NPC should climb. */
    public double supportHeight(double x,double y,double z) {
        return supportHeight(x,y,z,1.01);
    }
    public double supportHeight(double x,double y,double z,double rise) {
        return supportHeight(x,y,z,rise,SUPPORT_RADIUS);
    }

    /** Directional probe used while approaching a stair/full-block riser. */
    double supportHeight(double x,double y,double z,double rise,double radius) {
        double highest=Double.NEGATIVE_INFINITY;
        for(int bx=(int)Math.floor(x-radius);bx<=(int)Math.floor(x+radius);bx++)
            for(int bz=(int)Math.floor(z-radius);bz<=(int)Math.floor(z+radius);bz++)
                for(int by=Math.max(world.getMinHeight(),(int)Math.floor(y-1.01)-1);
                        by<=Math.min(world.getMaxHeight()-1,(int)Math.floor(y+rise));by++) {
                    Block floor=block(bx,by,bz);
                    if(floor==null)return Double.NaN;
                    if(floor.getBlockData() instanceof Door)continue;
                    for(BoundingBox box:collision(floor,bx,by,bz)) {
                        double top=box.getMaxY();
                        if(!overFootprint(box,x,z,radius) || top<y-1.01 || top>y+rise)continue;
                        if(hazard(floor.getType()))return Double.NaN;
                        highest=Math.max(highest,top);
                    }
                }
        return Double.isFinite(highest)?highest:Double.NaN;
    }

    private static boolean overFootprint(BoundingBox box,double x,double z,double radius) {
        return box.getMaxX()>x-radius+1e-7 && box.getMinX()<x+radius-1e-7
                && box.getMaxZ()>z-radius+1e-7 && box.getMinZ()<z+radius-1e-7;
    }
    /** Paper supplies block-local voxel boxes; never use the enclosing stair cube. */
    private static Collection<BoundingBox> collision(Block block,int x,int y,int z) {
        VoxelShape shape=block.getCollisionShape();
        if(shape==null) {
            // Some test/integration adapters do not expose voxel shapes and may also
            // return null for blocks such as AIR. Treat that as no collision instead
            // of passing null to List.of(), which throws NullPointerException.
            BoundingBox box=block.getBoundingBox();
            return box==null?List.of():List.of(box);
        }
        return shape.getBoundingBoxes().stream().map(box->box.clone().shift(x,y,z)).toList();
    }

    public Node near(Location l) {
        int y = (int)Math.ceil(l.getY()-.02);
        for (int dy : new int[]{0,1,-1}) { Node n = new Node(l.getBlockX(),y+dy,l.getBlockZ()); if (stand(n)) return n; }
        return null;
    }

    /** All locally valid approach tiles. Furniture can be entered from more than one side;
     * callers may retry another side when native pathfinding proves the first one unreachable. */
    public List<Node> approaches(RoutineGoal.Point point, boolean furniture) {
        if (!furniture) {
            Node n=near(point.location(world));
            return n==null?List.of():List.of(n);
        }
        ArrayList<Node> result=new ArrayList<>(4);
        for (int[] d : new int[][]{{0,1},{1,0},{0,-1},{-1,0}}) {
            Node n = near(new Location(world, point.x()+d[0]+.5, point.y(), point.z()+d[1]+.5));
            if (n != null && !result.contains(n)) result.add(n);
        }
        return List.copyOf(result);
    }
    public Node approach(RoutineGoal.Point point, boolean furniture) {
        List<Node> nodes=approaches(point,furniture);
        return nodes.isEmpty()?null:nodes.getFirst();
    }
}
