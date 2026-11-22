package com.mdvcraft.mdvnpc.companion;

import com.mdvcraft.mdvnpc.api.CompanionSpec;
import com.mdvcraft.mdvnpc.routine.FlatTripWorld;
import com.mdvcraft.mdvnpc.routine.RoutineGravity;
import com.mdvcraft.mdvnpc.runtime.ActiveNpc;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Many guides following walking players at once, with the real runtime and navigator and the
 * same shared allowance the service gives them: 4 Paper searches per movement update.
 */
class CompanionCrowdFollowTest {
    private static final int STARTS_PER_UPDATE = 4;
    private FlatTripWorld trip;
    private long now;
    private final Map<String, Integer> relocations = new HashMap<>();

    @BeforeEach void setup() { MockBukkit.mock(); }
    @AfterEach void cleanup() { MockBukkit.unmock(); }

    private final class Pair {
        final CompanionRuntime guide;
        final ActiveNpc npc;
        final Player player;
        final Location feet;
        final double speed, heading, turn;

        Pair(CompanionService service, int index, int count) {
            double z = index * 10 + .5;
            npc = trip.walker("guide" + index, new Location(trip.world, .5, 64, z));
            guide = new CompanionRuntime(service, UUID.randomUUID(), mock(CompanionSpec.class), npc.definition(), null);
            guide.attach(npc);
            feet = new Location(trip.world, 3.5, 64, z, -90, 0);
            // 3.6-4.3 blocks per second, some walking straight and some turning.
            speed = 3.6 + .7 * index / Math.max(1, count - 1);
            heading = (index % 5 - 2) * .35;
            turn = index % 3 == 0 ? 0 : (index % 2 == 0 ? 1 : -1) * .004;
            player = mock(Player.class, withSettings().stubOnly());
            doReturn(UUID.randomUUID()).when(player).getUniqueId();
            doReturn(trip.world).when(player).getWorld();
            doReturn(GameMode.SURVIVAL).when(player).getGameMode();
            doAnswer(call -> feet.clone()).when(player).getLocation();
            doAnswer(call -> feet.clone().add(0, 1.6, 0)).when(player).getEyeLocation();
        }

        void walk(long tick) {
            double angle = heading + turn * tick, step = speed / 10; // one update = 2 ticks
            feet.add(Math.cos(angle) * step, 0, Math.sin(angle) * step);
            feet.setYaw((float) Math.toDegrees(Math.atan2(-Math.cos(angle), Math.sin(angle))));
        }

        double gap() { return npc.position().distance(feet); }
    }

    private record Outcome(int lost, double worstGap, int searches, int updates, int busiestUpdate) {}

    private Outcome run(int count) {
        trip = new FlatTripWorld(8192);
        CompanionService service = mock(CompanionService.class, withSettings().stubOnly());
        doReturn(trip.navigator).when(service).navigator();
        doReturn(mock(RoutineGravity.class, withSettings().stubOnly())).when(service).gravity();
        doAnswer(call -> now).when(service).ticks();
        doReturn(CompanionSettings.defaults()).when(service).settings();
        doAnswer(call -> call.<Location>getArgument(0).clone()).when(service).onFloor(any());
        doAnswer(call -> {
            ActiveNpc moved = call.getArgument(0);
            relocations.merge(moved.definition().id(), 1, Integer::sum);
            trip.place(moved, call.getArgument(1));
            return true;
        }).when(service).teleportInternal(any(), any());
        List<Pair> pairs = new ArrayList<>();
        for (int i = 0; i < count; i++) pairs.add(new Pair(service, i, count));
        List<CompanionRuntime> guides = pairs.stream().map(p -> p.guide).toList();
        int updates = 0, busiest = 0;
        double worst = 0;
        try (var bukkit = mockStatic(Bukkit.class, CALLS_REAL_METHODS)) {
            for (Pair pair : pairs) {
                UUID id = pair.player.getUniqueId();
                bukkit.when(() -> Bukkit.getPlayer(id)).thenReturn(pair.player);
                pair.guide.follow(pair.player);
            }
            while (now < 400) { // 20 seconds
                for (Pair pair : pairs) pair.walk(now);
                int before = trip.walkerSearches;
                // The service's own loop: one shared allowance, every guide in its turn.
                trip.navigator.beginTick(now, STARTS_PER_UPDATE, Long.MAX_VALUE);
                for (CompanionRuntime guide : CompanionService.movementOrder(guides, updates))
                    guide.tick(now, true, CompanionSettings.defaults());
                busiest = Math.max(busiest, trip.walkerSearches - before);
                updates++;
                now += 2;
                if (now > 60) for (Pair pair : pairs) worst = Math.max(worst, pair.gap());
            }
        }
        int lost = 0;
        for (Pair pair : pairs)
            if (relocations.containsKey(pair.npc.definition().id()) || pair.gap() > 24) lost++;
        return new Outcome(lost, worst, trip.walkerSearches, updates, busiest);
    }

    /** Paper is simulated with stair-shaped grid routes, √2 longer on a diagonal than the
     * player's own walk: even a lone guide trails by up to ~12 blocks there. 16 stays well
     * inside the 24 blocks at which a guide is teleported. */
    private static final double MAX_GAP = 16;

    @Test void twentyGuidesAllKeepWalkingBehindTheirPlayers() {
        Outcome outcome = run(CompanionSettings.defaults().maxActive());
        System.out.printf("20 guías: %d perdidos, hueco máximo %.1f, %d búsquedas en %d actualizaciones"
                        + " (%.2f de media, máximo %d)%n", outcome.lost(), outcome.worstGap(), outcome.searches(),
                outcome.updates(), (double) outcome.searches() / outcome.updates(), outcome.busiestUpdate());
        assertEquals(Map.of(), relocations, "nobody is teleported to their player");
        assertEquals(0, outcome.lost());
        assertTrue(outcome.worstGap() < MAX_GAP, "largest gap " + outcome.worstGap());
        assertTrue(outcome.busiestUpdate() <= STARTS_PER_UPDATE, "searches in one update " + outcome.busiestUpdate());
    }

    @Test void aFewGuidesAreUnaffected() {
        Outcome outcome = run(5);
        assertEquals(0, outcome.lost());
        assertTrue(outcome.worstGap() < MAX_GAP, "largest gap " + outcome.worstGap());
    }
}
