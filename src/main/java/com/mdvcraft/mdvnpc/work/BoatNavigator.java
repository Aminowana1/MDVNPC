package com.mdvcraft.mdvnpc.work;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Levelled;
import org.bukkit.util.BoundingBox;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/** Bounded surface navigation for a real boat. It never requests an unloaded block. */
final class BoatNavigator {
    static final double RADIUS = .6875, IMMERSION = .30, CLEARANCE = 2.60;
    private static final int MAX_NODES = 4096, MAX_RANGE = 96;
    private record Tile(int x,int z) {}
    private record Cell(int x,int y,int z) {}
    private record Open(Tile tile,double distance,double score) implements Comparable<Open> {
        @Override public int compareTo(Open other) {return Double.compare(score,other.score);}
    }
    private final World world;
    private Map<Cell,Block> queryBlocks;
    BoatNavigator(World world) {this.world=world;}

    /** A source-water surface also permits ordinary underwater plants. */
    static boolean water(Block block) {
        Material material=block.getType();
        if(material==Material.WATER)
            return !(block.getBlockData() instanceof Levelled level) || level.getLevel()==0;
        return material==Material.BUBBLE_COLUMN || material==Material.SEAGRASS
                || material==Material.TALL_SEAGRASS || material==Material.KELP || material==Material.KELP_PLANT;
    }
    private Block block(int x,int y,int z) {
        if(y<world.getMinHeight() || y>=world.getMaxHeight() || !world.isChunkLoaded(x>>4,z>>4))return null;
        return queryBlocks==null?world.getBlockAt(x,y,z)
                :queryBlocks.computeIfAbsent(new Cell(x,y,z),cell->world.getBlockAt(cell.x,cell.y,cell.z));
    }
    static boolean loaded(Location location) {
        return location!=null && location.getWorld()!=null && Double.isFinite(location.getX())
                && Double.isFinite(location.getY()) && Double.isFinite(location.getZ())
                && location.getWorld().isChunkLoaded(location.getBlockX()>>4,location.getBlockZ()>>4);
    }
    private double surface(int x,int z,double reference) {
        int middle=(int)Math.floor(reference);
        for(int offset:new int[]{0,-1,1,-2,2}) {
            int y=middle+offset;Block water=block(x,y,z),above=block(x,y+1,z);
            if(water!=null && above!=null && water(water) && !water(above))return y+1;
        }
        return Double.NaN;
    }
    Location waterTarget(double x,double z,double reference) {
        double y=surface((int)Math.floor(x),(int)Math.floor(z),reference);
        if(!Double.isFinite(y))return null;
        Location target=new Location(world,x,y+.04,z);
        return empty(new BoundingBox(x-.10,y+.01,z-.10,x+.10,y+.30,z+.10))?target:null;
    }
    Location position(Location requested) {
        if(requested==null || requested.getWorld()!=world || !loaded(requested))return null;
        double top=surface(requested.getBlockX(),requested.getBlockZ(),requested.getY());
        if(!Double.isFinite(top))return null;
        Location boat=new Location(world,requested.getX(),top-IMMERSION,requested.getZ(),requested.getYaw(),0);
        return valid(boat)?boat:null;
    }
    boolean valid(Location boat) {
        if(boat==null || boat.getWorld()!=world || !loaded(boat))return false;
        double x=boat.getX(),z=boat.getZ(),top=boat.getY()+IMMERSION;
        int minimumX=(int)Math.floor(x-RADIUS+1e-7),maximumX=(int)Math.floor(x+RADIUS-1e-7);
        int minimumZ=(int)Math.floor(z-RADIUS+1e-7),maximumZ=(int)Math.floor(z+RADIUS-1e-7);
        for(int bx=minimumX;bx<=maximumX;bx++)for(int bz=minimumZ;bz<=maximumZ;bz++) {
            double level=surface(bx,bz,top);
            if(!Double.isFinite(level) || Math.abs(level-top)>.01)return false;
        }
        // Hull and rider must clear banks, bridges, fences and partial-block geometry.
        return empty(new BoundingBox(x-RADIUS,boat.getY()+.08,z-RADIUS,
                x+RADIUS,boat.getY()+CLEARANCE,z+RADIUS));
    }
    private boolean empty(BoundingBox volume) {
        int minX=(int)Math.floor(volume.getMinX()),maxX=(int)Math.floor(volume.getMaxX()-1e-7);
        int minY=(int)Math.floor(volume.getMinY()),maxY=(int)Math.floor(volume.getMaxY()-1e-7);
        int minZ=(int)Math.floor(volume.getMinZ()),maxZ=(int)Math.floor(volume.getMaxZ()-1e-7);
        for(int x=minX;x<=maxX;x++)for(int z=minZ;z<=maxZ;z++)for(int y=minY;y<=maxY;y++) {
            Block block=block(x,y,z);if(block==null)return false;
            if(water(block) || block.getType().isAir())continue;
            var shape=block.getCollisionShape();
            if(shape!=null && shape.getBoundingBoxes()!=null) {
                for(BoundingBox local:shape.getBoundingBoxes())if(local.clone().shift(x,y,z).overlaps(volume))return false;
            } else {
                BoundingBox bounds=block.getBoundingBox();
                if(bounds!=null && bounds.overlaps(volume))return false;
                if(bounds==null && block.getType().isSolid())return false;
            }
        }
        return true;
    }
    boolean segment(Location start,Location end) {
        if(start==null || end==null || start.getWorld()!=world || end.getWorld()!=world
                || Math.abs(start.getY()-end.getY())>.01)return false;
        double distance=start.distance(end);
        if(!Double.isFinite(distance) || distance>MAX_RANGE+2)return false;
        int samples=Math.max(1,(int)Math.ceil(distance/.20));
        for(int i=0;i<=samples;i++) {
            double fraction=i/(double)samples;
            Location test=start.clone().add(end.toVector().subtract(start.toVector()).multiply(fraction));
            if(!valid(test))return false;
        }
        return true;
    }
    /** A small cosmetic float follows the same arc used by the casting animation. */
    boolean castClear(Location start,Location end) {
        Map<Cell,Block> previous=queryBlocks;queryBlocks=new HashMap<>();
        try{return casting(start,end);}finally{queryBlocks=previous;}
    }
    private boolean casting(Location start,Location end) {
        if(start==null || end==null || start.getWorld()!=world || end.getWorld()!=world
                || !loaded(start) || !loaded(end))return false;
        double distance=start.distance(end);
        if(!Double.isFinite(distance) || distance>16)return false;
        int samples=Math.max(1,(int)Math.ceil(distance/.10));
        for(int i=0;i<=samples;i++) {
            double fraction=i/(double)samples;
            Location point=start.clone().add(end.toVector().subtract(start.toVector()).multiply(fraction));
            point.add(0,4*.55*fraction*(1-fraction),0);
            if(!empty(new BoundingBox(point.getX()-.075,point.getY()-.075,point.getZ()-.075,
                    point.getX()+.075,point.getY()+.075,point.getZ()+.075)))return false;
        }
        return true;
    }
    List<Location> route(Location start,Location target) {
        // Geometry is stable within this synchronous query; never retain snapshots between ticks.
        Map<Cell,Block> previous=queryBlocks;queryBlocks=new HashMap<>();
        try{return search(start,target);}finally{queryBlocks=previous;}
    }
    private List<Location> search(Location start,Location target) {
        if(!valid(start) || !valid(target) || Math.abs(start.getY()-target.getY())>.01)return List.of();
        // Half-block nodes can follow the centre of a two-block-wide water channel.
        Tile first=new Tile((int)Math.round(start.getX()*2),(int)Math.round(start.getZ()*2));
        Tile last=new Tile((int)Math.round(target.getX()*2),(int)Math.round(target.getZ()*2));
        if(Math.max(Math.abs(first.x-last.x),Math.abs(first.z-last.z))>MAX_RANGE*2)return List.of();
        Location firstCentre=centre(first,start.getY()),lastCentre=centre(last,start.getY());
        if(!segment(start,firstCentre) || !segment(lastCentre,target))return List.of();
        Map<Tile,Double> best=new HashMap<>();Map<Tile,Tile> parents=new HashMap<>();
        Map<Tile,Boolean> checked=new HashMap<>();PriorityQueue<Open> open=new PriorityQueue<>();
        best.put(first,0d);open.add(new Open(first,0,heuristic(first,last)));
        int visited=0;
        while(!open.isEmpty() && visited++<MAX_NODES) {
            Open current=open.remove();
            if(current.distance>best.getOrDefault(current.tile,Double.POSITIVE_INFINITY))continue;
            if(current.tile.equals(last)) {
                ArrayList<Location> result=new ArrayList<>();Tile tile=last;
                while(tile!=null) {result.add(centre(tile,start.getY()));tile=parents.get(tile);}
                Collections.reverse(result);result.add(target.clone());return List.copyOf(result);
            }
            for(int[] delta:new int[][]{{1,0},{-1,0},{0,1},{0,-1}}) {
                Tile next=new Tile(current.tile.x+delta[0],current.tile.z+delta[1]);
                if(Math.max(Math.abs(next.x-first.x),Math.abs(next.z-first.z))>MAX_RANGE*2)continue;
                double distance=current.distance+.5;
                if(distance>=best.getOrDefault(next,Double.POSITIVE_INFINITY))continue;
                boolean fits=checked.computeIfAbsent(next,tile->valid(centre(tile,start.getY())));
                if(!fits || !segment(centre(current.tile,start.getY()),centre(next,start.getY())))continue;
                best.put(next,distance);parents.put(next,current.tile);
                open.add(new Open(next,distance,distance+heuristic(next,last)));
            }
        }
        return List.of();
    }
    private Location centre(Tile tile,double y) {return new Location(world,tile.x*.5,y,tile.z*.5);}
    private static double heuristic(Tile first,Tile last) {return .5*(Math.abs(first.x-last.x)+Math.abs(first.z-last.z));}
}
