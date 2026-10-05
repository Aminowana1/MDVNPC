package com.mdvcraft.mdvnpc.routine;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Door;
import org.bukkit.block.data.type.Gate;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.VoxelShape;
import org.bukkit.util.Vector;
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
        if(block==null || hazard(block.getType()) || block.getType().isAir()
                || fenceMaterial(block.getType()) || gateMaterial(block.getType()) || fenceSupportedBlock(block))return false;
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

    /** A slab, trapdoor or complete block directly above a fence remains part of that
     * barrier. A separate bridge floor with an intervening row is ordinary terrain. */
    private static boolean fenceSupportedBlock(Block floor) {
        World owner=floor.getWorld();
        if(owner==null)return false; // Some editor/test adapters expose geometry only.
        int below=floor.getY()-1;
        if(below<owner.getMinHeight())return false;
        // An editor can already hold the clicked Block without exposing a loaded chunk.
        // Unknown blocks below it are not evidence of a fence. Runtime landing/stand
        // checks separately require their entire queried area to be loaded.
        if(!owner.isChunkLoaded(floor.getX()>>4,floor.getZ()>>4))return false;
        Block lower=owner.getBlockAt(floor.getX(),below,floor.getZ());
        return lower!=null && closedFence(lower.getType(),lower.getBlockData());
    }

    private boolean fenceSupportedFloor(int x,int y,int z) {
        if(y<=minimumHeight)return false;
        Block lower=block(x,y-1,z);
        return lower==null || closedFence(lower.getType(),lower.getBlockData());
    }

    public double height(Node n) {
        return supportHeightAtNode(n,n.x()+.5,n.z()+.5);
    }

    /** The selected floor's actual surface under these feet. A stair's lower tread
     * can differ from the tile-centre height used to normalize Paper waypoints. */
    double supportHeightAtNode(Node n,double x,double z) {
        Block floor = block(n.x(), n.y()-1, n.z());
        if (floor == null) return n.y();
        double base = n.y()-1;
        double height = base;
        for (BoundingBox box : collision(floor,n.x(),n.y()-1,n.z()))
            if (overFootprint(box,x,z,SUPPORT_RADIUS)) height=Math.max(height,box.getMaxY());
        return height;
    }
    public Location location(Node n) { return new Location(world, n.x()+.5, height(n), n.z()+.5); }

    /** True when the support is a genuine sub-block collision surface rather than the
     * top of a full-height cube. Used only to recover an NPC that was restored slightly
     * inside carpet/slab/path-like collision; it must never pull entities through stone. */
    boolean partialSupport(Node n) {
        return partialSupport(n,n.x()+.5,n.z()+.5);
    }

    boolean partialSupport(Node n,double x,double z) {
        Block floor=block(n.x(),n.y()-1,n.z());
        if(floor==null || floor.getType().isAir() || hazard(floor.getType())
                || fenceMaterial(floor.getType()) || gateMaterial(floor.getType()) || floor.getBlockData() instanceof Door
                || fenceSupportedFloor(n.x(),n.y()-1,n.z()))return false;
        double base=n.y()-1, top=base, bottom=Double.POSITIVE_INFINITY;
        for(BoundingBox box:collision(floor,n.x(),n.y()-1,n.z())) {
            if(!overFootprint(box,x,z,SUPPORT_RADIUS))continue;
            top=Math.max(top,box.getMaxY());bottom=Math.min(bottom,box.getMinY());
        }
        // A top slab ends at an integer height too, but its collision starts halfway up
        // the block. Its thin geometry must not be confused with stone or a double slab.
        return top>base+EPSILON && top<=n.y()+EPSILON
                && (top<n.y()-EPSILON || bottom>base+EPSILON);
    }

    /** The known partial floor and verified partial neighbours below the same target may
     * be overlapped while exiting a shallow embedding. Walls, ceilings, complete cubes,
     * higher risers, hazards, closed doors and unloaded chunks still block. */
    boolean fitsPartialEscape(double x,double y,double z,Node support) {
        if(support==null || !partialSupport(support,x,z))return false;
        double top=supportHeightAtNode(support,x,z);
        if(y>top+EPSILON || y<top-.51-EPSILON)return false;
        Block floor=block(support.x(),support.y()-1,support.z());
        if(floor==null)return false;
        boolean beneathFeet=false;
        for(BoundingBox box:collision(floor,support.x(),support.y()-1,support.z()))
            if(Math.abs(box.getMaxY()-top)<=EPSILON && overFootprint(box,x,z,SUPPORT_RADIUS)) {
                beneathFeet=true;break;
            }
        return beneathFeet && fits(x,y,z,false,support,top);
    }

    @Override public boolean stand(Node n) {
        Block floor = block(n.x(), n.y()-1, n.z());
        if (floor == null || hazard(floor.getType()) || fenceMaterial(floor.getType())
                || gateMaterial(floor.getType()) || floor.getBlockData() instanceof Door
                || fenceSupportedFloor(n.x(),n.y()-1,n.z())) return false;
        // Carpet, snow layers and other thin collision shapes are valid supports even though
        // Bukkit does not classify them as full solid blocks.
        double y = height(n);
        return y > n.y()-1 + 1e-7 && y <= n.y() + .01 && fits(n.x()+.5,y,n.z()+.5, true);
    }
    @Override public boolean edge(Node from, Node to) {
        double a = height(from), b = height(to), delta=b-a;
        // Match RoutineNavigator's controlled movement envelope. Ascents and descents are
        // intentionally asymmetric: villagers may step/climb up 1.5 blocks and drop onto a
        // verified safe landing up to 2.3 blocks below. Clearance is checked at the higher
        // crossing height and again on the actual lower landing.
        if(delta>1.50+EPSILON || delta<-(2.30+EPSILON))return false;
        double high=Math.max(a,b);
        return fits(from.x()+.5,high,from.z()+.5,true)
                && fits(to.x()+.5,high,to.z()+.5,true)
                && fits(to.x()+.5,b,to.z()+.5,true);
    }

    public boolean fits(double x, double y, double z, boolean planned) {
        return fits(x,y,z,planned,null,Double.NaN);
    }

    /** Check a rising move against barriers at its original grounded height. Testing only
     * the elevated body would otherwise let a high climb or recovery arc cross a fence.
     * A route already on a legitimate higher floor can still pass above a lower fence. */
    public boolean recoveryHopFits(double x,double y,double z,double baseY,boolean planned) {
        if(!Double.isFinite(baseY) || baseY<minimumHeight || baseY>maximumHeight
                || Math.abs(y-baseY)>8+EPSILON || !fits(x,y,z,planned))return false;
        // Ordinary level/downward replay cannot hide a barrier below an elevated body.
        // The normal collision check is sufficient and avoids repeating its block scan.
        if(y<=baseY+EPSILON)return true;
        return projectedBarriersClear(x,y,z,baseY);
    }

    /** Check before the first vertical lift, while its original grounded height is still
     * known. The extra lower row catches fences capped at integer or fractional foot Y. */
    public boolean fenceClearAtBase(double x,double baseY,double z) {
        return coordinates(x,baseY,z) && baseY>=minimumHeight && baseY<=maximumHeight
                && projectedBarriersClear(x,baseY,z,baseY);
    }

    /** Execute an attempted recovery impulse against live physical voxels. Unlike a
     * planned hop, it needs neither support nor a safe landing and may start embedded.
     * Each axis is clipped independently, so a wall can stop forward travel without
     * cancelling the upward impulse. Existing penetration may only be reduced toward
     * its nearest exit. Fences and their direct caps additionally block horizontal
     * crossing at the original height; this projection never stops an in-place lift. */
    public Vector clipRecoveryHop(Location from,Vector requested,double baseY) {
        if(from==null || requested==null || from.getWorld()!=world
                || !coordinates(from.getX(),from.getY(),from.getZ()) || !Double.isFinite(baseY)
                || from.getY()<minimumHeight-8 || from.getY()>maximumHeight+8
                || Math.abs(from.getY()-baseY)>8
                || !Double.isFinite(requested.getX()) || !Double.isFinite(requested.getY())
                || !Double.isFinite(requested.getZ()))return new Vector();
        double dx=Math.max(-4,Math.min(4,requested.getX()));
        double dy=Math.max(-4,Math.min(4,requested.getY()));
        double dz=Math.max(-4,Math.min(4,requested.getZ()));
        if(!coordinates(from.getX()+dx,from.getY()+dy,from.getZ()+dz))return new Vector();
        // The physical feet start at Y itself so a descending impulse cannot sink
        // through the .015 tolerance used by normal terrain-fit queries.
        BoundingBox body=new BoundingBox(from.getX()-BODY_RADIUS,from.getY(),from.getZ()-BODY_RADIUS,
                from.getX()+BODY_RADIUS,from.getY()+1.95,from.getZ()+BODY_RADIUS);
        // A world boundary is physical too. If restored outside it, permit returning
        // toward the world without moving any farther out.
        dy=body.getMinY()<minimumHeight ? Math.max(0,dy)
                :body.getMaxY()>maximumHeight ? Math.min(0,dy)
                :Math.max(minimumHeight-body.getMinY(),Math.min(maximumHeight-body.getMaxY(),dy));
        for(int x=(int)Math.floor(body.getMinX());x<=(int)Math.floor(body.getMaxX()-EPSILON);x++)
            for(int z=(int)Math.floor(body.getMinZ());z<=(int)Math.floor(body.getMaxZ()-EPSILON);z++)
                if(!loaded(x,z))return new Vector();
        double minX=Math.min(body.getMinX(),body.getMinX()+dx),maxX=Math.max(body.getMaxX(),body.getMaxX()+dx);
        double minY=Math.min(body.getMinY(),body.getMinY()+dy),maxY=Math.max(body.getMaxY(),body.getMaxY()+dy);
        double minZ=Math.min(body.getMinZ(),body.getMinZ()+dz),maxZ=Math.max(body.getMaxZ(),body.getMaxZ()+dz);
        List<BoundingBox> physical=new ArrayList<>(),horizontal=new ArrayList<>();
        int firstY=Math.max(minimumHeight,Math.min((int)Math.floor(minY)-1,(int)Math.floor(baseY)-2));
        int lastY=Math.min(maximumHeight-1,(int)Math.floor(maxY));
        try(Update ignored=beginUpdate()) {
            for(int x=(int)Math.floor(minX);x<=(int)Math.floor(maxX);x++)
                for(int z=(int)Math.floor(minZ);z<=(int)Math.floor(maxZ);z++) {
                    if(!loaded(x,z)) {
                        physical.add(new BoundingBox(x,minY-1,z,x+1,maxY+1,z+1));
                        continue; // Never obtain a Bukkit Block from an unknown chunk.
                    }
                    for(int y=firstY;y<=lastY;y++) {
                        Block obstacle=block(x,y,z);
                        if(obstacle==null || obstacle.getType().isAir())continue;
                        BlockData data=obstacle.getBlockData();
                        Collection<BoundingBox> boxes=collision(obstacle,x,y,z,data);
                        physical.addAll(boxes);
                        boolean fence=closedFence(obstacle.getType(),data);
                        boolean cap=false;
                        if(!fence && y>minimumHeight) {
                            Block lower=block(x,y-1,z);
                            if(lower!=null && closedFence(lower.getType(),lower.getBlockData()))
                                for(BoundingBox lowerBox:collision(lower,x,y-1,z))
                                    if(lowerBox.getMaxY()>=baseY-.5-EPSILON) {cap=true;break;}
                        }
                        for(BoundingBox box:boxes)
                            if(cap || fence && box.getMaxY()>=baseY-.5-EPSILON)
                                horizontal.add(new BoundingBox(box.getMinX(),minY-1,box.getMinZ(),
                                        box.getMaxX(),maxY+1,box.getMaxZ()));
                    }
                }
        }
        dy=clipAxis(body,dy,physical,1);body.shift(0,dy,0);
        horizontal.addAll(physical);
        dx=clipAxis(body,dx,horizontal,0);body.shift(dx,0,0);
        dz=clipAxis(body,dz,horizontal,2);
        return new Vector(dx,dy,dz);
    }

    private static double clipAxis(BoundingBox body,double movement,List<BoundingBox> obstacles,int axis) {
        if(Math.abs(movement)<=EPSILON)return 0;
        double low=axis==0?body.getMinX():axis==1?body.getMinY():body.getMinZ();
        double high=axis==0?body.getMaxX():axis==1?body.getMaxY():body.getMaxZ();
        for(BoundingBox obstacle:obstacles) {
            if(axis!=0 && (body.getMaxX()<=obstacle.getMinX()+EPSILON || body.getMinX()>=obstacle.getMaxX()-EPSILON)
                    || axis!=1 && (body.getMaxY()<=obstacle.getMinY()+EPSILON || body.getMinY()>=obstacle.getMaxY()-EPSILON)
                    || axis!=2 && (body.getMaxZ()<=obstacle.getMinZ()+EPSILON || body.getMinZ()>=obstacle.getMaxZ()-EPSILON))continue;
            double obstacleLow=axis==0?obstacle.getMinX():axis==1?obstacle.getMinY():obstacle.getMinZ();
            double obstacleHigh=axis==0?obstacle.getMaxX():axis==1?obstacle.getMaxY():obstacle.getMaxZ();
            if(high<=obstacleLow+EPSILON) {
                if(movement>0)movement=Math.min(movement,Math.max(0,obstacleLow-high));
            } else if(low>=obstacleHigh-EPSILON) {
                if(movement<0)movement=Math.max(movement,Math.min(0,obstacleHigh-low));
            } else {
                // Escape an initial overlap via the nearer face, never push farther
                // through its centre. Equidistant embedded bodies may exit either way.
                double negativeExit=high-obstacleLow,positiveExit=obstacleHigh-low;
                if(movement>0 && positiveExit>negativeExit+EPSILON
                        || movement<0 && negativeExit>positiveExit+EPSILON)movement=0;
            }
            if(Math.abs(movement)<=EPSILON)return 0;
        }
        return movement;
    }

    private boolean projectedBarriersClear(double x,double y,double z,double baseY) {
        int firstY=Math.max(minimumHeight,(int)Math.floor(baseY)-2);
        int lastY=Math.min(maximumHeight-1,(int)Math.floor(Math.max(baseY,y)+1.95));
        for(int bx=(int)Math.floor(x-BODY_RADIUS);bx<=(int)Math.floor(x+BODY_RADIUS);bx++)
            for(int bz=(int)Math.floor(z-BODY_RADIUS);bz<=(int)Math.floor(z+BODY_RADIUS);bz++)
                for(int by=firstY;by<=lastY;by++) {
                    Block obstacle=block(bx,by,bz);
                    if(obstacle==null)return false;
                    if(obstacle.getType().isAir())continue;
                    BlockData data=obstacle.getBlockData();
                    if(!projectedBarrier(obstacle.getType(),data))continue;
                    for(BoundingBox box:collision(obstacle,bx,by,bz,data))
                        if((closedFence(obstacle.getType(),data)
                                ? box.getMaxY()>=baseY-.5-EPSILON : box.getMaxY()>baseY+EPSILON)
                                && overFootprint(box,x,z,BODY_RADIUS))return false;
                }
        return true;
    }

    /** A recovery hop must end on an actual narrow foot contact with a safe body-wide
     * landing. Bounds stay independent of configurable climb height and never load chunks. */
    public double recoveryHopLanding(double x,double y,double z,double maximumDrop,double maximumRise) {
        if(!coordinates(x,y,z) || !Double.isFinite(maximumDrop) || maximumDrop<0 || maximumDrop>8
                || !Double.isFinite(maximumRise) || maximumRise<0 || maximumRise>4)return Double.NaN;
        double low=y-maximumDrop,high=y+maximumRise,top=Double.NEGATIVE_INFINITY;
        double unsafeTop=Double.NEGATIVE_INFINITY;
        int firstY=Math.max(minimumHeight,(int)Math.floor(low)-1);
        int lastY=Math.min(maximumHeight-1,(int)Math.floor(high));
        for(int bx=(int)Math.floor(x-SUPPORT_RADIUS);bx<=(int)Math.floor(x+SUPPORT_RADIUS);bx++)
            for(int bz=(int)Math.floor(z-SUPPORT_RADIUS);bz<=(int)Math.floor(z+SUPPORT_RADIUS);bz++)
                for(int by=firstY;by<=lastY;by++) {
                    Block floor=block(bx,by,bz);
                    if(floor==null)return Double.NaN;
                    Material material=floor.getType();
                    if(material.isAir())continue;
                    BlockData data=floor.getBlockData();
                    if(data instanceof Door)continue;
                    boolean fenceCap=fenceSupportedFloor(bx,by,bz);
                    for(BoundingBox box:collision(floor,bx,by,bz,data)) {
                        double surface=box.getMaxY();
                        if(surface<low-EPSILON || surface>high+EPSILON || !overFootprint(box,x,z,SUPPORT_RADIUS))continue;
                        top=Math.max(top,surface);
                        if(hazard(material) || landingBarrier(material,data) || fenceCap)unsafeTop=Math.max(unsafeTop,surface);
                    }
                }
        if(!Double.isFinite(top) || unsafeTop>=top-EPSILON || !fits(x,top,z,false))return Double.NaN;
        FallColumn column=fallColumn(x,top,z,0);
        if(!column.safe() || !Double.isFinite(column.support()) || Math.abs(column.support()-top)>EPSILON)return Double.NaN;
        // Reject even a barrier touching only the shoulder at the landing height. A real
        // bridge above its top remains usable because the barrier is below this surface.
        for(int bx=(int)Math.floor(x-BODY_RADIUS);bx<=(int)Math.floor(x+BODY_RADIUS);bx++)
            for(int bz=(int)Math.floor(z-BODY_RADIUS);bz<=(int)Math.floor(z+BODY_RADIUS);bz++)
                for(int by=Math.max(minimumHeight,(int)Math.floor(top)-1);by<=Math.min(maximumHeight-1,(int)Math.floor(top+.02));by++) {
                    Block floor=block(bx,by,bz);
                    if(floor==null)return Double.NaN;
                    if(floor.getType().isAir())continue;
                    BlockData data=floor.getBlockData();
                    if(!landingBarrier(floor.getType(),data))continue;
                    for(BoundingBox box:collision(floor,bx,by,bz,data))
                        if(Math.abs(box.getMaxY()-top)<=.02 && overFootprint(box,x,z,BODY_RADIUS))return Double.NaN;
                }
        return top;
    }

    private static boolean fenceMaterial(Material material) { return material.name().endsWith("_FENCE"); }
    private static boolean gateMaterial(Material material) { return material.name().endsWith("_FENCE_GATE"); }
    private static boolean closedFence(Material material,BlockData data) {
        return fenceMaterial(material) || gateMaterial(material) && (!(data instanceof Gate gate) || !gate.isOpen());
    }
    private static boolean projectedBarrier(Material material,BlockData data) {
        return closedFence(material,data) || material.name().endsWith("_WALL");
    }
    private static boolean landingBarrier(Material material,BlockData data) {
        return projectedBarrier(material,data) || thinClimbBarrier(material);
    }
    private boolean fits(double x,double y,double z,boolean planned,Node partialFloor,double partialTop) {
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
                    Collection<BoundingBox> boxes=collision(block,bx,by,bz,data);
                    boolean escapeFloor=false;
                    if(partialFloor!=null) {
                        escapeFloor=bx==partialFloor.x() && by==partialFloor.y()-1 && bz==partialFloor.z();
                        if(!escapeFloor) {
                            Node neighbour=new Node(bx,by+1,bz);
                            // Use actual feet for a stair's lower border tread. A flat partial
                            // floor touching only the shoulder can use its central column too.
                            escapeFloor=(partialSupport(neighbour,x,z) && supportHeightAtNode(neighbour,x,z)<=partialTop+EPSILON)
                                    || (partialSupport(neighbour) && height(neighbour)<=partialTop+EPSILON);
                        }
                    }
                    for (BoundingBox box : boxes) {
                        if(escapeFloor && box.getMaxY()<=partialTop+EPSILON)continue;
                        if (box.overlaps(body)) return false;
                    }
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
        return supportHeight(x,y,z,rise,radius,false);
    }

    /** A fence/wall extends above its source block; panes and bars form thin vertical
     * barriers. Their tops are not evidence of an unannounced stair. Ordinary ground
     * and fall probes still see their complete physical collision. */
    double climbSupportHeight(double x,double y,double z,double rise,double radius) {
        return supportHeight(x,y,z,rise,radius,true);
    }

    private double supportHeight(double x,double y,double z,double rise,double radius,boolean climb) {
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
                    if(climb && thinClimbBarrier(floor.getType()))continue;
                    BlockData data=floor.getBlockData();
                    if(data instanceof Door)continue;
                    boolean fenceCap=fenceSupportedFloor(bx,by,bz);
                    for(BoundingBox box:collision(floor,bx,by,bz,data)) {
                        double top=box.getMaxY();
                        if(climb && top>by+1+EPSILON)continue;
                        if(!overFootprint(box,x,z,radius) || top<y-1.01 || top>y+rise)continue;
                        if(hazard(floor.getType()) || closedFence(floor.getType(),data) || fenceCap)unsafeHighest=Math.max(unsafeHighest,top);
                        highest=Math.max(highest,top);
                    }
                }
        return Double.isFinite(highest) && unsafeHighest<highest-EPSILON?highest:Double.NaN;
    }

    private static boolean thinClimbBarrier(Material material) {
        return material==Material.IRON_BARS || material==Material.GLASS_PANE
                || material.name().endsWith("_GLASS_PANE");
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
                    BlockData data=floor.getBlockData();
                    if(data instanceof Door)continue;
                    boolean fenceCap=fenceSupportedFloor(bx,by,bz);
                    if(hazard(floor.getType()) && by+1>=bottom-EPSILON && by<=y+.02
                            && bx+1>x-BODY_RADIUS+EPSILON && bx<x+BODY_RADIUS-EPSILON
                            && bz+1>z-BODY_RADIUS+EPSILON && bz<z+BODY_RADIUS-EPSILON)
                        unsafeTop=Math.max(unsafeTop,by+1);
                    for(BoundingBox box:collision(floor,bx,by,bz,data)) {
                        double top=box.getMaxY();
                        if(top<bottom-EPSILON || top>y+.02 || !overFootprint(box,x,z,BODY_RADIUS))continue;
                        highest=Math.max(highest,top);
                        if(hazard(floor.getType()) || closedFence(floor.getType(),data) || fenceCap)unsafeTop=Math.max(unsafeTop,top);
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
