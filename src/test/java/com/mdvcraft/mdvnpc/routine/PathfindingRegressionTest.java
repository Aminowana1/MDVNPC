package com.mdvcraft.mdvnpc.routine;

import com.mdvcraft.mdvnpc.routine.BoundedPathfinder.Node;
import org.bukkit.Location;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PathfindingRegressionTest {
    @BeforeEach void setup() { MockBukkit.mock(); }
    @AfterEach void cleanup() { MockBukkit.unmock(); }

    @Test void longPlanningThatAdvancesKeepsTheNodes() {
        var trip = new FlatTripWorld(256);
        for (int x = 6; x < 48; x += 6) trip.marker(x, 3);
        long arrived = trip.walk(trip.at(48, 0), 4, 2, 4000);
        assertTrue(arrived > 440, "this scan alone takes more than ten seconds; arrived at " + arrived);
        assertEquals(0, trip.nativeSearches, "a slow but advancing plan still follows its markers");
    }

    @Test void planningWithoutProgressStillFallsBackToPaper() {
        var trip = new FlatTripWorld(256);
        trip.marker(2, 3);
        for (long tick = 0; tick < 600 && trip.nativeSearches == 0; tick += 2) {
            trip.navigator.invalidateNodes(trip.world, 2, 3);
            trip.navigator.beginTick(tick, 2, Long.MAX_VALUE);
            trip.navigator.move(trip.npc, trip.at(12, 0), 4, tick, 2);
        }
        assertTrue(trip.nativeSearches > 0, "constant edits must not hold the NPC forever");
    }

    @Test void waitingForTheSharedBudgetIsNotAStall() {
        var trip = new FlatTripWorld(256);
        for (int x = 6; x < 48; x += 6) trip.marker(x, 3);
        // Far from the trip, with enough markers to own the whole allowance for over ten seconds.
        for (int x = 406; x < 470; x += 8) trip.marker(x, 3);
        var ahead = trip.bystander("ahead", trip.at(400, 0));
        boolean arrived = false;
        for (long tick = 0; tick < 8000 && !arrived; tick += 2) {
            trip.navigator.beginTick(tick, 2, Long.MAX_VALUE);
            if (trip.navigator.advancingNodes("ahead") || tick == 0)
                trip.navigator.move(ahead, trip.at(470, 0), 4, tick, 2);
            arrived = trip.navigator.move(trip.npc, trip.at(48, 0), 4, tick, 2) == RoutineNavigator.Result.ARRIVED;
        }
        assertTrue(arrived);
        assertEquals(0, trip.nativeSearches, "queueing behind another NPC's plan keeps the nodes");
    }

    @Test void aMarkerBehindDoesNotTurnAShortTripIntoADetour() {
        for (int behind : new int[]{6, 10}) {
            var trip = new FlatTripWorld(8192);
            trip.marker(-behind, 0);
            assertTrue(trip.walk(trip.at(3, 0), 4, 2, 800) >= 0);
            assertTrue(trip.walked <= 3.2, "walked " + trip.walked + " blocks for a trip of three");
        }
    }

    @Test void theEntranceIsChosenByTheWholeTrip() {
        var trip = new FlatTripWorld(8192);
        trip.marker(-3, 0);
        for (int x = 4; x <= 28; x += 6) trip.marker(x, 0);
        assertTrue(trip.walk(trip.at(30, 0), 4, 2, 1500) >= 0);
        assertTrue(trip.minX >= .5 - 1e-6, "the nearest marker is behind; the next one is on the way");
        assertEquals(0, trip.nativeSearches);
    }

    @Test void aMarkedRoadKeepsItsCorner() {
        BoundedPathfinder.Grid flat = new BoundedPathfinder.Grid() {
            public boolean stand(Node n) { return n.y() == 64; }
            public boolean edge(Node from, Node to) { return true; }
        };
        Node corner = new Node(12, 64, 0);
        var router = new NetheriteNodeRouter(List.of(new Node(0, 64, 0), corner, new Node(12, 64, 12)),
                flat, new Node(4, 64, 0), new Node(12, 64, 14), 12, 16, 2048);
        while (!router.done()) router.advance(256);
        assertNotNull(router.result());
        assertTrue(router.result().contains(corner), "marked roads exist to be followed, corners included");
    }

    @Test void aThinPanelOnTheCellBorderIsNotCrossed() {
        var trip = new FlatTripWorld(8192);
        trip.panel(4, 0);
        var terrain = new RoutineTerrain(trip.world, org.mockito.Mockito.mock(DoorController.class));
        assertFalse(terrain.edge(new Node(4, 64, 0), new Node(5, 64, 0)));
        assertFalse(terrain.edge(new Node(5, 64, 0), new Node(4, 64, 0)));
        assertTrue(terrain.edge(new Node(4, 64, 2), new Node(5, 64, 2)));

        trip.marker(2, 0);
        trip.marker(8, 0);
        assertTrue(trip.walk(trip.at(10, 0), 4, 2, 1200) >= 0, "walk around a single panel");
        assertEquals(0, trip.nativeSearches);
    }

    @Test void aNodeRouteThatFailsWhileWalkingHandsTheTripToPaper() {
        var trip = new FlatTripWorld(8192);
        trip.marker(2, 0);
        trip.marker(8, 0);
        trip.rejectOnce = true;
        assertTrue(trip.walk(trip.at(10, 0), 4, 2, 1200) >= 0);
        assertTrue(trip.nativeSearches > 0, "replanning the same node route repeats the same failure");
    }

    @Test void walkingMatchesTheConfiguredSpeed() {
        for (int cadence : new int[]{1, 2, 4})
            for (double speed : new double[]{2.5, 3.2, 4.3, 5.6, 8}) {
                var trip = new FlatTripWorld(8192);
                trip.navigator.configureNodes(false, 12, 16, 24, 256, 8192, 256);
                long arrived = trip.walk(trip.at(80, 0), speed, cadence, 2400);
                assertEquals(80 / speed * 20, arrived, 2 * cadence + 1, "speed " + speed + ", cadence " + cadence);
            }
    }

    @Test void carryingAStepBackwardsIsNotABlockedWalk() {
        var trip = new FlatTripWorld(8192);
        trip.marker(-1, 0);
        trip.marker(7, 0);
        assertTrue(trip.walk(trip.at(9, 0), 4, 2, 800) >= 0);
        assertEquals(0, trip.nativeSearches, "a turn back spends the step, it does not fail the route");
    }

    @Test void carryingAStepRoundACornerNeverExceedsTheSpeed() {
        var trip = new FlatTripWorld(8192);
        trip.navigator.configureNodes(false, 12, 16, 24, 256, 8192, 256);
        Location goal = trip.at(8, 8);
        for (long tick = 0; tick < 200; tick += 4) {
            double before = trip.walked;
            trip.navigator.beginTick(tick, 2, Long.MAX_VALUE);
            var result = trip.navigator.move(trip.npc, goal, 8, tick, 4);
            assertTrue(trip.walked - before <= 1.6 + 1e-6);
            if (result == RoutineNavigator.Result.ARRIVED) return;
        }
        fail("did not arrive");
    }
}
