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
    /** Without a source the node's cost is known; with one it is a lower bound not yet walked. */
    private record Entry(Node node, double cost, Node from) {}
    private final List<Node> available;
    private final Grid grid;
    private final Node start, goal;
    private final int linkRange, endpointRange, localMaxNodes;
    private final PriorityQueue<Entry> open = new PriorityQueue<>(Comparator.comparingDouble(Entry::cost));
    private final Map<Node, Double> costs = new HashMap<>();
    private final Map<Node, Node> parent = new HashMap<>();
    private final Map<Node, List<Node>> legs = new HashMap<>();
    private Node pendingFrom, pendingTo;
    private BoundedPathfinder pending;
    private List<Node> guided, guidedMarkers;
    private int probes, work;
    private boolean done;
    private List<Node> result, markerRoute = List.of();

    public NetheriteNodeRouter(List<Node> markers, Grid grid, Node start, Node goal,
                               int linkRange, int endpointRange, int localMaxNodes) {
        this.grid = grid; this.start = start; this.goal = goal;
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
                if (guided != null) finish(path); else accept(path);
                continue;
            }
            used++; work++;
            Entry next = open.poll();
            if (next == null) { done = true; break; }
            double known = costs.getOrDefault(next.node, Double.POSITIVE_INFINITY);
            if (next.from != null) {
                // A connection is only walked while it can still improve the trip.
                if (next.cost < known) connect(next.from, next.node);
                continue;
            }
            if (next.cost != known) continue;
            if (next.node.equals(goal)) { complete(); continue; }
            if (nearby(next.node, goal, endpointRange)) offer(next.node, goal);
            for (Node marker : available)
                if (!marker.equals(next.node) && !marker.equals(goal) && nearby(next.node, marker, linkRange))
                    offer(next.node, marker);
        }
        return used;
    }

    private void offer(Node from, Node to) {
        double bound = costs.get(from) + weight(from, to) * distance(from, to);
        if (bound < costs.getOrDefault(to, Double.POSITIVE_INFINITY)) open.add(new Entry(to, bound, from));
    }
    private double weight(Node from, Node to) {
        return from.equals(start) || to.equals(goal) ? OFF_ROAD_COST : 1;
    }

    private void connect(Node from, Node to) {
        pendingFrom = from; pendingTo = to; probes++;
        // Restrict a local link to a small corridor. A supposedly nearby edge must not
        // become an unbounded global search through an unrelated part of the map.
        Grid local = new Grid() {
            @Override public boolean stand(Node n) {
                return n.x() >= Math.min(from.x(), to.x()) - 4 && n.x() <= Math.max(from.x(), to.x()) + 4
                        && n.z() >= Math.min(from.z(), to.z()) - 4 && n.z() <= Math.max(from.z(), to.z()) + 4
                        && n.y() >= Math.min(from.y(), to.y()) - 4 && n.y() <= Math.max(from.y(), to.y()) + 4
                        && grid.stand(n);
            }
            @Override public boolean edge(Node a, Node b) { return grid.edge(a, b); }
            @Override public int maxRise() { return grid.maxRise(); }
            @Override public int maxDrop() { return grid.maxDrop(); }
        };
        pending = new BoundedPathfinder(local, from, to, localMaxNodes);
    }

    private void accept(List<Node> path) {
        if (path == null) return;
        double cost = costs.get(pendingFrom) + weight(pendingFrom, pendingTo) * pathCost(path);
        if (cost < costs.getOrDefault(pendingTo, Double.POSITIVE_INFINITY)) {
            costs.put(pendingTo, cost); parent.put(pendingTo, pendingFrom);
            legs.put(pendingTo, path); open.add(new Entry(pendingTo, cost, null));
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
    private static boolean nearby(Node a, Node b, int range) { return distance(a, b) <= range; }
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
