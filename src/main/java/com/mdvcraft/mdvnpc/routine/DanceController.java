package com.mdvcraft.mdvnpc.routine;

import com.mdvcraft.mdvnpc.MdvNpcPlugin;
import com.mdvcraft.mdvnpc.runtime.ActiveNpc;
import com.mdvcraft.mdvnpc.trait.Trait;
import org.bukkit.Location;
import org.bukkit.World;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiPredicate;
import java.util.random.RandomGenerator;

/** Existing routine clock owns these activities; Paper is used only for the approach. */
public final class DanceController {
    public enum Result { MOVING, DANCING, FINISHED }
    private static final double HEARING_RADIUS = 14, JUMP_HEIGHT = .70;
    private static final int JUMP_TICKS = 12;
    private final MdvNpcPlugin plugin;
    private final RoutineNavigator navigator;
    private final BiPredicate<ActiveNpc, Location> teleport;
    private final DoorController doors;
    private final RandomGenerator random;
    private final Map<String, State> active = new HashMap<>();
    private final Map<FloorKey, FloorEntry> floors = new HashMap<>();
    private final DanceCrowd crowd = new DanceCrowd();

    private record FloorKey(String musician, World world, int x, int z, long y) {}
    private static final class FloorEntry { DanceFloor floor; int users; }
    private static final class State {
        ActiveNpc npc, musician;
        Location returnTo, destination, jumpGround;
        FloorKey floorKey; FloorEntry floor;
        long approachUntil, duration, until, nextGesture, nextMove, nextJump, jumpStart;
        boolean started, sneaking, previousSneaking;
    }

    public DanceController(MdvNpcPlugin plugin, RoutineNavigator navigator, BiPredicate<ActiveNpc, Location> teleport) {
        this(plugin, navigator, teleport, ThreadLocalRandom.current());
    }
    DanceController(MdvNpcPlugin plugin, RoutineNavigator navigator, BiPredicate<ActiveNpc, Location> teleport, RandomGenerator random) {
        this.plugin = plugin; this.navigator = navigator; this.teleport = teleport; this.random = random;
        doors = new DoorController(plugin);
    }

    /** The owner checks sitting, unmounts afterward, and keeps this return point reserved. */
    public boolean start(ActiveNpc npc, Location returnTo, long tick) {
        String id = npc.definition().id();
        if (npc.definition().traits().type() != Trait.PARTYGOER || active.containsKey(id) || !npc.entity().isValid()
                || plugin.music() == null || returnTo == null || !loaded(returnTo)) return false;
        Location current = npc.position();
        ActiveNpc musician = plugin.music().nearestPerformer(current, HEARING_RADIUS);
        if (musician == null || musician == npc) return false;
        Location source = musician.position();
        if (!loaded(source) || source.getWorld() != current.getWorld() || returnTo.getWorld() != source.getWorld()) return false;
        // The seat exit is ground; a mounted passenger's Y can be above its actual floor.
        RoutineTerrain terrain = new RoutineTerrain(source.getWorld(), doors);
        var node = terrain.near(returnTo);
        if (node == null) return false;
        double y = terrain.height(node);
        FloorKey key = new FloorKey(musician.definition().id(), source.getWorld(), source.getBlockX(), source.getBlockZ(), Double.doubleToLongBits(y));
        FloorEntry floor = floors.computeIfAbsent(key, ignored -> {
            FloorEntry entry = new FloorEntry(); entry.floor = new DanceFloor(source, y, doors, tick); return entry;
        });
        floor.floor.centerOn(source); floor.floor.refresh(tick);
        State state = new State(); state.npc = npc; state.musician = musician; state.floorKey = key; state.floor = floor;
        state.returnTo = returnTo.clone();
        Location spot = selectSpot(state, null);
        if (spot == null) { if (floor.users == 0) floors.remove(key); return false; }
        state.destination = spot;
        state.duration = Math.max(1, Math.min(600, plugin.settings().messages().getInt("routines.dance-duration-seconds", 60))) * 20L;
        state.approachUntil = tick + 400;
        state.previousSneaking = npc.disguise() != null && npc.disguise().getWatcher().isSneaking();
        state.sneaking = state.previousSneaking;
        active.put(id, state); floor.users++;
        crowd.position(id, current); crowd.reserve(id, spot);
        return true;
    }

    public Location returnTo(String id) { State state = active.get(id); return state == null ? null : state.returnTo.clone(); }

    public Result tick(ActiveNpc npc, long tick, int cadence, double speed) {
        String id = npc.definition().id(); State state = active.get(id);
        if (state == null) return Result.FINISHED;
        if (state.npc != npc) { cancel(id); return Result.FINISHED; }
        Location current = npc.position(), source = state.musician.position();
        if (npc.definition().traits().type() != Trait.PARTYGOER || !npc.entity().isValid() || !state.musician.entity().isValid()
                || plugin.music() == null || !plugin.music().isPerforming(state.musician)
                || current.getWorld() != source.getWorld() || !loaded(source) || !loaded(current)
                || current.distanceSquared(source) > HEARING_RADIUS * HEARING_RADIUS
                || source.getBlockX() != state.floorKey.x() || source.getBlockZ() != state.floorKey.z()
                || state.started && tick >= state.until || !state.started && tick >= state.approachUntil) {
            cancel(id); return Result.FINISHED;
        }
        state.floor.floor.centerOn(source); state.floor.floor.refresh(tick);
        if (state.jumpGround != null) {
            if (!jumpFrame(state, tick)) { cancel(id); return Result.FINISHED; }
            return Result.DANCING;
        }
        crowd.position(id, current);
        if (!state.started) {
            if (!state.floor.floor.stand(current) || !crowd.free(id, current, .85)) {
                if (!state.floor.floor.stand(state.destination)) { cancel(id); return Result.FINISHED; }
                navigator.move(npc, state.destination, speed, tick, cadence);
                current = npc.position(); crowd.position(id, current);
                if (!state.floor.floor.stand(current) || !crowd.free(id, current, .85)) return Result.MOVING;
            }
            state.started = true; state.until = tick + state.duration;
            navigator.cancel(id); state.destination = null; crowd.release(id);
            state.nextMove = tick + 8; state.nextGesture = tick;
        } else if (!state.floor.floor.stand(current)) {
            // Never snap upward/downward to repair a dance: the owner resumes its seated goal.
            cancel(id); return Result.FINISHED;
        }
        if (state.destination == null && tick >= state.nextMove) {
            state.destination = selectSpot(state, current); state.nextMove = tick + 10;
            if (state.destination != null) crowd.reserve(id, state.destination);
        }
        if (state.destination != null) step(state, tick, cadence, speed);
        if (tick >= state.nextGesture) {
            state.nextGesture = tick + 10;
            int gesture = random.nextInt(4); sneaking(state, gesture == 0);
            if (gesture == 1 && tick >= state.nextJump) beginJump(state, tick);
            if (gesture == 2) faceMusic(npc, source);
        }
        return Result.DANCING;
    }

    /** Forty continuous candidates spread across the ring, rather than block-centred slots. */
    private Location selectSpot(State state, Location previous) {
        DanceFloor floor = state.floor.floor; Location best = null; double bestScore = -1;
        double phase = random.nextDouble() * Math.PI * 2 + (state.npc.definition().id().hashCode() & 255) * Math.PI / 128;
        for (int i = 0; i < 40; i++) {
            double angle = phase + i * 2.399963229728653, radius = 1.25 + (i % 4) * .5;
            Location point = floor.center.clone(); point.setY(floor.y);
            point.add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius);
            double moved = previous == null ? state.npc.position().distanceSquared(point) : previous.distanceSquared(point);
            if (previous != null && moved < .64 || !crowd.free(state.npc.definition().id(), point, 1.05) || !floor.plannedStand(point)) continue;
            if (previous != null && !floor.plannedSegment(previous, point)) continue;
            double score = previous == null ? 1 / (1 + moved) : moved;
            if (score > bestScore) { best = point; bestScore = score; }
        }
        return best;
    }

    private void step(State state, long tick, int cadence, double speed) {
        Location current = state.npc.position(), destination = state.destination;
        double dx = destination.getX() - current.getX(), dz = destination.getZ() - current.getZ();
        double distance = Math.hypot(dx, dz);
        double amount = Math.min(distance, Math.max(.6, Math.min(1.8, speed)) * Math.max(1, Math.min(4, cadence)) / 20);
        Location next = current.clone(); next.setY(state.floor.floor.y);
        if (distance > .001) next.add(dx * amount / distance, 0, dz * amount / distance);
        if (!state.floor.floor.segment(current, next) || !crowd.free(state.npc.definition().id(), next, .8) || !teleport.test(state.npc, next)) {
            state.destination = null; crowd.release(state.npc.definition().id()); state.nextMove = tick + 10; return;
        }
        crowd.position(state.npc.definition().id(), next); faceMusic(state.npc, state.musician.position());
        if (distance <= amount + .01) {
            state.destination = null; crowd.release(state.npc.definition().id()); state.nextMove = tick + 4;
        }
    }

    private static boolean loaded(Location location) {
        World world = location.getWorld(); return world != null && world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4);
    }
    private static void faceMusic(ActiveNpc npc, Location source) {
        Location current = npc.position(); var delta = source.toVector().subtract(current.toVector()).setY(0);
        if (delta.lengthSquared() < .001) return;
        Location facing = current.clone().setDirection(delta);
        float difference = ((facing.getYaw() - current.getYaw()) % 360 + 540) % 360 - 180;
        if (Math.abs(difference) > .5 || Math.abs(current.getPitch()) > .5)
            npc.entity().setRotation(current.getYaw() + Math.max(-12, Math.min(12, difference)), 0);
    }
    private void sneaking(State state, boolean value) {
        if (state.sneaking == value) return;
        state.sneaking = value;
        if (state.npc.disguise() != null) state.npc.disguise().getWatcher().setSneaking(value);
    }
    private void beginJump(State state, long tick) {
        Location ground = state.npc.position();
        if (!state.floor.floor.jumpClear(ground, JUMP_HEIGHT)) return;
        state.destination = null; crowd.release(state.npc.definition().id());
        sneaking(state, false); state.jumpGround = ground.clone(); state.jumpStart = tick; state.nextJump = tick + 48;
    }
    private boolean jumpFrame(State state, long tick) {
        Location ground = state.jumpGround;
        if (!loaded(ground) || ground.getWorld() != state.npc.entity().getWorld()) return false;
        double phase = Math.min(1, Math.max(0, (tick - state.jumpStart) / (double)JUMP_TICKS));
        Location next = ground.clone().add(0, 4 * JUMP_HEIGHT * phase * (1 - phase), 0);
        if (!state.floor.floor.airborneClear(next) || phase >= 1 && !state.floor.floor.stand(next)
                || !teleport.test(state.npc, next)) return false;
        if (phase >= 1) { state.jumpGround = null; state.nextMove = tick + 4; }
        return true;
    }
    public void cancel(String id) {
        State state = active.remove(id); navigator.cancel(id); crowd.remove(id);
        if (state == null) return;
        if (--state.floor.users == 0) floors.remove(state.floorKey);
        sneaking(state, state.previousSneaking);
        if (state.jumpGround != null && state.npc.entity().isValid() && state.npc.entity().getWorld() == state.jumpGround.getWorld()
                && loaded(state.jumpGround) && state.floor.floor.physicalStand(state.jumpGround)) teleport.test(state.npc, state.jumpGround);
    }
    public void clear() { for (String id : java.util.List.copyOf(active.keySet())) cancel(id); }
}
