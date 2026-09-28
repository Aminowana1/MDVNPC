package com.mdvcraft.mdvnpc.routine;

import com.mdvcraft.mdvnpc.runtime.ActiveNpc;
import com.mdvcraft.mdvnpc.routine.BoundedPathfinder.Node;
import org.bukkit.Location;
import java.util.*;
import java.util.function.BiPredicate;

/** Controlled short movement steps keep the vanilla villager brain disabled. */
public final class RoutineNavigator {
    public enum Result { MOVING, ARRIVED, WAITING }
    private record RouteKey(UUID world, Node start, Node end) {}
    private final LinkedHashMap<RouteKey,List<Node>> cache = new LinkedHashMap<>(16,.75f,true);
    private final DoorController doors;
    private final BiPredicate<ActiveNpc,Location> teleport;
    private final int maxNodes, cacheSize;
    private final Map<String, Travel> travels = new LinkedHashMap<>();
    private long cursor;
    private static final class Travel {
        RoutineTerrain terrain; RouteKey key; BoundedPathfinder search;
        List<Node> path; int index; long retry; Location destination;
    }
    public RoutineNavigator(DoorController doors, BiPredicate<ActiveNpc,Location> teleport, int maxNodes, int cacheSize) {
        this.doors = doors; this.teleport = teleport; this.maxNodes = maxNodes; this.cacheSize = cacheSize;
    }
    public void searchBudget(int nodes, long nanos) {
        if (travels.isEmpty()) return;
        List<Travel> jobs = new ArrayList<>(travels.values()); long deadline = System.nanoTime()+nanos;
        for (int i=0; i<jobs.size() && nodes>0 && System.nanoTime()<deadline; i++) {
            Travel travel = jobs.get((int)Math.floorMod(cursor++,jobs.size()));
            if (travel.search == null) continue;
            nodes -= travel.search.advance(Math.min(nodes,16));
            if (!travel.search.done()) continue;
            travel.path = travel.search.result(); travel.search = null; travel.index = 0;
            if (travel.path != null && cacheSize > 0) {
                cache.put(travel.key,travel.path);
                while (cache.size()>cacheSize) cache.remove(cache.keySet().iterator().next());
            }
        }
    }
    public Result move(ActiveNpc npc, Location destination, double speed, long tick, int cadence) {
        String id = npc.definition().id(); Location current = npc.entity().getLocation();
        if (!current.getWorld().equals(destination.getWorld())) return Result.WAITING;
        RoutineTerrain terrain = new RoutineTerrain(current.getWorld(),doors);
        if (current.distanceSquared(destination)<.025) { cancel(id); return Result.ARRIVED; }
        Travel t = travels.computeIfAbsent(id,k -> new Travel());
        if (t.destination == null || t.destination.distanceSquared(destination) > .01) { cancel(id); t = new Travel(); travels.put(id,t); t.destination=destination.clone(); }
        if (t.path == null && t.search == null) {
            if (tick<t.retry) return Result.WAITING;
            t.retry=tick+100;
            Node start=terrain.near(current), end=terrain.near(destination);
            if (start==null || end==null) return Result.WAITING;
            t.terrain=terrain; t.key=new RouteKey(current.getWorld().getUID(),start,end);
            t.path=cache.get(t.key); t.index=0;
            if (t.path==null) t.search=new BoundedPathfinder(terrain,start,end,maxNodes);
        }
        if (t.search!=null || t.path==null) return Result.WAITING;
        if (t.index>=t.path.size()) { cancel(id); return Result.ARRIVED; }
        Node node=t.path.get(t.index);
        if (!terrain.stand(node)) { failed(t,tick); return Result.WAITING; }
        Location next=terrain.location(node);
        if (current.distanceSquared(next)<.01) { t.index++; return Result.MOVING; }
        // Raise before a step; descend after clearing its edge. Never cut diagonally through a solid block.
        Location step=next.clone();
        if (next.getY()>current.getY()+.015) { step.setX(current.getX()); step.setZ(current.getZ()); }
        else if (Math.hypot(next.getX()-current.getX(),next.getZ()-current.getZ())>.02) step.setY(current.getY());
        var delta=step.toVector().subtract(current.toVector());
        double length=delta.length(), distance=speed*cadence/20.0;
        if (length>distance) delta.multiply(distance/length);
        step=current.clone().add(delta);
        if (Math.abs(delta.getX())+Math.abs(delta.getZ())>.001) step.setDirection(new org.bukkit.util.Vector(delta.getX(),0,delta.getZ()));
        int samples=Math.max(1,(int)Math.ceil(delta.length()/.2));
        for(int i=1;i<=samples;i++) {
            Location check=current.clone().add(delta.clone().multiply((double)i/samples));
            if(!doors.openNear(npc.entity(),check,tick) || !terrain.fits(check.getX(),check.getY(),check.getZ(),false)) {
                failed(t,tick); return Result.WAITING;
            }
        }
        if(!teleport.test(npc,step)) {failed(t,tick);return Result.WAITING;}
        return Result.MOVING;
    }
    private void failed(Travel t,long tick) { if (t.key!=null) cache.remove(t.key); t.path=null; t.search=null; t.retry=tick+100; }
    public void cancel(String npc) { travels.remove(npc); }
    public void clear() { travels.clear(); cache.clear(); }
    public int searches() { return (int)travels.values().stream().filter(t -> t.search!=null).count(); }
    public int cachedRoutes() { return cache.size(); }
}
