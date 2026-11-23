package com.mdvcraft.mdvnpc.routine;

import com.mdvcraft.mdvnpc.routine.BoundedPathfinder.Grid;
import com.mdvcraft.mdvnpc.routine.BoundedPathfinder.Node;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/** Incremental directed marker graph. Every connection is a complete, safe terrain path. */
public final class NetheriteNodeRouter {
    private static final int MAX_MARKERS = 512;
    private static final int MAX_PROBES = 8192;
    private static final int MAX_WORK = 131072;
    private static final int MAX_RESULT_POINTS = 16384;
    /** Reaching and leaving the network costs more, so a marked road is not cut short. */
    private static final double OFF_ROAD_COST = 1.5;
    /** Markers may be a detour, but not one longer than this many times a walkable direct path. */
    private static final double MAX_DETOUR = 2;
    /** Skipping a marker on the way must cost more than walking through it. */
    private static final double SKIP_COST = 1.5;
    /** Horizontal room around a connection, so a gently curved road between two markers fits. */
    static final int CORRIDOR_MARGIN = 6;
    /** A connection between two markers depends only on the terrain, so trips can share it. */
    public interface EdgeCache {
        /** @return the known path, {@link #NO_PATH} when known to be unwalkable, or null when unknown */
        List<Node> get(Node from, Node to);
        void put(Node from, Node to, List<Node> path);
    }
    public static final List<Node> NO_PATH = List.of();
    /** Without a source the node's cost is known; with one it is a lower bound not yet walked. */
    private record Entry(Node node, double cost, Node from) {}
    private final List<Node> available;
    private final Grid grid;
    private final Node start, goal;
    private final int linkRange, endpointRange, localMaxNodes, maxYDifference;
    private final EdgeCache cache;
    private final PriorityQueue<Entry> open = new PriorityQueue<>(Comparator.comparingDouble(Entry::cost));
    private final Map<Node, Double> costs = new HashMap<>();
    private final Map<Node, Node> parent = new HashMap<>();
    private final Map<Node, List<Node>> legs = new HashMap<>();
    /** Links that skip a marker lying between their ends. */
    private record Pair(Node from, Node to) {}
    private final java.util.Set<Pair> skipping = new java.util.HashSet<>();
    private Node pendingFrom, pendingTo;
    private BoundedPathfinder pending;
    private List<Node> guided, guidedMarkers;
    private int probes, work;
    private boolean done;
    private List<Node> result, markerRoute = List.of();

    public NetheriteNodeRouter(List<Node> markers, Grid grid, Node start, Node goal,
                               int linkRange, int endpointRange, int localMaxNodes) {
        this(markers, grid, start, goal, linkRange, endpointRange, localMaxNodes, 8, null);
    }
    /** Ranges are horizontal; markers further apart in height than {@code maxYDifference} never link. */
    public NetheriteNodeRouter(List<Node> markers, Grid grid, Node start, Node goal, int linkRange,
                               int endpointRange, int localMaxNodes, int maxYDifference, EdgeCache cache) {
        this.grid = grid; this.start = start; this.goal = goal; this.cache = cache;
        this.maxYDifference = Math.max(0, Math.min(64, maxYDifference));
        this.linkRange = Math.max(1, Math.min(64, linkRange));
        this.endpointRange = Math.max(1, Math.min(64, endpointRange));
        this.localMaxNodes = Math.max(16, Math.min(65536, localMaxNodes));
        available = markers == null ? List.of() : new ArrayList<>(new LinkedHashSet<>(markers));
        if (grid == null || start == null || goal == null || available.size() > MAX_MARKERS
                || available.contains(null)) { done = true; return; }
        costs.put(start, 0.0);
        // Every entrance competes by the cost of the whole trip, not by its distance alone.
        boolean onMarker = available.contains(start);
        for (Node marker : available)
            if (!marker.equals(start) && (nearby(start, marker, this.endpointRange)
                    || onMarker && nearby(start, marker, this.linkRange)))
                offer(start, marker);
        if (onMarker && nearby(start, goal, this.endpointRange)) offer(start, goal);
        if (open.isEmpty()) done = true;
    }

    /** The budget covers local A* expansions and graph bookkeeping, shared across all legs. */
    public int advance(int budget) {
        int used = 0;
        while (!done && used < Math.max(0, budget)) {
            if (work >= MAX_WORK || probes >= MAX_PROBES) { done = true; break; }
            if (pending != null) {
                int spent = pending.advance(Math.min(budget - used, MAX_WORK - work));
                used += spent; work += spent;
                if (!pending.done()) break;
                List<Node> path = pending.result(); pending = null;
                if (guided != null) finish(path);
                else {
                    if (cacheable(pendingFrom, pendingTo)) cache.put(pendingFrom, pendingTo, path == null ? NO_PATH : path);
                    accept(pendingFrom, pendingTo, path);
                }
                continue;
            }
            used++; work++;
            Entry next = open.poll();
            if (next == null) { done = true; break; }
            double known = costs.getOrDefault(next.node, Double.POSITIVE_INFINITY);
            if (next.from != null) {
                // A connection is only walked while it can still improve the trip.
                if (next.cost < known) {
                    List<Node> shared = shared(next.from, next.node);
                    if (shared == null) connect(next.from, next.node);
                    else accept(next.from, next.node, shared == NO_PATH ? null : shared);
                }
                continue;
            }
            if (next.cost != known) continue;
            if (next.node.equals(goal)) { complete(); continue; }
            if (nearby(next.node, goal, endpointRange)) offer(next.node, goal);
            List<Node> near = new ArrayList<>();
            for (Node marker : available)
                if (!marker.equals(next.node) && !marker.equals(goal) && nearby(next.node, marker, linkRange))
                    near.add(marker);
            for (Node marker : near) {
                if (skips(next.node, marker, near)) skipping.add(new Pair(next.node, marker));
                offer(next.node, marker);
            }
        }
        return used;
    }

    private void offer(Node from, Node to) {
        double bound = costs.get(from) + weight(from, to) * distance(from, to);
        if (bound < costs.getOrDefault(to, Double.POSITIVE_INFINITY)) open.add(new Entry(to, bound, from));
    }
    private double weight(Node from, Node to) {
        return from.equals(start) || to.equals(goal) ? OFF_ROAD_COST
                : skipping.contains(new Pair(from, to)) ? SKIP_COST : 1;
    }
    /** Another marker closer to both ends lies on the way: the road goes through it, corners
     * included. Skipping it stays possible, only dearer, for when that marker cannot be reached. */
    private static boolean skips(Node from, Node to, List<Node> near) {
        double link = flat(from, to);
        for (Node other : near)
            if (!other.equals(to) && flat(from, other) < link && flat(other, to) < link) return true;
        return false;
    }
    private static double flat(Node a, Node b) {
        double dx = a.x() - b.x(), dz = a.z() - b.z();
        return dx * dx + dz * dz;
    }

    private void connect(Node from, Node to) {
        pendingFrom = from; pendingTo = to; probes++;
        // Restrict a local link to a small corridor. A supposedly nearby edge must not
        // become an unbounded global search through an unrelated part of the map.
        int m = CORRIDOR_MARGIN, height = Math.max(4, maxYDifference);
        Grid local = new Grid() {
            @Override public boolean stand(Node n) {
                return n.x() >= Math.min(from.x(), to.x()) - m && n.x() <= Math.max(from.x(), to.x()) + m
                        && n.z() >= Math.min(from.z(), to.z()) - m && n.z() <= Math.max(from.z(), to.z()) + m
                        && n.y() >= Math.min(from.y(), to.y()) - height && n.y() <= Math.max(from.y(), to.y()) + height
                        && grid.stand(n);
            }
            @Override public boolean edge(Node a, Node b) { return grid.edge(a, b); }
            @Override public int maxRise() { return grid.maxRise(); }
            @Override public int maxDrop() { return grid.maxDrop(); }
        };
        pending = new BoundedPathfinder(local, from, to, localMaxNodes);
    }

    private boolean cacheable(Node from, Node to) {
        return cache != null && !from.equals(start) && !to.equals(goal) && !from.equals(goal) && !to.equals(start);
    }
    private List<Node> shared(Node from, Node to) {
        if (!cacheable(from, to)) return null;
        List<Node> known = cache.get(from, to);
        if (known != null) probes++;
        return known;
    }

    private void accept(Node from, Node to, List<Node> path) {
        if (path == null) return;
        double cost = costs.get(from) + weight(from, to) * pathCost(path);
        if (cost < costs.getOrDefault(to, Double.POSITIVE_INFINITY)) {
            costs.put(to, cost); parent.put(to, from);
            legs.put(to, path); open.add(new Entry(to, cost, null));
        }
    }

    private void complete() {
        LinkedList<Node> chain = new LinkedList<>();
        for (Node n = goal; n != null; n = parent.get(n)) chain.addFirst(n);
        ArrayList<Node> route = new ArrayList<>(List.of(start));
        for (int i = 1; i < chain.size(); i++) append(route, legs.get(chain.get(i)));
        if (route.size() > MAX_RESULT_POINTS) { done = true; return; }
        guided = List.copyOf(route);
        guidedMarkers = chain.stream().filter(available::contains).toList();
        // The straight line is a lower bound, so only a suspicious route pays for this search.
        if (pathCost(guided) > MAX_DETOUR * distance(start, goal)) connect(start, goal);
        else finish(null);
    }

    /** @param direct walkable local path without markers, or null when none was found */
    private void finish(List<Node> direct) {
        if (direct == null || pathCost(guided) <= MAX_DETOUR * pathCost(direct)) {
            result = guided; markerRoute = guidedMarkers;
        }
        done = true;
    }

    private static void append(List<Node> route, List<Node> leg) {
        for (Node node : leg) if (route.isEmpty() || !route.getLast().equals(node)) route.add(node);
    }
    private static double pathCost(List<Node> path) {
        double cost = 0;
        for (int i = 1; i < path.size(); i++) cost += distance(path.get(i - 1), path.get(i));
        return cost;
    }
    /** The height between markers is limited on its own, so a climb does not shorten the link range. */
    private boolean nearby(Node a, Node b, int range) {
        double dx = a.x() - b.x(), dz = a.z() - b.z();
        return Math.abs(a.y() - b.y()) <= maxYDifference && dx * dx + dz * dz <= (double) range * range;
    }
    private static double distance(Node a, Node b) {
        return Math.sqrt(Math.pow((double) a.x() - b.x(), 2) + Math.pow((double) a.y() - b.y(), 2)
                + Math.pow((double) a.z() - b.z(), 2));
    }
    public boolean done() { return done; }
    int work() { return work; }
    /** Complete low-level path, including entry and final approach, or null when no route exists. */
    public List<Node> result() { return result; }
    public List<Node> markers() { return markerRoute; }
}
