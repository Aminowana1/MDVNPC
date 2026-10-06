package com.mdvcraft.mdvnpc.work;

import com.mdvcraft.mdvnpc.MdvNpcPlugin;
import com.mdvcraft.mdvnpc.model.NpcDefinition;
import com.mdvcraft.mdvnpc.routine.DoorController;
import com.mdvcraft.mdvnpc.routine.RoutineNavigator;
import com.mdvcraft.mdvnpc.routine.RoutineTerrain;
import com.mdvcraft.mdvnpc.runtime.ActiveNpc;
import io.papermc.paper.entity.TeleportFlag;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.boat.OakBoat;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.random.RandomGenerator;

/** Shop fishing choreography. Land movement stays in Paper; boats use a bounded water-only route. */
public final class FishermanController {
    public enum Result { RUNNING, FALLBACK }
    private enum Stage { SHORE_WALK, SHORE_FISH, DOCK_WALK, BOAT_OUT, BOAT_FISH, BOAT_BACK }
    interface BobberVisual {
        boolean move(Location location);
        void remove();
    }
    @FunctionalInterface interface BobberFactory {
        BobberVisual spawn(ActiveNpc npc, Location start);
    }
    private static final Vector STILL=new Vector();
    private static final int CAST_TICKS=16, RECAST_TICKS=300, NO_PROGRESS_TICKS=160;
    private final MdvNpcPlugin plugin;
    private final RoutineNavigator navigator;
    private final DoorController doors;
    private final BiPredicate<ActiveNpc,Location> teleport;
    private final BiPredicate<ActiveNpc,Entity> mount;
    private final Function<Material,ItemStack> items;
    private final RandomGenerator random;
    private final BobberFactory bobbers;
    private final Map<String,State> states=new HashMap<>();

    private static final class State {
        ActiveNpc npc;
        FishingDefinition definition;
        Location workPost,target,dock,dockWater,boatPoint,boatPosition,progress,castStart,castEnd;
        List<Location> shoreOptions=List.of();
        List<Location> boatRoute=List.of();
        int shoreIndex,routeIndex;
        Stage stage=Stage.SHORE_WALK;
        Boat boat;
        BobberVisual bobber;
        long travelStarted,lastProgress,stageStarted,nextEffect,castStarted,nextCast,lastTick;
        long suspendedAt;
        boolean suspended;
        boolean handSaved,entityHandSaved,previousRaised,raisedApplied,dropChanceApplied,headApplied;
        ItemStack previousHand,previousEntityHand,appliedHand;
        float previousYaw,previousPitch,previousBodyYaw,previousDropChance,appliedYaw,appliedBodyYaw;
    }

    public FishermanController(MdvNpcPlugin plugin,RoutineNavigator navigator,
                               BiPredicate<ActiveNpc,Location> teleport,BiPredicate<ActiveNpc,Entity> mount) {
        // java.util.Random is in java.base and needs no optional algorithm provider.
        this(plugin,navigator,new DoorController(plugin),teleport,mount,ItemStack::new,new Random(),VanillaFishingBobber::new);
    }
    FishermanController(MdvNpcPlugin plugin,RoutineNavigator navigator,DoorController doors,
                        BiPredicate<ActiveNpc,Location> teleport,BiPredicate<ActiveNpc,Entity> mount,
                        Function<Material,ItemStack> items,RandomGenerator random) {
        this(plugin,navigator,doors,teleport,mount,items,random,VanillaFishingBobber::new);
    }
    FishermanController(MdvNpcPlugin plugin,RoutineNavigator navigator,DoorController doors,
                        BiPredicate<ActiveNpc,Location> teleport,BiPredicate<ActiveNpc,Entity> mount,
                        Function<Material,ItemStack> items,RandomGenerator random,BobberFactory bobbers) {
        this.plugin=plugin;this.navigator=navigator;this.doors=doors;this.teleport=teleport;
        this.mount=mount;this.items=items;this.random=random;this.bobbers=bobbers;
    }
    public boolean active(String id) {return states.containsKey(id);}
    public boolean walking(String id) {State state=states.get(id);return state!=null && walking(state.stage);}
    public boolean boating(String id) {State state=states.get(id);return state!=null && state.boat!=null;}
    /** A merchant session pauses at the current location so purchase distance checks remain valid. */
    public boolean pause(String id) {
        State state=states.get(id);if(state==null)return true;
        try {
            if(!state.npc.entity().isValid() || state.npc.entity().isDead() || !BoatNavigator.loaded(state.npc.position()))return false;
            navigator.cancel(id);removeBobber(state);
            if(state.boat!=null && (!state.boat.isValid() || state.npc.entity().getVehicle()!=state.boat
                    || !state.boat.getPassengers().contains(state.npc.entity()) || !holdBoat(state)))return false;
            hand(state,Material.AIR);return true;
        } catch(RuntimeException | LinkageError error) {return false;}
    }
    /** Reactions borrow the hand/head but keep this boat, passenger and work stage. */
    public boolean suspend(String id,long tick) {
        State state=states.get(id);if(state==null || state.boat==null)return false;
        try {
            if(!ownedPassenger(state) || !holdBoat(state))return false;
            if(!state.suspended) {
                state.suspended=true;state.suspendedAt=tick;
                navigator.cancel(id);removeBobber(state);hand(state,Material.AIR);
            }
            return true;
        } catch(RuntimeException | LinkageError error) {return false;}
    }
    public boolean resume(String id,long tick) {
        State state=states.get(id);if(state==null || state.boat==null)return false;
        try {
            if(!ownedPassenger(state) || !holdBoat(state))return false;
            if(state.suspended) {
                long elapsed=Math.max(0,tick-state.suspendedAt);
                state.stageStarted+=elapsed;state.travelStarted+=elapsed;state.lastProgress+=elapsed;
                state.lastTick=state.nextCast=state.nextEffect=tick;state.suspended=false;
                hand(state,state.stage==Stage.BOAT_FISH?Material.FISHING_ROD:Material.AIR);
            }
            return true;
        } catch(RuntimeException | LinkageError error) {return false;}
    }
    private static boolean ownedPassenger(State state) {
        return state.npc.entity().isValid() && !state.npc.entity().isDead() && state.boat!=null && state.boat.isValid()
                && !state.boat.isDead() && state.npc.entity().getVehicle()==state.boat
                && state.boat.getPassengers().contains(state.npc.entity());
    }
    public boolean ownsBoat(Entity boat) {return boat!=null && states.values().stream().anyMatch(state->state.boat==boat);}
    public ActiveNpc mountedNpc(Entity boat) {
        if(boat==null)return null;
        for(State state:states.values())if(state.boat==boat)return state.npc;
        return null;
    }
    public boolean travelExpired(String id,long tick) {
        State state=states.get(id);
        return state!=null && !state.suspended && traveling(state.stage)
                && tick-state.travelStarted>=seconds("travel-timeout-seconds",60,3,600)*20L;
    }
    public String status(String id) {
        State state=states.get(id);if(state==null)return "";
        return switch(state.stage) {
            case SHORE_WALK -> "caminando al punto de pesca";
            case SHORE_FISH -> "pescando en la orilla";
            case DOCK_WALK -> "caminando al muelle";
            case BOAT_OUT -> "navegando al punto de pesca";
            case BOAT_FISH -> "pescando en bote";
            case BOAT_BACK -> "regresando al muelle";
        };
    }
    private static boolean walking(Stage stage) {return stage==Stage.SHORE_WALK || stage==Stage.DOCK_WALK;}
    private static boolean traveling(Stage stage) {return walking(stage) || stage==Stage.BOAT_OUT || stage==Stage.BOAT_BACK;}

    public Result tick(ActiveNpc npc,Location workPost,double speed,long tick,int cadence) {
        String id=npc.definition().id();State state=states.get(id);
        try {
            var work=npc.definition().shopWork();
            if(!npc.definition().enabled() || npc.definition().mode()!=NpcDefinition.Mode.SHOP
                    || !npc.entity().isValid() || npc.entity().isDead() || npc.disguise()==null
                    || work==null || work.category()!=ShopWorkDefinition.Category.FISHERMAN
                    || !work.fishing().complete() || !BoatNavigator.loaded(workPost)
                    || workPost.getWorld()!=npc.entity().getWorld())return fallback(id);
            if(state!=null && (state.npc!=npc || !state.definition.equals(work.fishing())
                    || state.workPost.getWorld()!=workPost.getWorld() || state.workPost.distanceSquared(workPost)>1e-8)) {
                stop(id);state=null;
            }
            if(state==null) {
                if(!arrived(npc.position(),workPost) || npc.entity().isInsideVehicle())return Result.FALLBACK;
                state=new State();state.npc=npc;state.definition=work.fishing();state.workPost=workPost.clone();
                state.previousYaw=npc.position().getYaw();state.previousPitch=npc.position().getPitch();
                state.previousBodyYaw=npc.entity().getBodyYaw();state.lastTick=tick;
                states.put(id,state);saveHand(state);
                if(!prepareDock(state) || !beginShore(state,tick))return fallback(id);
            }
            if(travelExpired(id,tick) || !BoatNavigator.loaded(npc.position()))return fallback(id);
            if(state.boat!=null && (!state.boat.isValid() || state.boat.isDead()
                    || npc.entity().getVehicle()!=state.boat || !state.boat.getPassengers().contains(npc.entity())))return fallback(id);
            if(state.boat==null && npc.entity().isInsideVehicle())return fallback(id);
            if(state.suspended)return holdBoat(state)?Result.RUNNING:fallback(id);
            if(walking(state.stage))return walk(state,speed,tick,cadence);
            if(state.stage==Stage.BOAT_OUT || state.stage==Stage.BOAT_BACK)return sail(state,tick,cadence);
            if(state.stage==Stage.SHORE_FISH) {
                if(!arrived(npc.position(),state.target) || !supported(npc.position()))return fallback(id);
            } else if(!holdBoat(state))return fallback(id);
            fish(state,tick);
            long duration=seconds(state.stage==Stage.SHORE_FISH?"shore-seconds":"boat-seconds",45,1,3600)*20L;
            if(tick-state.stageStarted>=duration) {
                removeBobber(state);
                if(state.stage==Stage.SHORE_FISH) {
                    if(!prepareDock(state))return fallback(id);
                    beginWalk(state,Stage.DOCK_WALK,state.dock,tick);
                } else if(!beginBoatRoute(state,Stage.BOAT_BACK,state.dockWater,tick))return fallback(id);
            }
            return Result.RUNNING;
        } catch(RuntimeException | LinkageError error) {
            stop(id);return Result.FALLBACK;
        }
    }
    private boolean prepareDock(State state) {
        state.dock=landLocation(state,state.definition.dock());
        if(state.dock==null)return false;
        Vector direction=horizontal(state.dock.getYaw());BoatNavigator water=new BoatNavigator(state.dock.getWorld());
        state.dockWater=null;
        for(int distance=2;distance<=4;distance++) {
            Location candidate=water.position(state.dock.clone().add(direction.clone().multiply(distance)));
            if(candidate!=null) {state.dockWater=candidate;break;}
        }
        return state.dockWater!=null;
    }
    private Location landLocation(State state,FishingDefinition.Point point) {
        if(point==null)return null;
        Location requested=point.location(state.npc.entity().getWorld());
        if(!BoatNavigator.loaded(requested))return null;
        RoutineTerrain terrain=new RoutineTerrain(requested.getWorld(),doors);
        try(var update=terrain.beginUpdate()) {
            var node=terrain.near(requested);if(node==null)return null;
            Location ground=terrain.location(node);ground.setYaw(requested.getYaw());
            return Math.abs(ground.getY()-requested.getY())<=1.01?ground:null;
        }
    }
    private boolean beginShore(State state,long tick) {
        ArrayList<Location> options=new ArrayList<>();
        for(var point:state.definition.shorePoints()) {
            Location location=landLocation(state,point);
            if(location!=null && castTarget(location)!=null)options.add(location);
        }
        shuffle(options);state.shoreOptions=List.copyOf(options);state.shoreIndex=0;
        if(options.isEmpty())return false;
        beginWalk(state,Stage.SHORE_WALK,options.getFirst(),tick);return true;
    }
    private void shuffle(List<?> values) {
        for(int i=values.size()-1;i>0;i--)Collections.swap(values,i,random.nextInt(i+1));
    }
    private void beginWalk(State state,Stage stage,Location target,long tick) {
        navigator.cancel(state.npc.definition().id());removeBobber(state);
        state.stage=stage;state.target=target.clone();state.travelStarted=state.lastProgress=tick;
        state.progress=state.npc.position().clone();state.lastTick=tick;
    }
    private Result walk(State state,double speed,long tick,int cadence) {
        if(!BoatNavigator.loaded(state.target))return fallback(state.npc.definition().id());
        Location current=state.npc.position();
        if(horizontalSquared(current,state.progress)>=.01) {state.progress=current.clone();state.lastProgress=tick;}
        if(tick-state.lastProgress>=NO_PROGRESS_TICKS) {
            if(state.stage!=Stage.SHORE_WALK || ++state.shoreIndex>=state.shoreOptions.size())return fallback(state.npc.definition().id());
            state.target=state.shoreOptions.get(state.shoreIndex);state.progress=current.clone();state.lastProgress=tick;
            navigator.cancel(state.npc.definition().id());
        }
        var result=navigator.move(state.npc,state.target,speed,tick,cadence);
        if(result!=RoutineNavigator.Result.ARRIVED || !arrived(state.npc.position(),state.target))return Result.RUNNING;
        if(!supported(state.npc.position()))return fallback(state.npc.definition().id());
        navigator.cancel(state.npc.definition().id());
        if(state.stage==Stage.SHORE_WALK) {
            if(!beginFishing(state,Stage.SHORE_FISH,tick))return fallback(state.npc.definition().id());
        } else if(!launch(state,tick))return fallback(state.npc.definition().id());
        return Result.RUNNING;
    }
    private boolean launch(State state,long tick) {
        if(!prepareDock(state))return false;
        BoatNavigator water=new BoatNavigator(state.dock.getWorld());ArrayList<Location> candidates=new ArrayList<>();
        for(var point:state.definition.boatPoints()) {
            Location requested=point.location(state.dock.getWorld());Location position=water.position(requested);
            if(position!=null && castTarget(position)!=null)candidates.add(position);
        }
        shuffle(candidates);List<Location> route=List.of();state.boatPoint=null;
        // Try a small fixed number per cycle instead of multiplying the bounded search by a large configured list.
        for(Location candidate:candidates.subList(0,Math.min(3,candidates.size()))) {
            route=water.route(state.dockWater,candidate);
            if(!route.isEmpty()) {state.boatPoint=candidate;break;}
        }
        if(state.boatPoint==null)return false;
        state.boatPosition=state.dockWater.clone();state.lastTick=tick;
        OakBoat boat=state.dock.getWorld().spawn(state.boatPosition,OakBoat.class,entity->{
            // Register ownership before any callbacks or mount events can observe the boat.
            state.boat=entity;entity.setPersistent(false);entity.setInvulnerable(true);
            entity.setGravity(false);entity.setSilent(true);entity.setVelocity(STILL.clone());
        });
        if(boat==null || !boat.isValid())return false;
        state.boat=boat;
        if(!mount.test(state.npc,boat) || state.npc.entity().getVehicle()!=boat)return false;
        state.stage=Stage.BOAT_OUT;state.boatRoute=route;state.routeIndex=0;
        state.travelStarted=tick;state.dock.getWorld().playSound(state.dock,Sound.ENTITY_BOAT_PADDLE_WATER,.35f,1);
        hand(state,Material.AIR);return true;
    }
    private boolean beginBoatRoute(State state,Stage stage,Location target,long tick) {
        removeBobber(state);BoatNavigator water=new BoatNavigator(state.boatPosition.getWorld());
        List<Location> route=water.route(state.boatPosition,target);if(route.isEmpty())return false;
        state.stage=stage;state.boatRoute=route;state.routeIndex=0;state.travelStarted=state.lastTick=tick;
        hand(state,Material.AIR);return true;
    }
    private Result sail(State state,long tick,int cadence) {
        BoatNavigator water=new BoatNavigator(state.boatPosition.getWorld());
        Location actual=actualBoatPosition(state,water);
        if(!water.valid(state.boatPosition) || actual==null || !water.segment(actual,state.boatPosition))
            return fallback(state.npc.definition().id());
        double elapsed=Math.max(1,Math.min(10,tick-state.lastTick));state.lastTick=tick;
        double remaining=decimal("boat-speed",3,.5,6)*elapsed/20;
        Location next=state.boatPosition.clone();
        while(remaining>1e-7 && state.routeIndex<state.boatRoute.size()) {
            Location waypoint=state.boatRoute.get(state.routeIndex);
            double distance=next.distance(waypoint);
            if(distance<=1e-7) {state.routeIndex++;continue;}
            double step=Math.min(remaining,distance);
            Location moved=next.clone().add(waypoint.toVector().subtract(next.toVector()).multiply(step/distance));
            if(!water.segment(next,moved))return fallback(state.npc.definition().id());
            Vector direction=moved.toVector().subtract(next.toVector());direction.setY(0);
            if(direction.lengthSquared()>1e-8)moved.setDirection(direction);
            next=moved;remaining-=step;
            if(step>=distance-1e-7)state.routeIndex++;
        }
        if(!water.segment(actual,next))return fallback(state.npc.definition().id());
        state.boat.setVelocity(STILL.clone());
        if(!state.boat.teleport(next,TeleportFlag.EntityState.RETAIN_PASSENGERS))return fallback(state.npc.definition().id());
        state.boatPosition=next;
        if(tick>=state.nextEffect) {
            state.nextEffect=tick+20;
            next.getWorld().playSound(next,Sound.ENTITY_BOAT_PADDLE_WATER,.3f,1);
            next.getWorld().spawnParticle(Particle.SPLASH,next.clone().add(0,.3,0),3,.3,.02,.3,.01);
        }
        if(state.routeIndex<state.boatRoute.size())return Result.RUNNING;
        if(state.stage==Stage.BOAT_OUT) {
            state.target=state.boatPoint;
            if(!beginFishing(state,Stage.BOAT_FISH,tick))return fallback(state.npc.definition().id());
        } else {
            if(!disembark(state) || !beginShore(state,tick))return fallback(state.npc.definition().id());
        }
        return Result.RUNNING;
    }
    private boolean holdBoat(State state) {
        BoatNavigator water=new BoatNavigator(state.boatPosition.getWorld());
        Location actual=actualBoatPosition(state,water);
        if(!water.valid(state.boatPosition) || actual==null || !water.segment(actual,state.boatPosition))return false;
        Location head=state.suspended?state.npc.position():null;
        float body=state.suspended?state.npc.entity().getBodyYaw():0;
        state.boat.setVelocity(STILL.clone());
        boolean moved=state.boat.teleport(state.boatPosition,TeleportFlag.EntityState.RETAIN_PASSENGERS);
        if(moved && head!=null) {
            // Retaining a native passenger may update its rotation. A reaction retains its own pose.
            state.npc.entity().setRotation(head.getYaw(),head.getPitch());state.npc.entity().setBodyYaw(body);
        }
        return moved;
    }
    private Location actualBoatPosition(State state,BoatNavigator water) {
        if(state.boat.getWorld()!=state.boatPosition.getWorld())return null;
        Location actual=state.boat.getLocation();
        if(!BoatNavigator.loaded(actual) || horizontalSquared(actual,state.boatPosition)>1
                || Math.abs(actual.getY()-state.boatPosition.getY())>1)return null;
        // Normalize ordinary native buoyancy, while retaining actual X/Z to validate any push along the bank.
        return water.position(actual);
    }
    private boolean disembark(State state) {
        Location dock=landLocation(state,state.definition.dock());
        if(dock==null || !supported(dock))return false;
        if(state.npc.entity().getVehicle()==state.boat && !mount.test(state.npc,null))return false;
        if(state.npc.entity().isInsideVehicle() || !teleport.test(state.npc,dock))return false;
        removeBoat(state);return true;
    }
    private boolean beginFishing(State state,Stage stage,long tick) {
        state.stage=stage;state.stageStarted=state.nextEffect=state.nextCast=tick;
        if(stage==Stage.BOAT_FISH) {
            // Native passengers are yaw-clamped relative to their boat. Align both with the saved fishing direction.
            state.boatPosition.setYaw(state.target.getYaw());
            if(!holdBoat(state))return false;
        }
        hand(state,Material.FISHING_ROD);face(state,state.target.getYaw());
        return castTarget(state.target)!=null;
    }
    private Location castTarget(Location point) {
        if(!BoatNavigator.loaded(point))return null;
        Vector direction=horizontal(point.getYaw()).multiply(decimal("cast-distance",4.5,2,8));
        return new BoatNavigator(point.getWorld()).waterTarget(point.getX()+direction.getX(),point.getZ()+direction.getZ(),point.getY());
    }
    private void fish(State state,long tick) {
        face(state,state.target.getYaw());
        if(tick>=state.nextCast) {
            removeBobber(state);state.castEnd=castTarget(state.target);
            if(state.castEnd==null)throw new IllegalStateException("El lance ya no termina en agua");
            state.castStart=state.npc.position().clone().add(horizontal(state.target.getYaw()).multiply(.35)).add(0,1.25,0);
            if(!new BoatNavigator(state.castStart.getWorld()).castClear(state.castStart,state.castEnd))
                throw new IllegalStateException("Hay un obstáculo en el lance");
            state.castStarted=tick;state.nextCast=tick+RECAST_TICKS;
            state.bobber=bobbers.spawn(state.npc,state.castStart);
            if(state.bobber==null)throw new IllegalStateException("No se pudo crear el anzuelo vanilla");
            state.npc.entity().swingMainHand();
            state.castStart.getWorld().playSound(state.castStart,Sound.ENTITY_FISHING_BOBBER_THROW,.35f,1);
        }
        if(state.bobber==null || !BoatNavigator.loaded(state.castEnd))
            throw new IllegalStateException("Anzuelo no disponible");
        Location liveTarget=castTarget(state.target);
        if(liveTarget==null || liveTarget.distanceSquared(state.castEnd)>.0009
                || (tick-state.castStarted<=CAST_TICKS || tick>=state.nextEffect)
                && !new BoatNavigator(state.castStart.getWorld()).castClear(state.castStart,state.castEnd))
            throw new IllegalStateException("El agua o el recorrido del lance dejó de estar disponible");
        double fraction=Math.min(1,Math.max(0,(tick-state.castStarted)/(double)CAST_TICKS));
        Location bobber=state.castStart.clone().add(state.castEnd.toVector().subtract(state.castStart.toVector()).multiply(fraction));
        bobber.add(0,4*.55*fraction*(1-fraction)+(fraction==1?.025*Math.sin(tick*.15):0),0);
        if(!BoatNavigator.loaded(bobber) || !state.bobber.move(bobber))throw new IllegalStateException("Lance interrumpido");
        if(tick>=state.nextEffect) {
            state.nextEffect=tick+10;
            if(fraction==1) {
                bobber.getWorld().spawnParticle(Particle.BUBBLE_POP,bobber,2,.08,.01,.08,0);
                if(tick%40<10)bobber.getWorld().playSound(bobber,Sound.ENTITY_FISHING_BOBBER_SPLASH,.2f,1.1f);
            }
        }
    }
    private boolean supported(Location location) {
        if(!BoatNavigator.loaded(location))return false;
        RoutineTerrain terrain=new RoutineTerrain(location.getWorld(),doors);
        try(var update=terrain.beginUpdate()) {
            var floor=terrain.fallColumn(location.getX(),location.getY(),location.getZ(),.02);
            return floor.safe() && Double.isFinite(floor.support()) && Math.abs(location.getY()-floor.support())<=.02;
        }
    }
    private int seconds(String key,int fallback,int minimum,int maximum) {
        var settings=plugin.settings();return Math.max(minimum,Math.min(maximum,
                settings==null?fallback:settings.messages().getInt("fisherman."+key,fallback)));
    }
    private double decimal(String key,double fallback,double minimum,double maximum) {
        var settings=plugin.settings();double value=settings==null?fallback:settings.messages().getDouble("fisherman."+key,fallback);
        return Double.isFinite(value)?Math.max(minimum,Math.min(maximum,value)):fallback;
    }
    private static Vector horizontal(float yaw) {
        double radians=Math.toRadians(yaw);return new Vector(-Math.sin(radians),0,Math.cos(radians));
    }
    private static double horizontalSquared(Location first,Location second) {
        double x=first.getX()-second.getX(),z=first.getZ()-second.getZ();return x*x+z*z;
    }
    private static boolean arrived(Location current,Location target) {
        return current!=null && target!=null && current.getWorld()==target.getWorld()
                && horizontalSquared(current,target)<=.16 && Math.abs(current.getY()-target.getY())<=.16;
    }
    private void saveHand(State state) {
        var watcher=state.npc.disguise().getWatcher();state.previousHand=copy(watcher.getItemInMainHand());
        state.previousRaised=watcher.isMainHandRaised();state.handSaved=true;
        EntityEquipment equipment=state.npc.entity().getEquipment();
        if(equipment!=null) {
            state.previousEntityHand=copy(equipment.getItemInMainHand());state.previousDropChance=equipment.getItemInMainHandDropChance();
            state.entityHandSaved=true;
        }
    }
    private void hand(State state,Material material) {
        ItemStack item=items.apply(material);state.appliedHand=item.clone();state.raisedApplied=true;
        var watcher=state.npc.disguise().getWatcher();watcher.setMainHandRaised(false);watcher.setItemInMainHand(item.clone());
        EntityEquipment equipment=state.npc.entity().getEquipment();
        if(equipment!=null) {
            state.dropChanceApplied=true;equipment.setItemInMainHandDropChance(0);equipment.setItemInMainHand(item.clone(),true);
        }
    }
    private static void face(State state,float yaw) {
        state.headApplied=true;state.appliedYaw=state.appliedBodyYaw=yaw;
        state.npc.entity().setRotation(yaw,0);state.npc.entity().setBodyYaw(yaw);
    }
    private static ItemStack copy(ItemStack item) {return item==null?null:item.clone();}
    private static boolean sameItem(ItemStack first,ItemStack second) {
        boolean a=first==null || first.getType().isAir(),b=second==null || second.getType().isAir();
        return a || b?a && b:first.equals(second);
    }
    private static boolean sameAngle(float first,float second) {
        return Math.abs(((first-second+540)%360+360)%360-180)<.001;
    }
    private static void removeBobber(State state) {
        if(state.bobber!=null)try{state.bobber.remove();}catch(RuntimeException | LinkageError ignored){}
        state.bobber=null;
    }
    private static void removeBoat(State state) {
        if(state.boat!=null)try{state.boat.remove();}catch(RuntimeException | LinkageError ignored){}
        state.boat=null;state.boatRoute=List.of();
    }
    private Result fallback(String id) {stop(id);return Result.FALLBACK;}
    public void stop(String id) {
        State state=states.get(id);if(state==null)return;
        try{navigator.cancel(id);}catch(RuntimeException | LinkageError ignored){}
        removeBobber(state);
        // Retain ownership through exit callbacks; interruptions return the passenger to the configured safe dock.
        try {
            if(state.boat!=null && state.npc.entity().getVehicle()==state.boat) {
                mount.test(state.npc,null);
                Location dock=landLocation(state,state.definition.dock());
                if(!state.npc.entity().isInsideVehicle() && state.npc.entity().isValid() && !state.npc.entity().isDead()
                        && dock!=null && supported(dock))teleport.test(state.npc,dock);
            }
        } catch(RuntimeException | LinkageError ignored) {}
        removeBoat(state);states.remove(id,state);
        try {
            if(state.handSaved && state.npc.disguise()!=null) {
                var watcher=state.npc.disguise().getWatcher();
                if(state.appliedHand!=null && sameItem(watcher.getItemInMainHand(),state.appliedHand))watcher.setItemInMainHand(copy(state.previousHand));
                if(state.raisedApplied && !watcher.isMainHandRaised())watcher.setMainHandRaised(state.previousRaised);
            }
        } catch(RuntimeException | LinkageError ignored) {}
        try {
            EntityEquipment equipment=state.npc.entity().getEquipment();
            if(state.entityHandSaved && equipment!=null) {
                if(state.appliedHand!=null && sameItem(equipment.getItemInMainHand(),state.appliedHand))equipment.setItemInMainHand(copy(state.previousEntityHand),true);
                if(state.dropChanceApplied && equipment.getItemInMainHandDropChance()==0)equipment.setItemInMainHandDropChance(state.previousDropChance);
            }
        } catch(RuntimeException | LinkageError ignored) {}
        try {
            if(state.headApplied && state.npc.entity().isValid()) {
                Location current=state.npc.position();
                if(sameAngle(current.getYaw(),state.appliedYaw) && Math.abs(current.getPitch())<.001)state.npc.entity().setRotation(state.previousYaw,state.previousPitch);
                if(sameAngle(state.npc.entity().getBodyYaw(),state.appliedBodyYaw))state.npc.entity().setBodyYaw(state.previousBodyYaw);
            }
        } catch(RuntimeException | LinkageError ignored) {}
    }
    public void clear() {for(String id:List.copyOf(states.keySet()))stop(id);}
}
