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
 * Paper calculates the route once per journey; short controlled steps follow its diagonal points.
 * The villager's brain stays disabled, preserving routine poses and all existing NPC behaviour.
 */
public final class RoutineNavigator {
    public enum Result { MOVING, ARRIVED, WAITING }
    private static final int RETRY_TICKS = 100, MAX_PATH_POINTS = 2048, PAPER_SEARCH_RANGE = 16;
    private final DoorController doors;
    private final BiPredicate<ActiveNpc, Location> teleport;
    private final Map<String, Travel> travels = new LinkedHashMap<>();
    private long budgetTick = Long.MIN_VALUE, spentNanos;
    private int maximumStarts = 2, startsLeft = 2;
    private long maximumNanos = 2_000_000;

    private static final class Travel {
        final ActiveNpc npc;
        final RoutineTerrain terrain;
        final Location destination;
        List<Location> path;
        int index;
        long retry;
        boolean pending = true;
        Travel(ActiveNpc npc, Location destination, DoorController doors) {
            this.npc = npc; this.destination = destination.clone();
            terrain = new RoutineTerrain(destination.getWorld(), doors);
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
            cancel(id); travel = new Travel(npc, destination, doors); travels.put(id, travel);
        }
        if (travel.path == null) {
            if (tick < travel.retry) return Result.WAITING;
            if (!travel.terrain.loaded(current.getBlockX(), current.getBlockZ())
                    || !travel.terrain.loaded(destination.getBlockX(), destination.getBlockZ())) {
                failed(travel, tick); return Result.WAITING;
            }
            if (startsLeft <= 0 || spentNanos >= maximumNanos) return Result.WAITING;
            startsLeft--;
            long started = System.nanoTime();
            try {
                Location start = prepareStart(travel, current, tick);
                if (start == null) { failed(travel, tick); return Result.WAITING; }
                current = start;
                travel.path = findPaperPath(travel, start);
                travel.index = 0; travel.pending = false;
                if (travel.path == null) { failed(travel, tick); return Result.WAITING; }
            } finally {
                spentNanos += System.nanoTime() - started;
            }
        }
        while (travel.index < travel.path.size()
                && current.distanceSquared(travel.path.get(travel.index)) < .01) travel.index++;
        if (travel.index >= travel.path.size()) {
            // Paper may return a useful partial path beyond its native follow range.
            // Continue from that endpoint, but never mark the actual goal arrived early.
            travel.path = null; travel.pending = true; travel.retry = tick + 10;
            return Result.WAITING;
        }
        Location next = travel.path.get(travel.index);
        Node nextFloor = new Node(next.getBlockX(), (int)Math.ceil(next.getY() - .02), next.getBlockZ());
        if (!travel.terrain.stand(nextFloor) || Math.abs(travel.terrain.height(nextFloor) - next.getY()) > .05) {
            failed(travel, tick); return Result.WAITING;
        }
        Location step = next.clone();
        // Clear an upward edge before moving horizontally, then descend beyond the edge.
        if (next.getY() > current.getY() + .015) { step.setX(current.getX()); step.setZ(current.getZ()); }
        else if (Math.hypot(next.getX() - current.getX(), next.getZ() - current.getZ()) > .02) step.setY(current.getY());
        Vector delta = step.toVector().subtract(current.toVector());
        double length = delta.length(), distance = Math.max(.01, speed) * Math.max(1, cadence) / 20.0;
        if (length > distance) delta.multiply(distance / length);
        step = current.clone().add(delta);
        if (Math.abs(delta.getX()) + Math.abs(delta.getZ()) > .001)
            step.setDirection(new Vector(delta.getX(), 0, delta.getZ()));
        int samples = Math.max(1, (int)Math.ceil(delta.length() / .16));
        for (int i = 1; i <= samples; i++) {
            Location check = current.clone().add(delta.clone().multiply((double)i / samples));
            if (!doors.openNear(npc.entity(), check, tick)
                    || !travel.terrain.fits(check.getX(), check.getY(), check.getZ(), false)) {
                failed(travel, tick); return Result.WAITING;
            }
        }
        if (!teleport.test(npc, step)) { failed(travel, tick); return Result.WAITING; }
        return Result.MOVING;
    }

    private Location prepareStart(Travel travel, Location current, long tick) {
        Node start = travel.terrain.near(current);
        if (start == null) return null;
        Location floor = current.clone(); floor.setY(travel.terrain.height(start));
        if (!doors.openNear(travel.npc.entity(), floor, tick)
                || !travel.terrain.fits(floor.getX(), floor.getY(), floor.getZ(), false)
                || Math.abs(floor.getY() - current.getY()) > 1.01
                || current.distanceSquared(floor) > .0001 && !teleport.test(travel.npc, floor)) {
            return null;
        }
        return floor;
    }

    private List<Location> findPaperPath(Travel travel, Location current) {
        Pathfinder pathfinder = travel.npc.entity().getPathfinder();
        pathfinder.setCanOpenDoors(true);
        pathfinder.setCanPassDoors(true);
        pathfinder.setCanFloat(false);
        AttributeInstance range = travel.npc.entity().getAttribute(Attribute.FOLLOW_RANGE);
        double priorRange = range == null ? PAPER_SEARCH_RANGE : range.getBaseValue();
        Pathfinder.PathResult result;
        try {
            // Native searches use FOLLOW_RANGE + 8 around the starting block. A modest range
            // limits each query; useful partial paths continue long journeys in segments.
            if (range != null) range.setBaseValue(PAPER_SEARCH_RANGE);
            double nativeRange = range == null ? PAPER_SEARCH_RANGE : range.getValue();
            if (!Double.isFinite(nativeRange) || nativeRange < 1 || nativeRange > 32
                    || !queryAreaLoaded(current, (int)Math.ceil(nativeRange) + 8)) return null;
            // NoAI villagers cannot be expected to tick physics and establish onGround.
            // The verified floor above supplies that flag only for this synchronous query.
            try (GroundFlag ignored = NativeGroundFlag.open(travel.npc.entity())) {
                result = pathfinder.findPath(travel.destination);
            }
        } finally {
            if (range != null) range.setBaseValue(priorRange);
        }
        if (result == null) return null;
        List<Location> points = result.getPoints();
        if (points == null || points.isEmpty() || points.size() > MAX_PATH_POINTS) return null;
        ArrayList<Location> path = new ArrayList<>(points.size() + 1);
        for (Location raw : points) {
            if (raw == null || raw.getWorld() != current.getWorld()
                    || !travel.terrain.loaded(raw.getBlockX(), raw.getBlockZ())) return null;
            Location centered = new Location(current.getWorld(), raw.getBlockX() + .5, raw.getY(), raw.getBlockZ() + .5);
            Node point = travel.terrain.near(centered);
            if (point == null) return null;
            Location waypoint = travel.terrain.location(point);
            if (path.isEmpty() || path.getLast().distanceSquared(waypoint) > .0001) path.add(waypoint);
        }
        Location last = path.getLast();
        if (last.getBlockX() == travel.destination.getBlockX() && last.getBlockZ() == travel.destination.getBlockZ()
                && Math.abs(last.getY() - travel.destination.getY()) < .1) {
            if (last.distanceSquared(travel.destination) > .0001) path.add(travel.destination.clone());
        } else if (current.distanceSquared(travel.destination) - last.distanceSquared(travel.destination) < .25) return null;
        return List.copyOf(path);
    }

    private static boolean queryAreaLoaded(Location from, int radius) {
        // Checking a bounded set of chunk indices prevents native PathNavigationRegion from
        // asking the world for a missing chunk. Never getChunkAt, tickets, or world scans.
        int minX = (from.getBlockX() - radius) >> 4, maxX = (from.getBlockX() + radius) >> 4;
        int minZ = (from.getBlockZ() - radius) >> 4, maxZ = (from.getBlockZ() + radius) >> 4;
        for (int x = minX; x <= maxX; x++)
            for (int z = minZ; z <= maxZ; z++)
                if (!from.getWorld().isChunkLoaded(x, z)) return false;
        return true;
    }

    private void failed(Travel travel, long tick) {
        travel.path = null; travel.pending = true; travel.retry = tick + RETRY_TICKS;
    }
    public void cancel(String npc) { travels.remove(npc); }
    public void clear() {
        travels.clear(); budgetTick = Long.MIN_VALUE;
    }

    /**
     * Paper 1.21.6 uses Mojang names at runtime: CraftEntity.getHandle and Entity.setOnGround.
     * Reflection is resolved once per implementation class; it never enables AI, physics or goals.
     * Keep the flag scoped to findPath so neither dancing nor idle entities inherit a fake state.
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
