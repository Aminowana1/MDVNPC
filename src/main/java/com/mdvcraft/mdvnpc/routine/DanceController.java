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

/** Temporary local dance activities, advanced by the existing routine scheduler. */
public final class DanceController {
    public enum Result { MOVING, DANCING, FINISHED }
    private static final double HEARING_RADIUS = 14;
    private static final double DANCE_RADIUS_SQUARED = 9;
    private static final double JUMP_HEIGHT = .70;
    private static final int JUMP_TICKS = 12;
    private final MdvNpcPlugin plugin;
    private final RoutineNavigator navigator;
    private final BiPredicate<ActiveNpc, Location> teleport;
    private final DoorController doors;
    private final RandomGenerator random;
    private final Map<String, State> active = new HashMap<>();

    private static final class State {
        ActiveNpc npc, musician;
        Location returnTo, destination, jumpGround;
        long approachUntil, duration, until, nextGesture, nextMove, jumpStart;
        boolean started, sneaking, previousSneaking;
    }

    public DanceController(MdvNpcPlugin plugin, RoutineNavigator navigator,
                           BiPredicate<ActiveNpc, Location> teleport) {
        this(plugin, navigator, teleport, ThreadLocalRandom.current());
    }

    DanceController(MdvNpcPlugin plugin, RoutineNavigator navigator,
                    BiPredicate<ActiveNpc, Location> teleport, RandomGenerator random) {
        this.plugin = plugin;
        this.navigator = navigator;
        this.teleport = teleport;
        this.random = random;
        this.doors = new DoorController(plugin);
    }

    /** Only called after the owner confirms that this NPC is sitting and may leave its seat. */
    public boolean start(ActiveNpc npc, Location returnTo, long tick) {
        if (npc.definition().traits().type() != Trait.PARTYGOER
                || active.containsKey(npc.definition().id()) || !npc.entity().isValid()
                || plugin.music() == null || returnTo == null) return false;
        Location current = npc.position();
        ActiveNpc musician = plugin.music().nearestPerformer(current, HEARING_RADIUS);
        if (musician == null || musician == npc) return false;
        Location spot = safeSpot(npc, musician, null);
        if (spot == null) return false;
        int min = Math.max(5, Math.min(120, plugin.settings().messages().getInt("routines.dance-min-seconds", 20)));
        int max = Math.max(min, Math.min(120, plugin.settings().messages().getInt("routines.dance-max-seconds", 40)));
        State state = new State();
        state.npc = npc;
        state.musician = musician;
        state.returnTo = returnTo.clone();
        state.destination = spot;
        state.duration = random.nextLong(min, (long) max + 1) * 20;
        state.approachUntil = tick + 400;
        state.previousSneaking = npc.disguise() != null && npc.disguise().getWatcher().isSneaking();
        state.sneaking = state.previousSneaking;
        active.put(npc.definition().id(), state);
        return true;
    }

    public Location returnTo(String id) {
        State state = active.get(id);
        return state == null ? null : state.returnTo.clone();
    }

    public Result tick(ActiveNpc npc, long tick, int cadence, double speed) {
        State state = active.get(npc.definition().id());
        if (state == null) return Result.FINISHED;
        Location current = npc.position();
        Location source = state.musician.position();
        if (npc.definition().traits().type() != Trait.PARTYGOER
                || !npc.entity().isValid() || !state.musician.entity().isValid() || plugin.music() == null
                || !plugin.music().isPerforming(state.musician)
                || current.getWorld() != source.getWorld()
                || !loaded(source) || !loaded(current)
                || current.distanceSquared(source) > HEARING_RADIUS * HEARING_RADIUS
                || state.started && tick >= state.until || !state.started && tick >= state.approachUntil) {
            cancel(npc.definition().id());
            return Result.FINISHED;
        }
        if (state.jumpGround != null) {
            if (!jumpFrame(state, tick)) {
                cancel(npc.definition().id());
                return Result.FINISHED;
            }
            return Result.DANCING;
        }
        if (current.distanceSquared(source) > DANCE_RADIUS_SQUARED) {
            if (!safeDestination(state.destination, source)) {
                state.destination = safeSpot(npc, state.musician, null);
                if (state.destination == null) {
                    cancel(npc.definition().id());
                    return Result.FINISHED;
                }
            }
            navigator.move(npc, state.destination, speed, tick, cadence);
            // Entering the dance radius is enough; waiting for an exact waypoint can leave
            // a dancer standing silently beside the band during a limited or failed search.
            if (npc.position().distanceSquared(source) > DANCE_RADIUS_SQUARED) return Result.MOVING;
        }
        if (!state.started) {
            state.until = tick + state.duration;
            state.started = true;
            navigator.cancel(npc.definition().id());
            state.destination = null;
            state.nextMove = tick + 24;
            state.nextGesture = tick;
        }
        if (state.destination == null && tick >= state.nextMove) {
            state.nextMove = tick + 24;
            Location next = safeSpot(npc, state.musician, npc.position());
            if (next != null) state.destination = next;
        }
        if (state.destination != null) {
            RoutineNavigator.Result moved = navigator.move(npc, state.destination, Math.min(speed, 1.4), tick, cadence);
            if (moved == RoutineNavigator.Result.ARRIVED || moved == RoutineNavigator.Result.WAITING) {
                // Keep dancing on the checked floor while a local sidestep is unavailable.
                // A fresh, nearby sidestep may be chosen at the next bounded movement interval.
                if (moved == RoutineNavigator.Result.WAITING) navigator.cancel(npc.definition().id());
                state.destination = null;
                faceMusic(npc, source);
            }
        }
        if (tick >= state.nextGesture) {
            state.nextGesture = tick + 8;
            int gesture = random.nextInt(4);
            sneaking(state, gesture == 0);
            if (gesture == 1 && state.destination == null) beginJump(state, tick);
            if (gesture == 2) faceMusic(npc, source);
        }
        return Result.DANCING;
    }

    /** At most sixteen already-loaded local candidates; no world/entity scans or chunk loads. */
    private Location safeSpot(ActiveNpc npc, ActiveNpc musician, Location previous) {
        Location source = musician.position();
        if (source.getWorld() == null || source.getWorld() != npc.position().getWorld() || !loaded(source)) return null;
        RoutineTerrain terrain = new RoutineTerrain(source.getWorld(), doors);
        double phase = Math.atan2(npc.position().getZ() - source.getZ(), npc.position().getX() - source.getX());
        int offset = random.nextInt(16);
        for (int i = 0; i < 16; i++) {
            double angle = phase + ((i + offset) % 16) * Math.PI / 8;
            double radius = i < 8 ? 1.7 : 2.4;
            Location candidate = source.clone().add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius);
            var node = terrain.near(candidate);
            if (node == null) continue;
            Location spot = terrain.location(node);
            double distance = spot.distanceSquared(source);
            if (distance < 1 || distance > DANCE_RADIUS_SQUARED || !terrain.fits(spot.getX(), spot.getY(), spot.getZ(), false)) continue;
            if (previous != null && (previous.distanceSquared(spot) < .20 || previous.distanceSquared(spot) > 2.25)) continue;
            return spot;
        }
        return null;
    }

    private boolean safeDestination(Location destination, Location source) {
        if (destination == null || destination.getWorld() != source.getWorld() || !loaded(destination)
                || destination.distanceSquared(source) > DANCE_RADIUS_SQUARED) return false;
        RoutineTerrain terrain = new RoutineTerrain(destination.getWorld(), doors);
        return terrain.near(destination) != null && terrain.fits(destination.getX(), destination.getY(), destination.getZ(), false);
    }

    private static boolean loaded(Location location) {
        World world = location.getWorld();
        return world != null && world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4);
    }

    private static void faceMusic(ActiveNpc npc, Location source) {
        Location current = npc.position();
        var delta = source.toVector().subtract(current.toVector()).setY(0);
        if (delta.lengthSquared() < .001) return;
        current.setDirection(delta);
        npc.entity().setRotation(current.getYaw(), 0);
    }

    private void sneaking(State state, boolean value) {
        if (state.sneaking == value) return;
        state.sneaking = value;
        if (state.npc.disguise() != null) state.npc.disguise().getWatcher().setSneaking(value);
    }

    /** AI stays disabled: a short checked arc animates jumping without enabling the villager brain. */
    private void beginJump(State state, long tick) {
        Location ground = state.npc.position();
        RoutineTerrain terrain = new RoutineTerrain(ground.getWorld(), doors);
        var node = terrain.near(ground);
        if (node == null || Math.abs(terrain.location(node).getY() - ground.getY()) > .12
                || !terrain.fits(ground.getX(), ground.getY() + JUMP_HEIGHT / 2, ground.getZ(), false)
                || !terrain.fits(ground.getX(), ground.getY() + JUMP_HEIGHT, ground.getZ(), false)) return;
        navigator.cancel(state.npc.definition().id());
        sneaking(state, false);
        state.jumpGround = ground.clone();
        state.jumpStart = tick;
    }

    private boolean jumpFrame(State state, long tick) {
        Location ground = state.jumpGround;
        if (!loaded(ground) || ground.getWorld() != state.npc.entity().getWorld()) return false;
        double phase = Math.min(1, Math.max(0, (tick - state.jumpStart) / (double) JUMP_TICKS));
        double height = 4 * JUMP_HEIGHT * phase * (1 - phase);
        Location next = ground.clone().add(0, height, 0);
        RoutineTerrain terrain = new RoutineTerrain(ground.getWorld(), doors);
        if (!terrain.fits(next.getX(), next.getY(), next.getZ(), false) || !teleport.test(state.npc, next)) return false;
        if (phase >= 1) state.jumpGround = null;
        return true;
    }

    public void cancel(String id) {
        State state = active.remove(id);
        navigator.cancel(id);
        if (state == null) return;
        sneaking(state, state.previousSneaking);
        if (state.jumpGround != null && state.npc.entity().isValid()
                && state.npc.entity().getWorld() == state.jumpGround.getWorld() && loaded(state.jumpGround)) {
            Location ground = state.jumpGround;
            RoutineTerrain terrain = new RoutineTerrain(ground.getWorld(), doors);
            if (terrain.near(ground) != null && terrain.fits(ground.getX(), ground.getY(), ground.getZ(), false))
                teleport.test(state.npc, ground);
        }
    }

    public void clear() {
        for (String id : java.util.List.copyOf(active.keySet())) cancel(id);
    }
}
