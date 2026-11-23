package com.mdvcraft.mdvnpc.runtime;

import com.mdvcraft.mdvnpc.routine.RoutineTerrain;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;

import java.util.*;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/**
 * Where every NPC stands, for a simulated collision between them: routine NPCs and
 * companions. Buckets of 2x2 blocks, so a check reads at most nine of them. Positions are
 * updated by every internal teleport and rebuilt once a second from the live NPCs.
 */
public final class NpcSpacing {
    /** Bodies this far apart in height do not touch (a villager is almost two blocks tall). */
    public static final double SAME_LEVEL = 1.5;
    /** Moved this recently: walking, never pushed. */
    static final long WALKING_TICKS = 12;
    /** An owner that stops reporting its NPC as idle no longer lets it be pushed. */
    static final long IDLE_STALE_TICKS = 40;
    private static final long BLOCKED_STALE_TICKS = 10;
    private static final double CELL = 2;
    /** How far one shove or one separation pass moves an NPC. */
    public static final double PUSH_STEP = .2, SEPARATION_STEP = .1;

    public static final class Entry {
        final UUID id;
        ActiveNpc npc;
        World world;
        double x, y, z;
        Cell cell;
        long moved = Long.MIN_VALUE / 2, idleSeen = Long.MIN_VALUE / 2, blockedAt = Long.MIN_VALUE / 2, seen;
        boolean pushable, stationary;
        BiPredicate<ActiveNpc, Location> mover;
        UUID blockedBy;
        long priority = Long.MAX_VALUE;
        Entry(UUID id) { this.id = id; }
        public UUID id() { return id; }
        public ActiveNpc npc() { return npc; }
        public double x() { return x; }
        public double y() { return y; }
        public double z() { return z; }
        public World world() { return world; }
        public Location location() { return new Location(world, x, y, z); }
    }
    record Cell(UUID world, int x, int z) {}

    private final Map<UUID, Entry> entries = new HashMap<>();
    private final Map<Cell, List<Entry>> cells = new HashMap<>();
    private LongSupplier clock;
    private boolean enabled = true, pushIdle = true, companions = true;
    private double minDistance = 1;
    private long passThroughTicks;
    private long nextPriority;
    private long rebuiltAt = Long.MIN_VALUE / 2;

    public NpcSpacing() { this(() -> System.nanoTime() / 50_000_000L); }
    /** {@code clock} counts server ticks (50 ms); tests drive it by hand. */
    public NpcSpacing(LongSupplier clock) { this.clock = clock; }

    public void configure(ConfigurationSection cfg) {
        enabled = cfg == null || cfg.getBoolean("npc-spacing.enabled", true);
        double min = cfg == null ? 1 : cfg.getDouble("npc-spacing.min-distance", 1);
        minDistance = Double.isFinite(min) ? Math.max(0, Math.min(3, min)) : 1;
        if (minDistance < .05) enabled = false;
        pushIdle = cfg == null || cfg.getBoolean("npc-spacing.push-idle", true);
        double pass = cfg == null ? 0 : cfg.getDouble("npc-spacing.pass-through-after-seconds", 0);
        passThroughTicks = Double.isFinite(pass) ? Math.round(Math.max(0, Math.min(120, pass)) * 20) : 0;
        companions = cfg == null || cfg.getBoolean("npc-spacing.companions", true);
    }
    public void configure(boolean enabled, double minDistance, boolean pushIdle, double passThroughSeconds) {
        this.minDistance = Math.max(0, Math.min(3, minDistance));
        this.enabled = enabled && this.minDistance >= .05;
        this.pushIdle = pushIdle;
        passThroughTicks = Math.round(Math.max(0, Math.min(120, passThroughSeconds)) * 20);
    }

    public boolean enabled() { return enabled; }
    public double minDistance() { return minDistance; }
    public boolean companions() { return companions; }
    /** 0: never walk through another NPC. */
    public long passThroughTicks() { return passThroughTicks; }
    public long now() { return clock.getAsLong(); }

    // ------------------------------------------------------------------ positions

    /** Where it stands now, without counting as a step. */
    public void track(ActiveNpc npc) {
        if (npc == null || npc.entity() == null) return;
        Location at = npc.entity().getLocation();
        if (at == null || at.getWorld() == null) return;
        Entry e = entries.computeIfAbsent(npc.entity().getUniqueId(), Entry::new);
        e.npc = npc; e.seen = now();
        place(e, at);
    }
    /** A walking step or any other internal move. */
    public void moved(ActiveNpc npc) {
        if (npc == null || npc.entity() == null) return;
        track(npc);
        Entry e = entries.get(npc.entity().getUniqueId());
        if (e != null) e.moved = now();
    }
    /** The owner reports, on each of its updates, whether this NPC may be shoved aside. */
    public void idle(ActiveNpc npc, boolean pushable, BiPredicate<ActiveNpc, Location> mover) {
        idle(npc, pushable, pushable, mover);
    }
    /** {@code stationary}: on no trip at all (seated, working, standing); a walker waiting its turn is not. */
    public void idle(ActiveNpc npc, boolean pushable, boolean stationary, BiPredicate<ActiveNpc, Location> mover) {
        if (npc == null || npc.entity() == null) return;
        Entry e = entries.get(npc.entity().getUniqueId());
        if (e == null) { track(npc); e = entries.get(npc.entity().getUniqueId()); }
        if (e == null) return;
        e.pushable = pushable; e.stationary = stationary || pushable; e.mover = mover; e.idleSeen = now();
    }
    /** Reported by its owner as on no trip, and not to be pushed: seated, working, a static NPC. */
    public boolean settled(Entry e) {
        return e.stationary && now() - e.idleSeen <= IDLE_STALE_TICKS && !walking(e) && !pushable(e);
    }
    public void remove(UUID id) {
        Entry e = entries.remove(id);
        if (e != null) unplace(e);
    }
    public void clear() { entries.clear(); cells.clear(); }

    /** Once a second: adds new NPCs and forgets removed ones. */
    public void refresh(Collection<ActiveNpc> live) {
        long now = now();
        if (now - rebuiltAt < 20) return;
        rebuiltAt = now;
        Set<UUID> present = new HashSet<>();
        for (ActiveNpc npc : live) {
            if (npc == null || npc.entity() == null || !npc.entity().isValid()) continue;
            present.add(npc.entity().getUniqueId());
            track(npc);
        }
        for (UUID id : List.copyOf(entries.keySet())) if (!present.contains(id)) remove(id);
    }

    private void place(Entry e, Location at) {
        e.world = at.getWorld(); e.x = at.getX(); e.y = at.getY(); e.z = at.getZ();
        Cell cell = cell(e.world, e.x, e.z);
        if (cell.equals(e.cell)) return;
        unplace(e);
        e.cell = cell;
        cells.computeIfAbsent(cell, ignored -> new ArrayList<>(2)).add(e);
    }
    private void unplace(Entry e) {
        if (e.cell == null) return;
        List<Entry> bucket = cells.get(e.cell);
        if (bucket != null) { bucket.remove(e); if (bucket.isEmpty()) cells.remove(e.cell); }
        e.cell = null;
    }
    private static Cell cell(World world, double x, double z) {
        return new Cell(world.getUID(), (int) Math.floor(x / CELL), (int) Math.floor(z / CELL));
    }

    // ------------------------------------------------------------------ state

    public Entry entry(UUID id) { return entries.get(id); }
    public boolean walking(Entry e) { return now() - e.moved <= WALKING_TICKS; }
    public boolean pushable(Entry e) {
        return pushIdle && e.pushable && e.mover != null && now() - e.idleSeen <= IDLE_STALE_TICKS && !walking(e)
                && e.npc != null && e.npc.entity().isValid() && !e.npc.entity().isInsideVehicle();
    }
    /** The order in which walkers that face each other give way: the newer trip yields. */
    public long nextPriority() { return ++nextPriority; }
    public void priority(UUID id, long priority) { Entry e = entries.get(id); if (e != null) e.priority = priority; }
    public long priority(UUID id) { Entry e = entries.get(id); return e == null ? Long.MAX_VALUE : e.priority; }
    public void blocked(UUID id, UUID by) {
        Entry e = entries.get(id);
        if (e != null) { e.blockedBy = by; e.blockedAt = now(); }
    }
    public void unblocked(UUID id) { Entry e = entries.get(id); if (e != null) e.blockedBy = null; }
    public UUID blockedBy(UUID id) {
        Entry e = entries.get(id);
        return e == null || e.blockedBy == null || now() - e.blockedAt > BLOCKED_STALE_TICKS ? null : e.blockedBy;
    }

    // ------------------------------------------------------------------ queries

    /** Every other NPC whose centre is within {@code radius} of this point, on any level. */
    public List<Entry> near(World world, double x, double z, double radius, UUID self) {
        List<Entry> found = new ArrayList<>();
        if (world == null) return found;
        int span = (int) Math.ceil(radius / CELL);
        Cell center = cell(world, x, z);
        for (int dx = -span; dx <= span; dx++) for (int dz = -span; dz <= span; dz++) {
            List<Entry> bucket = cells.get(new Cell(center.world(), center.x() + dx, center.z() + dz));
            if (bucket == null) continue;
            for (Entry e : bucket) {
                // A removed NPC lingers at most until the next refresh, a second later.
                if (e.id.equals(self)) continue;
                double ex = e.x - x, ez = e.z - z;
                if (ex * ex + ez * ez <= radius * radius) found.add(e);
            }
        }
        return found;
    }

    /**
     * The nearest NPC this move would walk into: it ends closer than the minimum distance
     * and closer than it started. Moving away from someone already too close is allowed,
     * so two NPCs that overlap can always separate.
     */
    public Entry blocker(UUID self, Location from, Location to, Predicate<Entry> ignore) {
        if (!enabled || to.getWorld() == null) return null;
        double min2 = minDistance * minDistance;
        Entry best = null; double bestDistance = Double.MAX_VALUE;
        for (Entry e : near(to.getWorld(), to.getX(), to.getZ(), minDistance + .01, self)) {
            if (Math.abs(e.y - to.getY()) >= SAME_LEVEL || ignore != null && ignore.test(e)) continue;
            double tx = e.x - to.getX(), tz = e.z - to.getZ(), after = tx * tx + tz * tz;
            if (after >= min2) continue;
            double fx = e.x - from.getX(), fz = e.z - from.getZ(), before = fx * fx + fz * fz;
            if (after >= before - 1e-6) continue;
            if (after < bestDistance) { best = e; bestDistance = after; }
        }
        return best;
    }

    /**
     * The fraction of a straight move that can be walked before touching anyone, from 0 to 1.
     */
    public double allowance(UUID self, Location from, Location to, Predicate<Entry> ignore) {
        if (!enabled || to.getWorld() == null) return 1;
        double dx = to.getX() - from.getX(), dz = to.getZ() - from.getZ();
        double length2 = dx * dx + dz * dz;
        if (length2 < 1e-12) return 1;
        double t = 1, min2 = minDistance * minDistance;
        for (Entry e : near(to.getWorld(), (from.getX() + to.getX()) / 2, (from.getZ() + to.getZ()) / 2,
                minDistance + Math.sqrt(length2) / 2 + .01, self)) {
            if (Math.abs(e.y - to.getY()) >= SAME_LEVEL || ignore != null && ignore.test(e)) continue;
            double fx = from.getX() - e.x, fz = from.getZ() - e.z;
            double c = fx * fx + fz * fz - min2, b = 2 * (fx * dx + fz * dz);
            if (b >= 0) continue; // moving away or sideways from it
            if (c <= 0) return 0; // already touching and moving closer
            double disc = b * b - 4 * length2 * c;
            if (disc < 0) continue;
            double enter = (-b - Math.sqrt(disc)) / (2 * length2);
            if (enter >= 0 && enter < t) t = enter;
        }
        return Math.max(0, Math.min(1, t));
    }

    /** Nobody stands within {@code distance} of this point. */
    public boolean free(UUID self, Location point, double distance) {
        if (!enabled || point.getWorld() == null) return true;
        for (Entry e : near(point.getWorld(), point.getX(), point.getZ(), distance, self))
            if (Math.abs(e.y - point.getY()) < SAME_LEVEL) return false;
        return true;
    }

    // ------------------------------------------------------------------ pushing

    /** A level position a body can occupy: floor at this exact height, no hazard, no wall. */
    public static boolean walkable(RoutineTerrain terrain, double x, double y, double z) {
        double support = terrain.supportHeight(x, y, z, .02);
        return Double.isFinite(support) && Math.abs(support - y) <= .02 && terrain.fits(x, y, z, false);
    }

    /** Shove an idle NPC; false when it cannot move that way. */
    public boolean push(Entry e, double dx, double dz, RoutineTerrain terrain) {
        if (!pushable(e)) return false;
        Location from = e.npc.entity().getLocation();
        if (from.getWorld() != e.world) return false;
        Location to = from.clone().add(dx, 0, dz);
        if (!walkable(terrain, to.getX(), to.getY(), to.getZ())
                || !walkable(terrain, from.getX() + dx / 2, from.getY(), from.getZ() + dz / 2)) return false;
        // Being shoved into a third NPC is no better than being walked into.
        if (blocker(e.id, from, to, null) != null) return false;
        long walked = e.moved;
        boolean accepted = e.mover.test(e.npc, to);
        // The mover records a step; a shove is not walking, so it may be shoved again.
        e.moved = walked;
        if (!accepted) return false;
        Location actual = e.npc.entity().getLocation();
        place(e, actual);
        return true;
    }

    /**
     * NPCs that already overlap (spawned or placed together) drift apart a little on each
     * pass. Only idle NPCs move; seated, working or static ones stay where they are.
     */
    public int separate(Function<World, RoutineTerrain> terrains) {
        if (!enabled || !pushIdle) return 0;
        Map<World, RoutineTerrain> byWorld = new HashMap<>();
        int moved = 0;
        for (Entry e : List.copyOf(entries.values())) {
            if (!pushable(e)) continue;
            double pushX = 0, pushZ = 0; int touching = 0;
            for (Entry other : near(e.world, e.x, e.z, minDistance, e.id)) {
                if (Math.abs(other.y - e.y) >= SAME_LEVEL || walking(other)) continue;
                double dx = e.x - other.x, dz = e.z - other.z, distance = Math.sqrt(dx * dx + dz * dz);
                if (distance < 1e-4) {
                    // Exactly on top of each other: a stable direction from the pair's ids.
                    double angle = (e.id.hashCode() ^ other.id.hashCode() * 31) * 1e-3;
                    dx = Math.cos(angle); dz = Math.sin(angle); distance = 1;
                    if (e.id.compareTo(other.id) < 0) { dx = -dx; dz = -dz; }
                }
                pushX += dx / distance; pushZ += dz / distance; touching++;
            }
            if (touching == 0) continue;
            double length = Math.sqrt(pushX * pushX + pushZ * pushZ);
            if (length < 1e-6) continue;
            RoutineTerrain terrain = byWorld.computeIfAbsent(e.world, terrains);
            if (push(e, pushX / length * SEPARATION_STEP, pushZ / length * SEPARATION_STEP, terrain)) moved++;
        }
        return moved;
    }
    int size() { return entries.size(); }
}
