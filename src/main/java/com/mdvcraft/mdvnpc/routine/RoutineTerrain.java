package com.mdvcraft.mdvnpc.routine;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Door;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.VoxelShape;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import com.mdvcraft.mdvnpc.routine.BoundedPathfinder.Node;

/** Conservative pedestrian geometry. Never asks Bukkit for an unloaded block. */
public final class RoutineTerrain implements BoundedPathfinder.Grid {
    private static final double BODY_RADIUS = .30;
    /** A narrow support probe prevents a wall touching the shoulder from becoming a fake stair. */
    private static final double SUPPORT_RADIUS = .10;
    private static final int MAX_CACHED_COLLISIONS = 512;
    private static final double EPSILON = 1e-7;
    private final World world;
    private final DoorController doors;
    private final int minimumHeight, maximumHeight;
    private Map<Node, CollisionSnapshot> collisionCache;
    private int updateDepth;
    private record CollisionSnapshot(Material material, BlockData state, Collection<BoundingBox> boxes) {}
    /** A finite support is a verified landing; NaN with safe=true means a loaded empty column. */
    public record FallColumn(double support, boolean safe) {}

    public RoutineTerrain(World world, DoorController doors) {
        this.world = world; this.doors = doors;
        minimumHeight=world.getMinHeight(); maximumHeight=world.getMaxHeight();
    }

    /** Reuse converted voxels only during one synchronous movement update. Long-lived
     * terrain users remain uncached unless they explicitly open this scope. */
    public Update beginUpdate() {
        if(updateDepth++==0) {
            if(collisionCache==null)collisionCache=new HashMap<>();
            else collisionCache.clear();
        }
        return new Update();
    }
    public final class Update implements AutoCloseable {
        private boolean closed;
        private Update() {}
        @Override public void close() {
            if(closed)return;
            closed=true;
            if(--updateDepth==0)collisionCache.clear();
        }
    }
    public boolean loaded(int x, int z) { return world.isChunkLoaded(x >> 4, z >> 4); }
    public Block block(int x, int y, int z) { return y >= minimumHeight && y < maximumHeight && loaded(x,z) ? world.getBlockAt(x,y,z) : null; }
    public static boolean hazard(Material m) { return m == Material.LAVA || m == Material.WATER || m == Material.FIRE || m == Material.SOUL_FIRE || m == Material.CACTUS || m == Material.MAGMA_BLOCK || m == Material.CAMPFIRE || m == Material.SOUL_CAMPFIRE || m == Material.SWEET_BERRY_BUSH || m == Material.POWDER_SNOW || m == Material.POINTED_DRIPSTONE || m == Material.NETHER_PORTAL || m == Material.END_PORTAL; }

    /** Editor/runtime helper: a walking surface is defined by collision, not Material#isSolid.
     * Carpets, snow layers and other thin supports are intentionally accepted. */
    public static boolean walkingSurface(Block block) {
        if(block==null || hazard(block.getType()) || block.getType().isAir())return false;
        // Preserve the old editor behaviour for ordinary floors. Some Bukkit/MockBukkit
        // implementations do not expose a useful collision shape for full solid blocks.
        if(block.getType().isSolid())return true;
        VoxelShape shape=block.getCollisionShape();
        if(shape!=null) {
            Collection<BoundingBox> boxes=shape.getBoundingBoxes();
            if(boxes!=null && !boxes.isEmpty())return true;
        }
        BoundingBox box=block.getBoundingBox();
        return box!=null && box.getMaxX()-box.getMinX()>EPSILON
                && box.getMaxY()-box.getMinY()>EPSILON && box.getMaxZ()-box.getMinZ()>EPSILON;
    }

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

    /** True when the support is a genuine sub-block collision surface rather than the
     * top of a full-height cube. Used only to recover an NPC that was restored slightly
     * inside carpet/slab/path-like collision; it must never pull entities through stone. */
    boolean partialSupport(Node n) {
        Block floor=block(n.x(),n.y()-1,n.z());
        if(floor==null || floor.getType().isAir() || hazard(floor.getType()) || floor.getBlockData() instanceof Door)return false;
        double top=height(n);
        return top>n.y()-1+EPSILON && top<n.y()-EPSILON;
    }

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
        if(!coordinates(x,y,z) || y+.015<minimumHeight || y+1.95>maximumHeight)return false;
        BoundingBox body = new BoundingBox(x-BODY_RADIUS,y+.015,z-BODY_RADIUS,x+BODY_RADIUS,y+1.95,z+BODY_RADIUS);
        // Fences and walls are 1.5 blocks high: their source block can be below the feet.
        // Clamp this extra row so the lowest valid floor never queries below the world.
        int firstY=Math.max(minimumHeight,(int)Math.floor(body.getMinY())-1);
        int lastY=Math.min(maximumHeight-1,(int)Math.floor(body.getMaxY()));
        for (int bx=(int)Math.floor(body.getMinX()); bx <= (int)Math.floor(body.getMaxX()); bx++)
            for (int bz=(int)Math.floor(body.getMinZ()); bz <= (int)Math.floor(body.getMaxZ()); bz++)
                for (int by=firstY; by <= lastY; by++) {
                    Block block = block(bx,by,bz);
                    if (block == null) return false;
                    Material material=block.getType();
                    if(material.isAir())continue;
                    // The extra below-feet row is only a collision source. A hazard beneath
                    // an intervening safe floor does not touch this body.
                    if (hazard(material) && bx<body.getMaxX() && bx+1>body.getMinX()
                            && by<body.getMaxY() && by+1>body.getMinY()
                            && bz<body.getMaxZ() && bz+1>body.getMinZ()) return false;
                    BlockData data=block.getBlockData();
                    if (data instanceof Door door && !door.isOpen()
                            && by+1>body.getMinY() && by<body.getMaxY()) {
                        if (planned && doors.canOpen(block)) continue;
                        return false;
                    }
                    // An open door still has a swung collision panel. Read its live voxels
                    // after DoorController opens it, rather than treating the whole tile as air.
                    for (BoundingBox box : collision(block,bx,by,bz,data))
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
        if(!coordinates(x,y,z) || !Double.isFinite(rise) || !Double.isFinite(radius) || radius<=0 || radius>BODY_RADIUS)return Double.NaN;
        double highest=Double.NEGATIVE_INFINITY;
        double unsafeHighest=Double.NEGATIVE_INFINITY;
        for(int bx=(int)Math.floor(x-radius);bx<=(int)Math.floor(x+radius);bx++)
            for(int bz=(int)Math.floor(z-radius);bz<=(int)Math.floor(z+radius);bz++)
                for(int by=Math.max(minimumHeight,(int)Math.floor(y-1.01)-1);
                        by<=Math.min(maximumHeight-1,(int)Math.floor(y+rise));by++) {
                    Block floor=block(bx,by,bz);
                    if(floor==null)return Double.NaN;
                    if(floor.getType().isAir())continue;
                    if(floor.getBlockData() instanceof Door)continue;
                    for(BoundingBox box:collision(floor,bx,by,bz)) {
                        double top=box.getMaxY();
                        if(!overFootprint(box,x,z,radius) || top<y-1.01 || top>y+rise)continue;
                        if(hazard(floor.getType()))unsafeHighest=Math.max(unsafeHighest,top);
                        highest=Math.max(highest,top);
                    }
                }
        return Double.isFinite(highest) && unsafeHighest<highest-EPSILON?highest:Double.NaN;
    }

    /** A bounded fall probe over the complete body footprint. No chunk is loaded and no
     * block below the world is read. Hazards below a higher safe landing are shielded by it. */
    public FallColumn fallColumn(double x,double y,double z,double maximumDrop) {
        if(!coordinates(x,y,z) || !Double.isFinite(maximumDrop) || maximumDrop<0
                || !fits(x,y,z,false))return new FallColumn(Double.NaN,false);
        double bottom=y-Math.min(8,maximumDrop), highest=Double.NEGATIVE_INFINITY;
        double unsafeTop=Double.NEGATIVE_INFINITY;
        int firstY=Math.max(minimumHeight,(int)Math.floor(bottom)-1);
        int lastY=Math.min(maximumHeight-1,(int)Math.floor(y+.02));
        int firstX=(int)Math.floor(x-BODY_RADIUS), lastX=(int)Math.floor(x+BODY_RADIUS);
        int firstZ=(int)Math.floor(z-BODY_RADIUS), lastZ=(int)Math.floor(z+BODY_RADIUS);
        for(int bx=firstX;bx<=lastX;bx++)for(int bz=firstZ;bz<=lastZ;bz++)
            if(!loaded(bx,bz))return new FallColumn(Double.NaN,false);
        // Inspect nearer rows first. Once a landing shields all remaining 1.5-high
        // collision sources, an idle NPC needs no scan through the eight blocks below it.
        for(int by=lastY;by>=firstY;by--) {
            for(int bx=firstX;bx<=lastX;bx++)
                for(int bz=firstZ;bz<=lastZ;bz++) {
                    Block floor=block(bx,by,bz);
                    if(floor==null)return new FallColumn(Double.NaN,false);
                    if(floor.getType().isAir())continue;
                    if(floor.getBlockData() instanceof Door)continue;
                    if(hazard(floor.getType()) && by+1>=bottom-EPSILON && by<=y+.02
                            && bx+1>x-BODY_RADIUS+EPSILON && bx<x+BODY_RADIUS-EPSILON
                            && bz+1>z-BODY_RADIUS+EPSILON && bz<z+BODY_RADIUS-EPSILON)
                        unsafeTop=Math.max(unsafeTop,by+1);
                    for(BoundingBox box:collision(floor,bx,by,bz)) {
                        double top=box.getMaxY();
                        if(top<bottom-EPSILON || top>y+.02 || !overFootprint(box,x,z,BODY_RADIUS))continue;
                        highest=Math.max(highest,top);
                        if(hazard(floor.getType()))unsafeTop=Math.max(unsafeTop,top);
                    }
                }
            if(Double.isFinite(highest) && highest>=by+.5-EPSILON)break;
        }
        if(!Double.isFinite(highest))return new FallColumn(Double.NaN,!Double.isFinite(unsafeTop));
        if(unsafeTop>=highest-EPSILON || Math.abs(highest-y)>EPSILON && !fits(x,highest,z,false))
            return new FallColumn(Double.NaN,false);
        return new FallColumn(highest,true);
    }
    public double safeSupportBelow(double x,double y,double z,double maximumDrop) {
        FallColumn column=fallColumn(x,y,z,maximumDrop);
        return column.safe()?column.support():Double.NaN;
    }

    private static boolean coordinates(double x,double y,double z) {
        // Keep the integer block loops finite even for malformed integration coordinates.
        return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z)
                && x>Integer.MIN_VALUE+2.0 && x<Integer.MAX_VALUE-2.0
                && z>Integer.MIN_VALUE+2.0 && z<Integer.MAX_VALUE-2.0;
    }

    private static boolean overFootprint(BoundingBox box,double x,double z,double radius) {
        return box.getMaxX()>x-radius+1e-7 && box.getMinX()<x+radius-1e-7
                && box.getMaxZ()>z-radius+1e-7 && box.getMinZ()<z+radius-1e-7;
    }
    /** Paper supplies block-local voxel boxes; never use the enclosing stair cube. */
    private Collection<BoundingBox> collision(Block block,int x,int y,int z) {
        return collision(block,x,y,z,null);
    }
    private Collection<BoundingBox> collision(Block block,int x,int y,int z,BlockData knownData) {
        Material material=block.getType();
        if(material.isAir())return List.of();
        BlockData data=knownData==null?block.getBlockData():knownData;
        // DoorController may open a panel between two substeps of this same update.
        // Other state changes are observed through their fresh BlockData snapshot.
        if(updateDepth>0 && !(data instanceof Door)) {
            Node key=new Node(x,y,z);
            CollisionSnapshot saved=collisionCache.get(key);
            if(saved!=null && saved.material()==material && Objects.equals(saved.state(),data))return saved.boxes();
            Collection<BoundingBox> boxes=readCollision(block,x,y,z);
            if(saved!=null || collisionCache.size()<MAX_CACHED_COLLISIONS)
                collisionCache.put(key,new CollisionSnapshot(material,data,boxes));
            return boxes;
        }
        return readCollision(block,x,y,z);
    }
    private static Collection<BoundingBox> readCollision(Block block,int x,int y,int z) {
        VoxelShape shape=block.getCollisionShape();
        if(shape==null) {
            // Some test/integration adapters do not expose voxel shapes and may also
            // return null for blocks such as AIR. Treat that as no collision instead
            // of passing null to List.of(), which throws NullPointerException.
            BoundingBox box=block.getBoundingBox();
            return box==null?List.of():List.of(box);
        }
        Collection<BoundingBox> local=shape.getBoundingBoxes();
        if(local==null || local.isEmpty())return List.of();
        ArrayList<BoundingBox> result=new ArrayList<>(local.size());
        for(BoundingBox box:local)result.add(box.clone().shift(x,y,z));
        return List.copyOf(result);
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
