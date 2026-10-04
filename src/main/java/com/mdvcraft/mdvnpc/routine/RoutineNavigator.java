package com.mdvcraft.mdvnpc.routine;

import com.destroystokyo.paper.entity.Pathfinder;
import com.mdvcraft.mdvnpc.routine.BoundedPathfinder.Node;
import com.mdvcraft.mdvnpc.runtime.ActiveNpc;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Door;
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
    private enum SearchFailure { NONE, NO_ROUTE, CHUNK, DENIED_DOOR }
    private static final int[] PAPER_SEARCH_RANGES = {16, 24, 32, 48, 64};
    private static final int MAX_PATH_POINTS = 2048;
    private static final int LOCAL_RETRY_TICKS = 4, PARTIAL_RETRY_TICKS = 4,
            CHUNK_RETRY_TICKS = 20, IMPOSSIBLE_RETRY_TICKS = 100;
    private static final int STUCK_TICKS = 36;
    private static final int PARTIAL_ENDPOINT_HISTORY = 12;
    private static final double PROGRESS_DISTANCE_SQUARED = .15 * .15;
    private static final double GOAL_PROGRESS_DISTANCE = .15;
    private static final double MOVEMENT_EPSILON_SQUARED = 1e-12;
    private static final double GROUND_EPSILON = .015;
    /** Waypoints are converted from Paper's integer cells to the real collision height.
     * Horizontal tolerance prevents tiny orbiting near the centre, while the vertical
     * tolerance stays tight so mud/soul-sand/path/carpet levels are actually visited. */
    private static final double WAYPOINT_HORIZONTAL_EPSILON = .15;
    private static final double WAYPOINT_VERTICAL_EPSILON = .02;
    private static final double MAX_STEP_HEIGHT = 1.01;
    /** Look slightly ahead of the 0.30-wide body so a low physical riser is raised onto
     * before collision, even when Paper compresses both route nodes to the same Y. */
    private static final double RISER_LOOKAHEAD = .42;
    private static final double RISER_SUPPORT_RADIUS = .065;
    /** When Paper keeps both nodes at the same Y, follow any real sub-block rise, but
     * never promote a complete one-block obstacle to a stair. The almost-point-sized
     * probe samples the route centreline and ignores a slab/block merely beside it. */
    private static final double MAX_UNANNOUNCED_RISER = .99;
    private static final double UNANNOUNCED_SUPPORT_RADIUS = .001;
    /** Approximate villager/player half-width. When a horizontal substep first collides,
     * sample just beyond the body's leading edge to discover the low surface causing it. */
    private static final double RISER_BODY_LEAD = .305;
    /** If a partial route finishes almost below/above the destination, following it only
     * makes the NPC hug the wall/floor. Widen first so Paper can discover stairs/ramps/doors. */
    private static final double VERTICAL_SHADOW_HORIZONTAL_SQUARED = 4.5 * 4.5;
    private static final double VERTICAL_SHADOW_MIN_Y = .75;
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
        final int maxSearchLevel = PAPER_SEARCH_RANGES.length - 1;
        int localFailures;
        boolean exhausted;
        boolean avoidClosedDoors;
        final Deque<Location> partialEndpoints = new ArrayDeque<>();
        double bestGoalDistance;
        double segmentStartBest;
        int segmentsWithoutGoalProgress;
        Location riserWaypoint;
        double riserHeight = Double.NaN;
        Location lastCommandPosition;
        Location lastProgressPosition;
        long lastProgressTick;
        Travel(ActiveNpc npc, Location destination, DoorController doors, Location start, long tick) {
            this.npc = npc; this.destination = destination.clone();
            terrain = new RoutineTerrain(destination.getWorld(), doors);
            // The only entrance or staircase can require a detour on any floor. Every trip
            // starts cheaply at 16, widening only after an unsuccessful or stagnant segment.
            bestGoalDistance = start.distance(destination);
            lastCommandPosition = start.clone();
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
        if (arrivedAt(current,destination)) { cancel(id); return Result.ARRIVED; }
        if (budgetTick != tick) beginTick(tick, maximumStarts, maximumNanos);
        Travel travel = travels.get(id);
        if (travel == null || travel.npc != npc || travel.destination.getWorld() != destination.getWorld()
                || travel.destination.distanceSquared(destination) > .01) {
            cancel(id); travel = new Travel(npc, destination, doors, current, tick); travels.put(id, travel);
        }
        try (RoutineTerrain.Update ignored = travel.terrain.beginUpdate()) {
            return advance(travel, current, speed, tick, cadence);
        }
    }

    private Result advance(Travel travel, Location current, double speed, long tick, int cadence) {
        ActiveNpc npc = travel.npc;
        Location destination = travel.destination;
        // An external teleport/push invalidates the route's starting assumptions. Re-query
        // from the new feet position instead of walking back through old waypoints.
        if (current.distanceSquared(travel.lastCommandPosition) > .25) {
            travel.path = null; travel.pending = true; travel.retry = tick;
            travel.partialEndpoints.clear(); travel.segmentsWithoutGoalProgress = 0;
            travel.searchLevel = 0; travel.minimumSearchLevel = 0;
            travel.avoidClosedDoors = false;
            travel.riserWaypoint = null; travel.riserHeight = Double.NaN;
            travel.bestGoalDistance = current.distance(destination);
            travel.lastCommandPosition = current.clone();
            travel.lastProgressPosition = current.clone(); travel.lastProgressTick = tick;
            travel.localFailures = 0; travel.exhausted = false;
        }
        observeProgress(travel,current,tick);
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
                if (current.distanceSquared(start) > MOVEMENT_EPSILON_SQUARED) return Result.MOVING;
                current = start;
                SearchOutcome outcome = findPaperPath(travel, start);
                if (outcome.plan() == null) {
                    if (outcome.failure() == SearchFailure.CHUNK) chunkFailure(travel,tick);
                    else if (outcome.failure() == SearchFailure.DENIED_DOOR && !travel.avoidClosedDoors)
                        avoidClosedDoors(travel,tick,cadence);
                    else noRouteFailure(travel,tick);
                    return Result.WAITING;
                }
                PathPlan plan=outcome.plan();
                double endpointMovement=start.distanceSquared(plan.endpoint());
                if (!plan.reachesFinal() && endpointMovement < .04) {
                    noRouteFailure(travel,tick); return Result.WAITING;
                }
                if (!plan.reachesFinal() && repeatsStagnantEndpoint(travel, plan.endpoint())) {
                    noRouteFailure(travel, tick);
                    travel.minimumSearchLevel = Math.max(travel.minimumSearchLevel, travel.searchLevel);
                    return Result.WAITING;
                }
                // Do not walk into the vertical "shadow" of a bed/workstation on another floor.
                // A short-range partial path commonly ends against the wall directly below/above
                // the goal. Re-run Paper with a wider FOLLOW_RANGE until it can see the actual
                // stairs/ramp/door detour. At maximum range, reject the dead-end instead of
                // oscillating a couple of blocks forever.
                if (!plan.reachesFinal() && verticalShadow(plan.endpoint(), travel.destination)
                        && !usefulFloorProgress(start,plan.endpoint(),travel.destination)) {
                    if (travel.searchLevel < travel.maxSearchLevel) {
                        travel.searchLevel++;
                        travel.minimumSearchLevel = Math.max(travel.minimumSearchLevel, travel.searchLevel);
                        travel.path = null; travel.pending = true; travel.exhausted = false;
                        travel.retry = tick + LOCAL_RETRY_TICKS;
                    } else {
                        noRouteFailure(travel, tick);
                    }
                    return Result.WAITING;
                }
                travel.path = plan.points(); travel.pathReachesFinal=plan.reachesFinal();
                travel.index = 0; travel.pending = false; travel.exhausted=false;
                travel.segmentStartBest = travel.bestGoalDistance;
                travel.lastProgressPosition = current.clone(); travel.lastProgressTick = tick;
                travel.riserWaypoint = null; travel.riserHeight = Double.NaN;
                tuneNextSearch(travel,start,plan);
            } finally {
                spentNanos += System.nanoTime() - started;
            }
        }
        while (travel.index < travel.path.size()
                && reachedWaypoint(travel,current,travel.path.get(travel.index))) travel.index++;
        if (travel.index >= travel.path.size()) {
            // A partial path is a valid segment of a long trip. Re-query from its endpoint;
            // the search level may already have widened if that segment represented a detour.
            if (!travel.pathReachesFinal) rememberPartialEndpoint(travel);
            travel.riserWaypoint = null; travel.riserHeight = Double.NaN;
            travel.path = null; travel.pending = true; travel.retry = tick + PARTIAL_RETRY_TICKS;
            return Result.WAITING;
        }
        Location next = travel.path.get(travel.index);
        Node nextFloor = new Node(next.getBlockX(), (int)Math.ceil(next.getY() - .02), next.getBlockZ());
        if (!travel.terrain.stand(nextFloor) || Math.abs(travel.terrain.height(nextFloor) - next.getY()) > .08) {
            if (!travel.avoidClosedDoors && deniedClosedDoor(travel,next)) avoidClosedDoors(travel,tick,cadence);
            else localFailure(travel, tick);
            return Result.WAITING;
        }
        double distance = Math.max(.01, speed) * Math.max(1, cadence) / 20.0;
        Location step = walkingStep(travel,current,next,distance,tick);
        if(step==null) step = recoverPartialRiser(travel,current,next,distance,tick);
        if(step==null) {localFailure(travel,tick);return Result.WAITING;}
        Vector delta = step.toVector().subtract(current.toVector());
        if (Math.abs(delta.getX()) + Math.abs(delta.getZ()) > .001)
            step.setDirection(new Vector(delta.getX(), 0, delta.getZ()));
        if (!applyStep(travel, current, step)) { localFailure(travel, tick); return Result.WAITING; }
        return Result.MOVING;
    }

    private void observeProgress(Travel travel,Location current,long tick) {
        double goalDistance = current.distance(travel.destination);
        if (goalDistance < travel.bestGoalDistance - GOAL_PROGRESS_DISTANCE) {
            travel.bestGoalDistance = goalDistance;
            travel.partialEndpoints.clear(); travel.segmentsWithoutGoalProgress = 0;
            travel.minimumSearchLevel = 0;
        }
        if(travel.lastProgressPosition==null) {
            travel.lastProgressPosition=current.clone();travel.lastProgressTick=tick;return;
        }
        if(current.distanceSquared(travel.lastProgressPosition)>=PROGRESS_DISTANCE_SQUARED) {
            travel.lastProgressPosition=current.clone();travel.lastProgressTick=tick;
            travel.localFailures=0;travel.exhausted=false;
        }
    }

    private boolean repeatsStagnantEndpoint(Travel travel, Location endpoint) {
        if (endpoint.distance(travel.destination) < travel.bestGoalDistance - GOAL_PROGRESS_DISTANCE) return false;
        for (Location prior : travel.partialEndpoints) if (prior.distanceSquared(endpoint) < .25) return true;
        return false;
    }

    private void rememberPartialEndpoint(Travel travel) {
        Location endpoint = travel.path.getLast();
        if (travel.bestGoalDistance < travel.segmentStartBest - GOAL_PROGRESS_DISTANCE)
            travel.segmentsWithoutGoalProgress = 0;
        else travel.segmentsWithoutGoalProgress++;
        if (travel.partialEndpoints.size() >= PARTIAL_ENDPOINT_HISTORY) travel.partialEndpoints.removeFirst();
        travel.partialEndpoints.addLast(endpoint.clone());
    }

    /** A successful teleport event can still leave the entity in place. Count actual motion. */
    private boolean applyStep(Travel travel, Location before, Location step) {
        if (before.distanceSquared(step) <= MOVEMENT_EPSILON_SQUARED || !teleport.test(travel.npc, step)) return false;
        Location actual = travel.npc.entity().getLocation();
        if (actual.getWorld() != before.getWorld()
                || actual.distanceSquared(before) <= MOVEMENT_EPSILON_SQUARED) return false;
        travel.lastCommandPosition = actual.clone();
        return true;
    }

    private static boolean arrivedAt(Location current,Location destination) {
        if(current.getWorld()!=destination.getWorld())return false;
        double dx=current.getX()-destination.getX(),dz=current.getZ()-destination.getZ();
        return dx*dx+dz*dz<=WAYPOINT_HORIZONTAL_EPSILON*WAYPOINT_HORIZONTAL_EPSILON
                && Math.abs(current.getY()-destination.getY())<=WAYPOINT_VERTICAL_EPSILON;
    }

    private static boolean reachedWaypoint(Travel travel,Location current,Location waypoint) {
        if(current.getWorld()!=waypoint.getWorld())return false;
        double dx=current.getX()-waypoint.getX(),dz=current.getZ()-waypoint.getZ();
        return dx*dx+dz*dz<=WAYPOINT_HORIZONTAL_EPSILON*WAYPOINT_HORIZONTAL_EPSILON
                && Math.abs(current.getY()-waypoint.getY())<=WAYPOINT_VERTICAL_EPSILON;
    }

    /** Approach a riser from the last clear position. Descents happen only after the body
     * clears the upper ledge. A diagonal corner that is too tight gets a short X/Z slide
     * before the whole Paper route is discarded. */
    private Location walkingStep(Travel travel,Location current,Location next,double allowance,long tick) {
        if (travel.riserWaypoint != next) {
            travel.riserWaypoint = next; travel.riserHeight = Double.NaN;
        }
        Location at=current.clone();
        int limit=(int)Math.ceil(allowance/.08)+20;
        for(int i=0;i<limit && allowance>1e-7;i++) {
            double dx=next.getX()-at.getX(),dz=next.getZ()-at.getZ(),horizontal=Math.hypot(dx,dz);
            Location candidate=null;
            if(horizontal<.0001) {
                // Never chase Paper's integer Y through empty air. At the waypoint centre,
                // follow the real collision support instead. This is what prevents 1/16-1/8
                // height blocks from producing a vertical oscillation/spin.
                double support=travel.terrain.supportHeight(at.getX(),at.getY(),at.getZ(),MAX_STEP_HEIGHT);
                if(!Double.isFinite(support))return null;
                double dy=support-at.getY();
                if(Math.abs(dy)<=WAYPOINT_VERTICAL_EPSILON)break;
                if(Math.abs(dy)>MAX_STEP_HEIGHT)return null;
                Location vertical=at.clone().add(0,Math.copySign(Math.min(Math.abs(dy),Math.min(.08,allowance)),dy),0);
                if(!walkable(travel,vertical,tick))return null;
                candidate=vertical;
            } else {
                double ux=dx/horizontal,uz=dz/horizontal;
                double support=travel.terrain.supportHeight(at.getX(),at.getY(),at.getZ());
                if(!Double.isFinite(support))return null;
                if (Double.isFinite(travel.riserHeight) && support >= travel.riserHeight - GROUND_EPSILON
                        && at.getY() >= travel.riserHeight - GROUND_EPSILON) travel.riserHeight = Double.NaN;

                // Follow Paper's vertical intent for real descents. Keep walking at the old
                // height until the complete body clears the previous ledge; only then lower.
                if(next.getY()<at.getY()-GROUND_EPSILON && support<at.getY()-GROUND_EPSILON) {
                    Location down=at.clone().add(0,-Math.min(at.getY()-Math.max(support,next.getY()),Math.min(.08,allowance)),0);
                    if(walkable(travel,down,tick))candidate=down;
                }

                if(candidate==null) {
                    // Detect the physical riser in front of the feet even when Paper keeps the
                    // same integer Y for consecutive waypoints. Partial-height surfaces are
                    // allowed without a higher native node; a complete block still requires
                    // Paper's vertical intent so obstacles beside/inside a flat route are not
                    // treated as stairs.
                    boolean paperRiser=next.getY()>at.getY()+GROUND_EPSILON;
                    double riseLimit=paperRiser?MAX_STEP_HEIGHT:MAX_UNANNOUNCED_RISER;
                    double radius=paperRiser?RISER_SUPPORT_RADIUS:UNANNOUNCED_SUPPORT_RADIUS;
                    double probe=Math.min(horizontal,RISER_LOOKAHEAD);
                    double contact=travel.terrain.supportHeight(at.getX()+ux*probe,at.getY(),at.getZ()+uz*probe,
                            riseLimit,radius);
                    if(Double.isFinite(contact) && contact>at.getY()+GROUND_EPSILON
                            && contact-at.getY()<=riseLimit+GROUND_EPSILON) {
                        Location up=at.clone().add(0,Math.min(contact-at.getY(),Math.min(.08,allowance)),0);
                        if(walkable(travel,up,tick)) {
                            candidate=up;
                            travel.riserHeight = contact;
                        }
                    }
                }

                // A dirt path/mud/carpet can leave the feet a few centimetres above the real
                // support after Paper advances to the next waypoint. Settle locally, but if the
                // old ledge still intersects the body, keep walking and retry after it is clear.
                // Once lifted for a verified riser, transfer horizontally onto it before
                // settling. The old lower floor otherwise undoes every ascent in this loop.
                boolean crossingRiser = Double.isFinite(travel.riserHeight)
                        && support < travel.riserHeight - GROUND_EPSILON;
                if(candidate==null && !crossingRiser && support<at.getY()-GROUND_EPSILON
                        && at.getY()-support<=MAX_STEP_HEIGHT) {
                    Location down=at.clone().add(0,-Math.min(at.getY()-support,Math.min(.08,allowance)),0);
                    if(walkable(travel,down,tick))candidate=down;
                }

                if(candidate==null) {
                    double stride=Math.min(horizontal,Math.min(.08,allowance));
                    candidate=horizontalStep(travel,at,next,ux,uz,stride,tick);
                    if(candidate==null) {
                        // A compressed Paper route can omit the intermediate node for a
                        // slab/carpet/other partial block. The first failed horizontal substep
                        // tells us exactly where the body meets that riser. Probe just beyond
                        // the leading edge, then raise while still beside the obstacle; once
                        // the feet reach its top, ordinary horizontal replay continues.
                        double lead=Math.min(horizontal,stride+RISER_BODY_LEAD);
                        double contact=travel.terrain.supportHeight(at.getX()+ux*lead,at.getY(),
                                at.getZ()+uz*lead,MAX_UNANNOUNCED_RISER,UNANNOUNCED_SUPPORT_RADIUS);
                        if(Double.isFinite(contact) && contact>at.getY()+GROUND_EPSILON
                                && contact-at.getY()<=MAX_UNANNOUNCED_RISER+GROUND_EPSILON) {
                            Location up=at.clone().add(0,Math.min(contact-at.getY(),Math.min(.08,allowance)),0);
                            if(walkable(travel,up,tick)) {
                                candidate=up;
                                travel.riserHeight=contact;
                            }
                        }
                    }
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


    /**
     * Last-resort recovery for a physical half-step that Paper omitted from its node list.
     * This is intentionally restricted to partial blocks (at most half a block above the
     * current feet), so a wall/full cube beside a flat route can never become a fake stair.
     * It is used only after the normal replay failed, and therefore prevents a valid
     * slab/carpet transition from being converted into WAITING -> replan -> circling.
     */
    private Location recoverPartialRiser(Travel travel,Location current,Location next,double allowance,long tick) {
        double dx=next.getX()-current.getX(),dz=next.getZ()-current.getZ();
        double horizontal=Math.hypot(dx,dz);
        if(horizontal<1e-7)return null;
        double ux=dx/horizontal,uz=dz/horizontal;

        // Probe from just beyond the leading edge through a small forward band. The first
        // collision can occur between Paper waypoints, especially for carpet/slab overlays.
        double[] probes={RISER_BODY_LEAD+.02,RISER_BODY_LEAD+.10,RISER_LOOKAHEAD,RISER_LOOKAHEAD+.10};
        double contact=Double.NaN;
        for(double requested:probes) {
            double ahead=Math.min(horizontal,requested);
            if(ahead<=1e-7)continue;
            double found=travel.terrain.supportHeight(current.getX()+ux*ahead,current.getY(),
                    current.getZ()+uz*ahead,.51,UNANNOUNCED_SUPPORT_RADIUS);
            if(Double.isFinite(found) && found>current.getY()+1e-4 && found-current.getY()<=.51+GROUND_EPSILON) {
                contact=found; break;
            }
        }
        if(!Double.isFinite(contact))return null;

        double climb=Math.min(contact-current.getY(),Math.max(.01,allowance));
        Location raised=current.clone().add(0,climb,0);
        if(!walkable(travel,raised,tick))return null;
        travel.riserWaypoint=next;
        travel.riserHeight=contact;
        return raised;
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
        Location floor = current.clone();
        if(Double.isFinite(contact))floor.setY(contact);
        boolean validContact=Double.isFinite(contact)
                && Math.abs(contact-current.getY())<=1.01
                && doors.openNear(travel.npc.entity(),floor,tick)
                && travel.terrain.fits(floor.getX(),floor.getY(),floor.getZ(),false);
        if(!validContact) {
            // If a carpet/bottom slab/thin layer was placed under an existing NPC, the old
            // feet Y can sit a few pixels inside its new collision. A normal vertical sweep
            // cannot leave an already-overlapping shape. Recover only to a verified partial
            // support and only by at most half a block; full cubes remain deliberately blocked.
            double shallow=travel.terrain.height(start), lift=shallow-current.getY();
            if(lift>GROUND_EPSILON && lift<=.51 && travel.terrain.partialSupport(start)) {
                Location recovered=current.clone();recovered.setY(shallow);
                if(doors.openNear(travel.npc.entity(),recovered,tick)
                        && travel.terrain.fits(recovered.getX(),recovered.getY(),recovered.getZ(),false))
                    return applyStep(travel,current,recovered)?travel.npc.entity().getLocation():null;
            }
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
        return applyStep(travel,current,step)?travel.npc.entity().getLocation():null;
    }

    private SearchOutcome findPaperPath(Travel travel, Location current) {
        Pathfinder pathfinder = travel.npc.entity().getPathfinder();
        pathfinder.setCanOpenDoors(!travel.avoidClosedDoors);
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
            // The replay destination uses the exact physical feet height (for example
            // 63.875 on soul sand), but Paper pathfinding targets integer path cells. Feeding
            // the physical Y directly makes floor(Y) point at the slab/carpet/mud block itself.
            // Resolve the destination back to its walkable node before asking Paper for a path.
            Node destinationNode=travel.terrain.near(travel.destination);
            Location nativeDestination=travel.destination.clone();
            if(destinationNode!=null) {
                nativeDestination=new Location(travel.destination.getWorld(),destinationNode.x()+.5,
                        destinationNode.y(),destinationNode.z()+.5,
                        travel.destination.getYaw(),travel.destination.getPitch());
            }
            try (GroundFlag ignored = NativeGroundFlag.open(travel.npc.entity())) {
                result = pathfinder.findPath(nativeDestination);
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
            if (point == null) return new SearchOutcome(null,deniedClosedDoor(travel,centered)
                    ? SearchFailure.DENIED_DOOR : SearchFailure.NO_ROUTE);
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

    /** Only a denied closed panel changes Paper's door policy; other bad terrain does not. */
    private boolean deniedClosedDoor(Travel travel, Location feet) {
        boolean denied = false;
        for (int x=(int)Math.floor(feet.getX()-.30);x<=(int)Math.floor(feet.getX()+.30);x++)
            for (int z=(int)Math.floor(feet.getZ()-.30);z<=(int)Math.floor(feet.getZ()+.30);z++)
                for (int y=(int)Math.floor(feet.getY()+.015);y<=(int)Math.floor(feet.getY()+1.95);y++) {
                    Block block=travel.terrain.block(x,y,z);
                    if(block==null)return false;
                    if(block.getBlockData() instanceof Door door && !door.isOpen() && !doors.canOpen(block)) denied=true;
                }
        return denied;
    }

    private void avoidClosedDoors(Travel travel,long tick,int cadence) {
        travel.path=null;travel.pending=true;travel.pathReachesFinal=false;
        travel.avoidClosedDoors=true;travel.exhausted=false;
        // The alternate policy gets its own shared-budget query on a later update.
        travel.retry=tick+Math.max(1,cadence);
    }

    private void tuneNextSearch(Travel travel,Location start,PathPlan plan) {
        if(plan.reachesFinal()) {
            travel.partialEndpoints.clear(); travel.segmentsWithoutGoalProgress = 0;
            return;
        }
        double before=start.distanceSquared(travel.destination);
        double after=plan.endpoint().distanceSquared(travel.destination);
        boolean detour=after>=before-.25;
        boolean floorMismatch=verticalShadow(plan.endpoint(),travel.destination)
                && !usefulFloorProgress(start,plan.endpoint(),travel.destination);
        if((detour||floorMismatch||travel.segmentsWithoutGoalProgress>=2) && travel.searchLevel<travel.maxSearchLevel) {
            travel.searchLevel++;
            if(floorMismatch)travel.minimumSearchLevel=Math.max(travel.minimumSearchLevel,travel.searchLevel);
        } else if(!detour && !floorMismatch && before-after>16 && travel.searchLevel>travel.minimumSearchLevel) {
            travel.searchLevel--;
        }
    }

    private static boolean verticalShadow(Location endpoint,Location destination) {
        if(endpoint==null || destination==null || endpoint.getWorld()!=destination.getWorld())return false;
        double dx=endpoint.getX()-destination.getX(),dz=endpoint.getZ()-destination.getZ();
        return dx*dx+dz*dz<=VERTICAL_SHADOW_HORIZONTAL_SQUARED
                && Math.abs(endpoint.getY()-destination.getY())>=VERTICAL_SHADOW_MIN_Y;
    }

    /** A partial stair segment can lie directly below the goal while genuinely climbing
     * toward it. Keep that inexpensive segment; only a stationary floor shadow widens. */
    private static boolean usefulFloorProgress(Location start,Location endpoint,Location destination) {
        return Math.abs(start.getY()-destination.getY())-Math.abs(endpoint.getY()-destination.getY())>GROUND_EPSILON
                && start.distance(destination)-endpoint.distance(destination)>=GOAL_PROGRESS_DISTANCE;
    }

    private void noRouteFailure(Travel travel,long tick) {
        travel.path=null;travel.pending=true;travel.pathReachesFinal=false;
        if(travel.searchLevel<travel.maxSearchLevel) {
            travel.searchLevel++;travel.retry=tick+LOCAL_RETRY_TICKS;travel.exhausted=false;
        } else {
            travel.exhausted=true;travel.retry=tick+IMPOSSIBLE_RETRY_TICKS;
            travel.avoidClosedDoors=false;
            travel.riserWaypoint=null;travel.riserHeight=Double.NaN;
        }
    }
    private void localFailure(Travel travel,long tick) {
        travel.path=null;travel.pending=true;travel.pathReachesFinal=false;travel.localFailures++;
        if(travel.localFailures>=2 && travel.searchLevel<travel.maxSearchLevel) {
            travel.searchLevel++;travel.localFailures=0;
        } else if(travel.localFailures>=3 && travel.searchLevel>=travel.maxSearchLevel) {
            travel.exhausted=true;
            travel.avoidClosedDoors=false;
            travel.riserWaypoint=null;travel.riserHeight=Double.NaN;
        }
        travel.retry=tick+(travel.exhausted?IMPOSSIBLE_RETRY_TICKS:LOCAL_RETRY_TICKS);
    }
    private void chunkFailure(Travel travel,long tick) {
        travel.path=null;travel.pending=true;travel.pathReachesFinal=false;
        travel.retry=tick+CHUNK_RETRY_TICKS;
    }

    public boolean exhausted(String npc) {
        Travel travel=travels.get(npc);return travel!=null && travel.exhausted;
    }
    /** A staged lift remains owned by route replay during its short local retry. */
    public boolean controlsVerticalStep(String npc) {
        Travel travel=travels.get(npc);return travel!=null && Double.isFinite(travel.riserHeight);
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
