package com.mdvcraft.mdvnpc.companion;

import com.mdvcraft.mdvnpc.api.*;
import com.mdvcraft.mdvnpc.routine.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** The real companion runtime and navigator, with a player that can be moved. */
class CompanionPathfindingTest {
    private FlatTripWorld trip;
    private CompanionRuntime guide;
    private Player player;
    private Location feet;
    private long now;
    private int relocations;

    @BeforeEach void setup() { MockBukkit.mock(); }
    @AfterEach void cleanup() { MockBukkit.unmock(); }

    private void create(int blocksPerUpdate) {
        trip = new FlatTripWorld(blocksPerUpdate);
        CompanionService service = mock(CompanionService.class, withSettings().stubOnly());
        doReturn(trip.navigator).when(service).navigator();
        doReturn(mock(RoutineGravity.class, withSettings().stubOnly())).when(service).gravity();
        doAnswer(call -> now).when(service).ticks();
        doReturn(CompanionSettings.defaults()).when(service).settings();
        doAnswer(call -> call.<Location>getArgument(0).clone()).when(service).onFloor(any());
        doAnswer(call -> {
            relocations++;
            trip.moveTo(call.getArgument(1));
            return true;
        }).when(service).teleportInternal(any(), any());
        guide = new CompanionRuntime(service, UUID.randomUUID(), mock(CompanionSpec.class), trip.npc.definition(), null);
        guide.attach(trip.npc);
        feet = new Location(trip.world, 3.5, 64, .5, -90, 0);
        player = mock(Player.class, withSettings().stubOnly());
        doReturn(UUID.randomUUID()).when(player).getUniqueId();
        doReturn(trip.world).when(player).getWorld();
        doReturn(GameMode.SURVIVAL).when(player).getGameMode();
        doAnswer(call -> feet.clone()).when(player).getLocation();
        doAnswer(call -> feet.clone().add(0, 1.6, 0)).when(player).getEyeLocation();
    }

    private void tick() {
        trip.navigator.beginTick(now, 4, Long.MAX_VALUE);
        guide.tick(now, true, CompanionSettings.defaults());
        now += 2;
    }

    @Test void followingWalksEvenBesideMarkers() {
        create(8192);
        for (int x = -8; x < 90; x += 6) trip.marker(x, 0);
        try (var bukkit = mockStatic(Bukkit.class, CALLS_REAL_METHODS)) {
            UUID id = player.getUniqueId();
            bukkit.when(() -> Bukkit.getPlayer(id)).thenReturn(player);
            guide.follow(player);
            while (now < 300) {
                feet.add(.43, 0, 0);
                tick();
            }
        }
        assertEquals(0, relocations, "a walking player is followed on foot");
        assertTrue(trip.position.distance(feet) < 9, "gap " + trip.position.distance(feet));
    }

    private MoveResult lead(boolean pauses, boolean unload) {
        AtomicReference<MoveResult> end = new AtomicReference<>();
        try (var bukkit = mockStatic(Bukkit.class, CALLS_REAL_METHODS)) {
            UUID id = player.getUniqueId();
            bukkit.when(() -> Bukkit.getPlayer(id)).thenReturn(player);
            guide.lead(trip.at(48, 0), player).thenAccept(end::set);
            while (now < 4000 && end.get() == null) {
                feet.setX(trip.position.getX() - (pauses && now % 160 >= 120 ? 9 : 2));
                feet.setZ(trip.position.getZ());
                if (unload && now >= 60) trip.loaded = false;
                tick();
            }
        }
        return end.get();
    }

    @Test void leadDoesNotFailWhileTheNodesAreStillBeingPlanned() {
        create(128);
        for (int x = 6; x < 48; x += 6) trip.marker(x, 3);
        assertEquals(MoveResult.ARRIVED, lead(false, false));
        assertEquals(0, trip.nativeSearches);
    }

    @Test void waitingForThePlayerDoesNotRestartTheLongPlan() {
        create(256);
        for (int x = 6; x < 48; x += 6) trip.marker(x, 3);
        assertEquals(MoveResult.ARRIVED, lead(true, false));
        // Past the last marker (42) a pause may resume straight to the goal.
        assertTrue(trip.nativeSearches == 0 || trip.firstNativeSearchX > 42,
                "left the markers at " + trip.firstNativeSearchX);
    }

    @Test void leadStillFailsWhenPlanningCannotContinue() {
        create(256);
        for (int x = 6; x < 48; x += 6) trip.marker(x, 3);
        assertEquals(MoveResult.FAILED, lead(false, true));
        assertTrue(now < 900, "a blocked plan is not progress; failed at " + now);
    }
}
