package com.mdvcraft.mdvnpc.routine;

import com.destroystokyo.paper.entity.Pathfinder;
import com.mdvcraft.mdvnpc.routine.BoundedPathfinder.Node;
import com.mdvcraft.mdvnpc.runtime.ActiveNpc;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.util.Vector;
import java.lang.reflect.Method;
import java.util.*;
import java.util.function.BiPredicate;

/**
 * Paper calculates the route; short controlled steps replay its waypoints while the villager
 * brain remains disabled. Searches widen only when a partial route stops making useful progress.
 */
public final class RoutineNavigator {
    public enum Result { MOVING, ARRIVED, WAITING }
    private enum SearchFailure { NONE, NO_ROUTE, CHUNK }
    private static final int[] PAPER_SEARCH_RANGES = {16, 24, 32, 48, 64};
    private static final int MAX_PATH_POINTS = 2048;
    private static final int LOCAL_RETRY_TICKS = 4, PARTIAL_RETRY_TICKS = 4,
            CHUNK_RETRY_TICKS = 20, IMPOSSIBLE_RETRY_TICKS = 100;
    private static final int STUCK_TICKS = 36;
    private static final double PROGRESS_DISTANCE_SQUARED = .15 * .15;
    /** Partial-height floors (dirt path, mud, carpet, snow layers) may differ from Paper's
     * raw waypoint Y by a few centimetres. Never require exact 3D coincidence to advance. */
    private static final double WAYPOINT_HORIZONTAL_SQUARED = .11 * .11;
    private static final double WAYPOINT_VERTICAL_TOLERANCE = .20;
    private static final double GROUND_EPSILON = .015;
    private static final double MAX_STEP_HEIGHT = 1.01;
    private static final double FORWARD_RISER_PROBE = .30;
    /** If a partial route finishes almost below/above the destination, following it only
     * makes the NPC hug the wall/floor. Widen first so Paper can discover stairs/ramps/doors. */
    private static final double VERTICAL_SHADOW_HORIZONTAL_SQUARED = 4.5 * 4.5;
    private static final double VERTICAL_SHADOW_MIN_Y = .75;
    private static final double MULTI_FLOOR_START_HORIZONTAL_SQUARED = 8.0 * 8.0;
    private static final double MULTI_FLOOR_START_MIN_Y = 1.25;
    private final DoorController doors;
    private final BiPredicate<ActiveNpc, Location> teleport;
    private final Map<String, Travel> travels = new LinkedHashMap<>();
    private long budgetTick = Long.MIN_VALUE, spentNanos;
    private int maximumStarts = 2, startsLeft = 2;
    private long maximumNanos = 2_000_000;

    private record PathPlan(List<Location> points, boolean reachesFinal, Location endpoint) {}
    private record SearchOutcome(PathPlan plan, SearchFailure failure) {}

    private static final class Travel {
        final ActiveNpc npc;
        final RoutineTerrain terrain;
        final Location destination;
        List<Location> path;
        int index;
        long retry;
        boolean pending = true;
        boolean pathReachesFinal;
        int searchLevel;
        int minimumSearchLevel;
        int maxSearchLevel = 2; // 32 blocks for ordinary same-floor movement.
        int localFailures;
        boolean exhausted;
        Location lastPartialEndpoint;
        Location lastProgressPosition;
        long lastProgressTick;
        Travel(ActiveNpc npc, Location destination, DoorController doors, Location start, long tick) {
            this.npc = npc; this.destination = destination.clone();
            terrain = new RoutineTerrain(destination.getWorld(), doors);
            // A destination on another floor often requires walking away from its X/Z first
            // to reach stairs or the only doorway. Start those searches wider and never shrink
            // them back to the cheap 16-block radius during the same trip.
            if (multiFloor(start, destination)) {
                maxSearchLevel = PAPER_SEARCH_RANGES.length - 1;
                searchLevel = Math.min(2, maxSearchLevel);
                minimumSearchLevel = searchLevel;
            }
            lastProgressPosition = start.clone(); lastProgressTick = tick;
        }
    }

    public RoutineNavigator(DoorController doors, BiPredicate<ActiveNpc, Location> teleport) {
        this.doors = doors; this.teleport = teleport;
    }
    /** Compatibility with older integrations; the former A* limits and route cache are unused. */
    public RoutineNavigator(DoorController doors, BiPredicate<ActiveNpc, Location> teleport, int maxNodes, int cacheSize) {
        this(doors, teleport);
    }
    /** A single shared allowance spreads simultaneous departures over successive updates. */
    public void beginTick(long tick, int maximumStarts, long maximumNanos) {
        if (budgetTick == tick) return;
        budgetTick = tick;
        this.maximumStarts = Math.max(1, Math.min(8, maximumStarts));
        this.maximumNanos = Math.max(1, maximumNanos);
        startsLeft = this.maximumStarts; spentNanos = 0;
    }
    /** No background A* search remains. Kept for source compatibility only. */
    public void searchBudget(int nodes, long nanos) {}

    public Result move(ActiveNpc npc, Location destination, double speed, long tick, int cadence) {
        String id = npc.definition().id();
        Location current = npc.entity().getLocation();
        if (destination.getWorld() == null || current.getWorld() != destination.getWorld()) {
            cancel(id); return Result.WAITING;
        }
        if (current.distanceSquared(destination) < .025) { cancel(id); return Result.ARRIVED; }
        if (budgetTick != tick) beginTick(tick, maximumStarts, maximumNanos);
        Travel travel = travels.get(id);
        if (travel == null || travel.npc != npc || travel.destination.getWorld() != destination.getWorld()
                || travel.destination.distanceSquared(destination) > .01) {
            cancel(id); travel = new Travel(npc, destination, doors, current, tick); travels.put(id, travel);
        }
        observeProgress(travel,current,tick);
        if (multiFloor(current, travel.destination)) {
            travel.maxSearchLevel = PAPER_SEARCH_RANGES.length - 1;
            int floorLevel = Math.min(2, travel.maxSearchLevel);
            travel.minimumSearchLevel = Math.max(travel.minimumSearchLevel, floorLevel);
            travel.searchLevel = Math.max(travel.searchLevel, travel.minimumSearchLevel);
        }
        if (travel.path != null && tick-travel.lastProgressTick >= STUCK_TICKS) {
            localFailure(travel,tick); return Result.WAITING;
        }
        if (travel.path == null) {
            if (tick < travel.retry) return Result.WAITING;
            if (!travel.terrain.loaded(current.getBlockX(), current.getBlockZ())
                    || !travel.terrain.loaded(destination.getBlockX(), destination.getBlockZ())) {
                chunkFailure(travel, tick); return Result.WAITING;
            }
            if (startsLeft <= 0 || spentNanos >= maximumNanos) return Result.WAITING;
            startsLeft--;
            long started = System.nanoTime();
            try {
                Location start = prepareStart(travel, current, tick, speed, cadence);
                if (start == null) { localFailure(travel, tick); return Result.WAITING; }
                // Settling onto a nearby verified floor follows the same speed limit as walking.
                if (current.distanceSquared(start) > .0001) return Result.MOVING;
                current = start;
                SearchOutcome outcome = findPaperPath(travel, start);
                if (outcome.plan() == null) {
                    if (outcome.failure() == SearchFailure.CHUNK) chunkFailure(travel,tick);
                    else noRouteFailure(travel,tick);
                    return Result.WAITING;
                }
                PathPlan plan=outcome.plan();
                double endpointMovement=start.distanceSquared(plan.endpoint());
                if (!plan.reachesFinal() && endpointMovement < .04) {
                    noRouteFailure(travel,tick); return Result.WAITING;
                }
                // Do not walk into the vertical "shadow" of a bed/workstation on another floor.
                // A short-range partial path commonly ends against the wall directly below/above
                // the goal. Re-run Paper with a wider FOLLOW_RANGE until it can see the actual
                // stairs/ramp/door detour. At maximum range, reject the dead-end instead of
                // oscillating a couple of blocks forever.
                if (!plan.reachesFinal() && verticalShadow(plan.endpoint(), travel.destination)) {
                    travel.maxSearchLevel = PAPER_SEARCH_RANGES.length - 1;
                    if (travel.searchLevel < travel.maxSearchLevel) {
                        travel.searchLevel++;
                        travel.minimumSearchLevel = Math.max(travel.minimumSearchLevel, travel.searchLevel);
                        travel.lastPartialEndpoint = plan.endpoint().clone();
                        travel.path = null; travel.pending = true; travel.exhausted = false;
                        travel.retry = tick + LOCAL_RETRY_TICKS;
                    } else {
                        noRouteFailure(travel, tick);
                    }
                    return Result.WAITING;
                }
                travel.path = plan.points(); travel.pathReachesFinal=plan.reachesFinal();
                travel.index = 0; travel.pending = false; travel.exhausted=false;
                tuneNextSearch(travel,start,plan);
            } finally {
                spentNanos += System.nanoTime() - started;
            }
        }
        while (travel.index < travel.path.size()
                && reachedWaypoint(current, travel.path.get(travel.index))) travel.index++;
        if (travel.index >= travel.path.size()) {
            // A partial path is a valid segment of a long trip. Re-query from its endpoint;
            // the search level may already have widened if that segment represented a detour.
            travel.path = null; travel.pending = true; travel.retry = tick + PARTIAL_RETRY_TICKS;
            return Result.WAITING;
        }
        Location next = travel.path.get(travel.index);
        Node nextFloor = new Node(next.getBlockX(), (int)Math.ceil(next.getY() - .02), next.getBlockZ());
        if (!travel.terrain.stand(nextFloor) || Math.abs(travel.terrain.height(nextFloor) - next.getY()) > .08) {
            localFailure(travel, tick); return Result.WAITING;
        }
        double distance = Math.max(.01, speed) * Math.max(1, cadence) / 20.0;
        Location step = walkingStep(travel,current,next,distance,tick);
        if(step==null) {localFailure(travel,tick);return Result.WAITING;}
        Vector delta = step.toVector().subtract(current.toVector());
        if (Math.abs(delta.getX()) + Math.abs(delta.getZ()) > .001)
            step.setDirection(new Vector(delta.getX(), 0, delta.getZ()));
        if (!teleport.test(npc, step)) { localFailure(travel, tick); return Result.WAITING; }
        return Result.MOVING;
    }

    private void observeProgress(Travel travel,Location current,long tick) {
        if(travel.lastProgressPosition==null) {
            travel.lastProgressPosition=current.clone();travel.lastProgressTick=tick;return;
        }
        if(current.distanceSquared(travel.lastProgressPosition)>=PROGRESS_DISTANCE_SQUARED) {
            travel.lastProgressPosition=current.clone();travel.lastProgressTick=tick;
            travel.localFailures=0;travel.exhausted=false;
        }
    }

    /** Approach a riser from the last clear position. Descents happen only after the body
     * clears the upper ledge. A diagonal corner that is too tight gets a short X/Z slide
     * before the whole Paper route is discarded. */
    private Location walkingStep(Travel travel,Location current,Location next,double allowance,long tick) {
        Location at=current.clone();
        int limit=(int)Math.ceil(allowance/.08)+20;
        for(int i=0;i<limit && allowance>1e-7;i++) {
            double dx=next.getX()-at.getX(),dz=next.getZ()-at.getZ(),horizontal=Math.hypot(dx,dz);
            Location candidate=null;
            if(horizontal<.0001) {
                double dy=next.getY()-at.getY();
                if(Math.abs(dy)<.0001)break;
                Location vertical=at.clone().add(0,Math.copySign(Math.min(Math.abs(dy),Math.min(.08,allowance)),dy),0);
                if(!walkable(travel,vertical,tick))return null;
                candidate=vertical;
            } else {
                double ux=dx/horizontal,uz=dz/horizontal;
                double support=travel.terrain.supportHeight(at.getX(),at.getY(),at.getZ());
                if(!Double.isFinite(support))return null;

                // Inspect the physical riser at the leading edge first. Paper's intermediate
                // waypoint may still have the lower Y on dirt paths/mud even though the 0.60-wide
                // body is already about to touch the next full block. A narrow directional probe
                // keeps side walls from being mistaken for stairs.
                double probe=FORWARD_RISER_PROBE;
                double forward=travel.terrain.supportHeight(at.getX()+ux*probe,at.getY(),at.getZ()+uz*probe,
                        MAX_STEP_HEIGHT,.065);
                if(Double.isFinite(forward) && forward>at.getY()+GROUND_EPSILON
                        && forward<=at.getY()+MAX_STEP_HEIGHT) {
                    Location up=at.clone().add(0,Math.min(forward-at.getY(),Math.min(.08,allowance)),0);
                    if(walkable(travel,up,tick))candidate=up;
                }

                // If there is no climb in progress, settle onto the real support below the feet
                // once the complete body has cleared the previous ledge. This deliberately does
                // not depend on next.getY(): Paper can already have advanced to a later waypoint
                // while the NPC is still floating 1/16 or 1/8 block above a partial-height floor.
                if(candidate==null && support<at.getY()-GROUND_EPSILON && at.getY()-support<=MAX_STEP_HEIGHT) {
                    Location down=at.clone().add(0,-Math.min(at.getY()-support,Math.min(.08,allowance)),0);
                    if(walkable(travel,down,tick))candidate=down;
                }

                if(candidate==null) {
                    double stride=Math.min(horizontal,Math.min(.08,allowance));
                    candidate=horizontalStep(travel,at,next,ux,uz,stride,tick);
                    if(candidate==null)return null;
                }
            }
            double moved=at.distance(candidate);
            if(moved<1e-8)break;
            allowance-=moved;at=candidate;
        }
        return at;
    }

    private Location horizontalStep(Travel travel,Location at,Location next,double ux,double uz,double stride,long tick) {
        Location direct=at.clone().add(ux*stride,0,uz*stride);
        if(walkable(travel,direct,tick) && supported(travel,direct))return direct;

        if(Math.abs(ux)<1e-6 || Math.abs(uz)<1e-6)return null;

        // Paper can legally return a diagonal around a block corner that our exact 0.60-wide
        // replay touches by a few centimetres. Slide along one axis and retry the diagonal next tick.
        ArrayList<Location> axes=new ArrayList<>(2);
        Location x=at.clone().add(Math.copySign(Math.min(Math.abs(next.getX()-at.getX()),stride),ux),0,0);
        Location z=at.clone().add(0,0,Math.copySign(Math.min(Math.abs(next.getZ()-at.getZ()),stride),uz));
        if(walkable(travel,x,tick) && supported(travel,x))axes.add(x);
        if(walkable(travel,z,tick) && supported(travel,z))axes.add(z);
        if(axes.isEmpty())return null;
        axes.sort(Comparator.comparingDouble(next::distanceSquared));
        return axes.getFirst();
    }


    private static boolean reachedWaypoint(Location current,Location waypoint) {
        double dx=current.getX()-waypoint.getX(),dz=current.getZ()-waypoint.getZ();
        return dx*dx+dz*dz<=WAYPOINT_HORIZONTAL_SQUARED
                && Math.abs(current.getY()-waypoint.getY())<=WAYPOINT_VERTICAL_TOLERANCE;
    }

    private boolean supported(Travel travel,Location at) {
        return Double.isFinite(travel.terrain.supportHeight(at.getX(),at.getY(),at.getZ()));
    }
    private boolean walkable(Travel travel,Location candidate,long tick) {
        return doors.openNear(travel.npc.entity(),candidate,tick)
                && travel.terrain.fits(candidate.getX(),candidate.getY(),candidate.getZ(),false);
    }

    private Location prepareStart(Travel travel, Location current, long tick, double speed, int cadence) {
        Node start = travel.terrain.near(current);
        if (start == null) return null;
        double contact=travel.terrain.supportHeight(current.getX(),current.getY(),current.getZ(),.02);
        if(!Double.isFinite(contact))return null;
        Location floor = current.clone(); floor.setY(contact);
        if (!doors.openNear(travel.npc.entity(), floor, tick)
                || !travel.terrain.fits(floor.getX(), floor.getY(), floor.getZ(), false)
                || Math.abs(floor.getY() - current.getY()) > 1.01) {
            return null;
        }
        double dy=floor.getY()-current.getY();
        if(Math.abs(dy)<=.01)return current;
        double distance=Math.max(.01,speed)*Math.max(1,cadence)/20.0;
        Location step=current.clone().add(0,Math.copySign(Math.min(Math.abs(dy),distance),dy),0);
        int samples=Math.max(1,(int)Math.ceil(Math.abs(step.getY()-current.getY())/.16));
        for(int i=1;i<=samples;i++) {
            double y=current.getY()+(step.getY()-current.getY())*i/samples;
            if(!travel.terrain.fits(current.getX(),y,current.getZ(),false))return null;
        }
        return teleport.test(travel.npc,step)?step:null;
    }

    private SearchOutcome findPaperPath(Travel travel, Location current) {
        Pathfinder pathfinder = travel.npc.entity().getPathfinder();
        pathfinder.setCanOpenDoors(true);
        pathfinder.setCanPassDoors(true);
        pathfinder.setCanFloat(false);
        AttributeInstance range = travel.npc.entity().getAttribute(Attribute.FOLLOW_RANGE);
        int requestedRange=PAPER_SEARCH_RANGES[Math.max(0,Math.min(PAPER_SEARCH_RANGES.length-1,travel.searchLevel))];
        double priorRange = range == null ? requestedRange : range.getBaseValue();
        Pathfinder.PathResult result;
        try {
            if (range != null) range.setBaseValue(requestedRange);
            // Paper's synchronous navigator works from currently available world data. Do not
            // reject the whole query merely because an unrelated neighboring chunk is unloaded.
            try (GroundFlag ignored = NativeGroundFlag.open(travel.npc.entity())) {
                result = pathfinder.findPath(travel.destination);
            }
        } finally {
            if (range != null) range.setBaseValue(priorRange);
        }
        if (result == null) return new SearchOutcome(null,SearchFailure.NO_ROUTE);
        List<Location> points = result.getPoints();
        if (points == null || points.isEmpty() || points.size() > MAX_PATH_POINTS)
            return new SearchOutcome(null,SearchFailure.NO_ROUTE);
        ArrayList<Location> path = new ArrayList<>(points.size() + 1);
        for (Location raw : points) {
            if (raw == null || raw.getWorld() != current.getWorld()) return new SearchOutcome(null,SearchFailure.NO_ROUTE);
            if (!travel.terrain.loaded(raw.getBlockX(), raw.getBlockZ())) return new SearchOutcome(null,SearchFailure.CHUNK);
            Location centered = new Location(current.getWorld(), raw.getBlockX() + .5, raw.getY(), raw.getBlockZ() + .5);
            Node point = travel.terrain.near(centered);
            if (point == null) return new SearchOutcome(null,SearchFailure.NO_ROUTE);
            Location waypoint = travel.terrain.location(point);
            if (path.isEmpty() || path.getLast().distanceSquared(waypoint) > .0001) path.add(waypoint);
        }
        if(path.isEmpty())return new SearchOutcome(null,SearchFailure.NO_ROUTE);
        Location last = path.getLast();
        boolean exactBlock=last.getBlockX() == travel.destination.getBlockX() && last.getBlockZ() == travel.destination.getBlockZ()
                && Math.abs(last.getY() - travel.destination.getY()) < .12;
        boolean reachesFinal=result.canReachFinalPoint() || exactBlock;
        if (exactBlock && last.distanceSquared(travel.destination) > .0001) {
            path.add(travel.destination.clone()); last=path.getLast();
        }
        return new SearchOutcome(new PathPlan(List.copyOf(path),reachesFinal,last.clone()),SearchFailure.NONE);
    }

    private void tuneNextSearch(Travel travel,Location start,PathPlan plan) {
        if(plan.reachesFinal()) {
            travel.lastPartialEndpoint=null;
            return;
        }
        double before=start.distanceSquared(travel.destination);
        double after=plan.endpoint().distanceSquared(travel.destination);
        boolean repeated=travel.lastPartialEndpoint!=null
                && travel.lastPartialEndpoint.distanceSquared(plan.endpoint())<.25;
        boolean detour=after>=before-.25;
        boolean floorMismatch=verticalShadow(plan.endpoint(),travel.destination);
        if(floorMismatch)travel.maxSearchLevel=PAPER_SEARCH_RANGES.length-1;
        if((repeated||detour||floorMismatch) && travel.searchLevel<travel.maxSearchLevel) {
            travel.searchLevel++;
            if(floorMismatch)travel.minimumSearchLevel=Math.max(travel.minimumSearchLevel,travel.searchLevel);
        } else if(!detour && !floorMismatch && before-after>16 && travel.searchLevel>travel.minimumSearchLevel) {
            travel.searchLevel--;
        }
        travel.lastPartialEndpoint=plan.endpoint().clone();
    }

    private static boolean multiFloor(Location from,Location destination) {
        if(from==null || destination==null || from.getWorld()!=destination.getWorld())return false;
        double dx=from.getX()-destination.getX(),dz=from.getZ()-destination.getZ();
        return dx*dx+dz*dz<=MULTI_FLOOR_START_HORIZONTAL_SQUARED
                && Math.abs(from.getY()-destination.getY())>=MULTI_FLOOR_START_MIN_Y;
    }

    private static boolean verticalShadow(Location endpoint,Location destination) {
        if(endpoint==null || destination==null || endpoint.getWorld()!=destination.getWorld())return false;
        double dx=endpoint.getX()-destination.getX(),dz=endpoint.getZ()-destination.getZ();
        return dx*dx+dz*dz<=VERTICAL_SHADOW_HORIZONTAL_SQUARED
                && Math.abs(endpoint.getY()-destination.getY())>=VERTICAL_SHADOW_MIN_Y;
    }

    private void noRouteFailure(Travel travel,long tick) {
        travel.path=null;travel.pending=true;travel.pathReachesFinal=false;
        if(travel.searchLevel<travel.maxSearchLevel) {
            travel.searchLevel++;travel.retry=tick+LOCAL_RETRY_TICKS;travel.exhausted=false;
        } else {
            travel.exhausted=true;travel.retry=tick+IMPOSSIBLE_RETRY_TICKS;
        }
    }
    private void localFailure(Travel travel,long tick) {
        travel.path=null;travel.pending=true;travel.pathReachesFinal=false;travel.localFailures++;
        if(travel.localFailures>=2 && travel.searchLevel<travel.maxSearchLevel) {
            travel.searchLevel++;travel.localFailures=0;
        } else if(travel.localFailures>=3 && travel.searchLevel>=travel.maxSearchLevel) {
            travel.exhausted=true;
        }
        travel.retry=tick+(travel.exhausted?20:LOCAL_RETRY_TICKS);
    }
    private void chunkFailure(Travel travel,long tick) {
        travel.path=null;travel.pending=true;travel.pathReachesFinal=false;
        travel.retry=tick+CHUNK_RETRY_TICKS;
    }

    public boolean exhausted(String npc) {
        Travel travel=travels.get(npc);return travel!=null && travel.exhausted;
    }
    public void cancel(String npc) { travels.remove(npc); }
    public void clear() {
        travels.clear(); budgetTick = Long.MIN_VALUE;
    }

    /**
     * Paper 1.21.6 uses Mojang names at runtime: CraftEntity.getHandle and Entity.setOnGround.
     * Reflection is resolved once per implementation class; it never enables AI or vanilla goals.
     */
    private static final class NativeGroundFlag {
        private static final ClassValue<Optional<Method>> HANDLES = methods("getHandle");
        private static final ClassValue<Optional<Method>> SETTERS = methods("setOnGround", boolean.class);
        private static ClassValue<Optional<Method>> methods(String name, Class<?>... arguments) {
            return new ClassValue<>() {
                @Override protected Optional<Method> computeValue(Class<?> type) {
                    try { return Optional.of(type.getMethod(name, arguments)); }
                    catch (NoSuchMethodException ex) { return Optional.empty(); }
                }
            };
        }
        static GroundFlag open(Entity entity) {
            if (entity.isOnGround()) return GroundFlag.UNCHANGED;
            Method getHandle = HANDLES.get(entity.getClass()).orElseThrow(() -> unsupported(entity.getClass()));
            Object handle = call(getHandle, entity);
            if (handle == null) throw unsupported(entity.getClass());
            Method setter = SETTERS.get(handle.getClass()).orElseThrow(() -> unsupported(handle.getClass()));
            call(setter, handle, true);
            return new GroundFlag(setter, handle);
        }
        private static IllegalStateException unsupported(Class<?> type) {
            return new IllegalStateException("No se pudo preparar la navegación Paper 1.21.6: " + type.getName()
                    + " no expone getHandle/setOnGround; la rutina se pausa para conservar la IA desactivada.");
        }
        static Object call(Method method, Object target, Object... arguments) {
            try { return method.invoke(target, arguments); }
            catch (ReflectiveOperationException ex) {
                throw new IllegalStateException("No se pudo ajustar/restaurar onGround durante la búsqueda Paper", ex);
            }
        }
    }
    private record GroundFlag(Method setter, Object handle) implements AutoCloseable {
        private static final GroundFlag UNCHANGED = new GroundFlag(null, null);
        @Override public void close() { if (setter != null) NativeGroundFlag.call(setter, handle, false); }
    }
    public int activeRoutes() { return (int)travels.values().stream().filter(t -> t.path != null).count(); }
    public int searches() { return (int)travels.values().stream().filter(t -> t.pending).count(); }
    public int cachedRoutes() { return 0; }
}
