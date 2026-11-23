package com.mdvcraft.mdvnpc.routine;

import com.mdvcraft.mdvnpc.runtime.ActiveNpc;
import org.bukkit.Location;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** NPCs keep a minimum distance from each other, as if they collided. */
class NpcCollisionTest {
    private static final double MIN = 1.0, TOLERANCE = .03;

    @Test void twoNpcsMeetingHeadOnInTheOpenPassEachOther() {
        CrowdWorld w = new CrowdWorld(MIN, 0);
        w.npc("east", w.at(0, 0), w.at(12, 0), false);
        w.npc("west", w.at(12, 0), w.at(0, 0), false);
        assertTrue(w.run(2, 600) > 0, "both arrive");
        assertTrue(w.closest >= MIN - TOLERANCE, "closest " + w.closest + " " + w.closestPair);
    }

    @Test void aWalkerGoesRoundSomeoneSittingOnItsWay() {
        CrowdWorld w = new CrowdWorld(MIN, 0);
        w.npc("walker", w.at(0, 0), w.at(12, 0), false);
        w.npc("seated", w.at(6, 0), null, false);
        assertTrue(w.run(2, 600) > 0, () -> "walker at " + w.feet.get("walker") + " crowded " + w.navigator.crowded("walker") + " searches " + w.searches);
        assertTrue(w.closest >= MIN - TOLERANCE, "closest " + w.closest + " " + w.closestPair);
        assertEquals(w.at(6, 0), w.feet.get("seated"), "a seated NPC is never pushed");
    }

    @Test void anIdleNpcInTheWayIsShovedAsideRatherThanWalkedThrough() {
        CrowdWorld w = new CrowdWorld(MIN, 0);
        // A passage two blocks wide: there is no room to go round without moving the idle one.
        for (int x = -2; x <= 14; x++) { w.wall(x, 1); w.wall(x, -2); }
        w.npc("walker", w.at(0, 0), w.at(12, 0), false);
        w.npc("idle", w.at(6, 0), null, true);
        assertTrue(w.run(2, 600) > 0, "the walker arrives");
        assertTrue(w.closest >= MIN - TOLERANCE, "closest " + w.closest + " " + w.closestPair);
        assertNotEquals(w.at(6, 0), w.feet.get("idle"), "the idle NPC was moved");
    }

    @Test void headOnInANarrowPassageOneStepsIntoAWideningAndLetsTheOtherPass() {
        CrowdWorld w = new CrowdWorld(MIN, 0);
        // A passage one block wide from x=-2 to 14, with a single alcove at x=6. Both goals lie
        // beyond its ends, so neither stays standing inside it.
        for (int x = -2; x <= 14; x++) { w.wall(x, -1); if (x != 6) w.wall(x, 1); }
        w.wall(5, 2); w.wall(7, 2); w.wall(6, 3);
        w.npc("east", w.at(0, 0), w.at(18, 0), false);
        w.npc("west", w.at(12, 0), w.at(-6, 0), false);
        assertTrue(w.run(2, 1600) > 0, "both arrive: " + w.feet);
        assertTrue(w.closest >= MIN - TOLERANCE, "closest " + w.closest + " " + w.closestPair);
    }

    @Test void withNoRoomAtAllTheyWaitWithoutSearchingAgainAndAgain() {
        CrowdWorld w = new CrowdWorld(MIN, 0);
        for (int x = -2; x <= 14; x++) { w.wall(x, -1); w.wall(x, 1); }
        w.wall(-3, 0); w.wall(15, 0);
        // Each must get past the other to reach the far end of a closed passage.
        w.npc("east", w.at(0, 0), w.at(14, 0), false);
        w.npc("west", w.at(12, 0), w.at(-2, 0), false);
        assertEquals(-1, w.run(2, 800));
        assertTrue(w.closest >= MIN - TOLERANCE, "never through each other: " + w.closest);
        assertTrue(w.navigator.crowded("east") && w.navigator.crowded("west"),
                () -> w.feet + " traveling " + w.navigator.traveling("east") + "/" + w.navigator.traveling("west"));
        assertFalse(w.navigator.exhausted("east") || w.navigator.exhausted("west"), "waiting is not a failed route");
        assertTrue(w.searches <= 12, "searches " + w.searches);
    }

    @Test void aConfiguredPassThroughEndsAJam() {
        CrowdWorld w = new CrowdWorld(MIN, 3);
        for (int x = -2; x <= 14; x++) { w.wall(x, -1); w.wall(x, 1); }
        w.wall(-3, 0); w.wall(15, 0);
        w.npc("east", w.at(0, 0), w.at(12, 0), false);
        w.npc("west", w.at(12, 0), w.at(0, 0), false);
        assertTrue(w.run(2, 1200) > 0);
    }

    @Test void aQueueBehindAWalkerFollowsWithoutClosingIn() {
        CrowdWorld w = new CrowdWorld(MIN, 0);
        for (int x = -2; x <= 20; x++) { w.wall(x, -1); w.wall(x, 1); }
        w.npc("first", w.at(2, 0), w.at(18, 0), false);
        w.npc("second", w.at(1, 0), w.at(17, 0), false);
        w.npc("third", w.at(0, 0), w.at(16, 0), false);
        assertTrue(w.run(2, 800) > 0);
        assertTrue(w.closest >= MIN - TOLERANCE, "closest " + w.closest + " " + w.closestPair);
    }

    @Test void twentyNpcsHeadingForOnePointGatherRoundItWithoutOverlapping() {
        CrowdWorld w = new CrowdWorld(MIN, 0);
        Location goal = w.at(10, 10);
        for (int i = 0; i < 20; i++) {
            double angle = i * Math.PI * 2 / 20;
            // A walk that arrives stands idle there, so the next one can nudge it.
            w.npc("n" + i, w.at(Math.round(10 + Math.cos(angle) * 9), Math.round(10 + Math.sin(angle) * 9)), goal, true);
        }
        w.run(2, 600);
        assertNull(w.firstViolation, w.firstViolation);
        assertTrue(w.closest >= MIN - TOLERANCE, "closest " + w.closest + " " + w.closestPair);
        assertTrue(w.arrived.size() >= 1);
        for (String id : w.goals.keySet()) if (!w.arrived.contains(id))
            assertTrue(w.navigator.crowded(id) || w.navigator.traveling(id), () -> id + " at " + w.feet.get(id) + " arrived " + w.arrived);
        assertTrue(w.searches < 200, "searches " + w.searches);
    }

    @Test void idleNpcsStandingInsideEachOtherDriftApart() {
        CrowdWorld w = new CrowdWorld(MIN, 0);
        w.npc("a", w.at(4, 4), null, true);
        w.npc("b", w.at(4, 4).add(.2, 0, 0), null, true);
        w.npc("seated", w.at(4, 4).add(0, 0, .3), null, false);
        for (int i = 0; i < 200; i++) w.step(2);
        Location a = w.feet.get("a"), b = w.feet.get("b"), seated = w.feet.get("seated");
        assertTrue(a.distance(b) >= MIN - TOLERANCE, "a/b " + a.distance(b));
        assertTrue(a.distance(seated) >= MIN - TOLERANCE && b.distance(seated) >= MIN - TOLERANCE);
        assertEquals(w.at(4, 4).add(0, 0, .3), seated, "only idle NPCs move");
    }

    @Test void npcsOnDifferentFloorsDoNotCollide() {
        CrowdWorld w = new CrowdWorld(MIN, 0);
        ActiveNpc walker = w.npc("walker", w.at(0, 0), w.at(10, 0), false);
        w.npc("above", w.at(5, 0).add(0, 3, 0), null, false);
        assertTrue(w.run(2, 400) > 0);
        assertTrue(Math.abs(w.feet.get("walker").getZ() - .5) < .01, "walked straight under it");
        assertNotNull(walker);
    }

    @Test void disabledSpacingWalksThroughAsBefore() {
        CrowdWorld w = new CrowdWorld(0, 0);
        w.npc("east", w.at(0, 0), w.at(12, 0), false);
        w.npc("west", w.at(12, 0), w.at(0, 0), false);
        assertTrue(w.run(2, 400) > 0);
        assertTrue(w.closest < .1, "they cross in the middle");
    }
}
