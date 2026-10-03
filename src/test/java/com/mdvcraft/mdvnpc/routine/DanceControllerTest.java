package com.mdvcraft.mdvnpc.routine;

import com.mdvcraft.mdvnpc.MdvNpcPlugin;
import com.mdvcraft.mdvnpc.config.NpcParser;
import com.mdvcraft.mdvnpc.config.Settings;
import com.mdvcraft.mdvnpc.music.MusicService;
import com.mdvcraft.mdvnpc.runtime.ActiveNpc;
import com.mdvcraft.mdvnpc.trait.Trait;
import me.libraryaddict.disguise.disguisetypes.PlayerDisguise;
import me.libraryaddict.disguise.disguisetypes.watchers.PlayerWatcher;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Villager;
import org.bukkit.util.BoundingBox;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.HashSet;
import java.util.function.BiPredicate;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DanceControllerTest {
    MdvNpcPlugin plugin;
    World world;
    MusicService music;
    RoutineNavigator navigator;
    DanceController dances;
    ActiveNpc seated, musician;
    RandomGenerator random;
    Map<String, Location> positions;
    BiPredicate<ActiveNpc, Location> teleport;
    boolean ceiling;
    Set<String> holes, obstacles;
    Map<String,Block> blockCache;

    @BeforeEach @SuppressWarnings("unchecked") void setup() {
        MockBukkit.mock();
        plugin = mock(MdvNpcPlugin.class);
        when(plugin.getName()).thenReturn("dance-test");
        when(plugin.settings()).thenReturn(Settings.parse(new YamlConfiguration()));
        music = mock(MusicService.class);
        when(plugin.music()).thenReturn(music);
        navigator = mock(RoutineNavigator.class);
        world = mock(World.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        when(world.getMinHeight()).thenReturn(-64);
        when(world.getMaxHeight()).thenReturn(320);
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        holes = new HashSet<>(); obstacles = new HashSet<>(); blockCache = new HashMap<>();
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(invocation -> {
            int x = invocation.getArgument(0), y = invocation.getArgument(1), z = invocation.getArgument(2);
            boolean solid = y == 63 && !holes.contains(x + "," + z) || ceiling && y == 66 || obstacles.contains(x + "," + y + "," + z);
            String key = x + "," + y + "," + z + "," + solid;
            return blockCache.computeIfAbsent(key, ignored -> block(x, y, z, solid));
        });
        positions = new HashMap<>();
        seated = npc("seated", 7.5);
        musician = npc("musician", .5);
        when(music.nearestPerformer(any(Location.class), eq(14d))).thenReturn(musician);
        when(music.isPerforming(musician)).thenReturn(true);
        when(navigator.move(any(), any(), anyDouble(), anyLong(), anyInt())).thenAnswer(invocation -> {
            ActiveNpc npc = invocation.getArgument(0);
            positions.put(npc.definition().id(), ((Location) invocation.getArgument(1)).clone());
            return RoutineNavigator.Result.ARRIVED;
        });
        teleport = mock(BiPredicate.class);
        when(teleport.test(any(), any())).thenAnswer(invocation -> {
            ActiveNpc npc = invocation.getArgument(0);
            positions.put(npc.definition().id(), ((Location) invocation.getArgument(1)).clone());
            return true;
        });
        random = mock(RandomGenerator.class);
        when(random.nextLong(anyLong(), anyLong())).thenReturn(20L);
        dances = new DanceController(plugin, navigator, teleport, random);
    }

    private Block block(int x, int y, int z, boolean solid) {
        Block block = mock(Block.class);
        when(block.getType()).thenReturn(solid ? Material.STONE : Material.AIR);
        when(block.getBoundingBox()).thenReturn(solid ? new BoundingBox(x, y, z, x + 1, y + 1, z + 1)
                : new BoundingBox(x, y, z, x, y, z));
        when(block.isPassable()).thenReturn(!solid);
        return block;
    }

    private ActiveNpc npc(String id, double x) {
        return npc(id, x, "fiestero");
    }

    private ActiveNpc npc(String id, double x, String trait) {
        var yaml = new YamlConfiguration();
        yaml.set("npcs." + id + ".location.world", "world");
        yaml.set("npcs." + id + ".trait.type", trait);
        Villager entity = mock(Villager.class);
        when(entity.isValid()).thenReturn(true);
        when(entity.getWorld()).thenReturn(world);
        Location position = new Location(world, x, 64, .5);
        positions.put(id, position);
        when(entity.getLocation()).thenAnswer(i -> positions.get(id).clone());
        doAnswer(call -> {
            Location p = positions.get(id).clone(); p.setYaw(call.getArgument(0)); p.setPitch(call.getArgument(1)); positions.put(id, p); return null;
        }).when(entity).setRotation(anyFloat(), anyFloat());
        return new ActiveNpc(NpcParser.parse(yaml).get(id), position, entity, null);
    }

    @AfterEach void cleanup() { dances.clear(); MockBukkit.unmock(); }

    @Test void everyOtherTraitSkipsMusicSearchAndCannotDance() {
        clearInvocations(music, world);
        for (Trait trait : Trait.values()) {
            if (trait == Trait.PARTYGOER) continue;
            ActiveNpc ordinary = npc("ordinary_" + trait.name().toLowerCase(java.util.Locale.ROOT), 2.5,
                    trait.name().toLowerCase(java.util.Locale.ROOT));
            assertFalse(dances.start(ordinary, ordinary.position(), 0), trait.name());
            assertEquals(DanceController.Result.FINISHED, dances.tick(ordinary, 2, 2, 2.4));
        }
        verifyNoInteractions(music, navigator, teleport);
        verify(world, never()).getBlockAt(anyInt(), anyInt(), anyInt());
    }

    @Test void startPreservesSeatAndReturnPointUntilOwnerUnmounts() {
        Location returnTo = seated.position();
        assertTrue(dances.start(seated, returnTo, 0));
        returnTo.setX(100);
        assertEquals(7.5, dances.returnTo("seated").getX());
        verifyNoInteractions(navigator, teleport);
        verify(seated.entity(), never()).leaveVehicle();
        assertFalse(dances.start(seated, seated.position(), 0));
    }

    @Test void dancerWalksToSafeLocalPointAndStopsImmediatelyWhenMusicStops() {
        assertTrue(dances.start(seated, seated.position(), 0));
        assertEquals(DanceController.Result.DANCING, dances.tick(seated, 0, 2, 2.4));
        assertTrue(seated.position().distanceSquared(musician.position()) <= 9);
        clearInvocations(navigator, teleport);
        when(music.isPerforming(musician)).thenReturn(false);
        assertEquals(DanceController.Result.FINISHED, dances.tick(seated, 2, 2, 2.4));
        verify(navigator).cancel("seated");
        verify(navigator, never()).move(any(), any(), anyDouble(), anyLong(), anyInt());
        verifyNoInteractions(teleport);
    }

    @Test void failedApproachIsBoundedAndCannotKeepAnNpcAwayFromItsRoutineForever() {
        doReturn(RoutineNavigator.Result.WAITING).when(navigator).move(any(), any(), anyDouble(), anyLong(), anyInt());
        assertTrue(dances.start(seated, seated.position(), 0));
        assertEquals(DanceController.Result.MOVING, dances.tick(seated, 2, 2, 2.4));
        assertEquals(DanceController.Result.FINISHED, dances.tick(seated, 400, 2, 2.4));
    }

    @Test void durationExpiresAndClearCancelsNavigation() {
        assertTrue(dances.start(seated, seated.position(), 0));
        dances.tick(seated, 0, 2, 2.4);
        assertEquals(DanceController.Result.FINISHED, dances.tick(seated, 1200, 2, 2.4));
        assertNull(dances.returnTo("seated"));
        assertTrue(dances.start(seated, seated.position(), 1202));
        clearInvocations(navigator);
        dances.clear();
        verify(navigator).cancel("seated");
        assertEquals(DanceController.Result.FINISHED, dances.tick(seated, 1204, 2, 2.4));
    }

    @Test void interruptedJumpSettlesOnItsCheckedGroundWithoutEnablingAiOrGravity() {
        when(random.nextInt(4)).thenReturn(1);
        assertTrue(dances.start(seated, seated.position(), 0));
        dances.tick(seated, 0, 2, 2.4);
        assertEquals(DanceController.Result.DANCING, dances.tick(seated, 2, 2, 2.4));
        assertTrue(seated.position().getY() > 64);
        dances.cancel("seated");
        assertEquals(64, seated.position().getY());
        verify(seated.entity(), never()).setAI(anyBoolean());
        verify(seated.entity(), never()).setGravity(anyBoolean());
    }

    @Test void dancerAlreadyWithinThreeBlocksJumpsEvenIfTheExactWaypointCannotBeReached() {
        positions.put("seated", new Location(world, 2.5, 64, .5));
        doReturn(RoutineNavigator.Result.WAITING).when(navigator).move(any(), any(), anyDouble(), anyLong(), anyInt());
        when(random.nextInt(4)).thenReturn(1);
        assertTrue(dances.start(seated, seated.position(), 0));
        assertEquals(DanceController.Result.DANCING, dances.tick(seated, 0, 2, 2.4));
        assertEquals(DanceController.Result.DANCING, dances.tick(seated, 2, 2, 2.4));
        assertTrue(seated.position().getY() > 64);
        verify(navigator, never()).move(any(), any(), anyDouble(), anyLong(), anyInt());
        verify(seated.entity(), never()).setAI(anyBoolean()); verify(seated.entity(), never()).setGravity(anyBoolean());
    }

    @Test void failedLocalSidestepKeepsTheDancerAnimatingAndAllowsJumping() {
        assertTrue(dances.start(seated, seated.position(), 0));
        dances.tick(seated, 0, 2, 2.4);
        doReturn(RoutineNavigator.Result.WAITING).when(navigator).move(any(), any(), anyDouble(), anyLong(), anyInt());
        when(random.nextInt(4)).thenReturn(1);
        assertEquals(DanceController.Result.DANCING, dances.tick(seated, 24, 2, 2.4));
        assertEquals(DanceController.Result.DANCING, dances.tick(seated, 26, 2, 2.4));
        assertTrue(seated.position().getY() > 64);
    }

    @Test void partygoerSneaksAndRestoresItsPreviousDisguiseState() {
        positions.put("seated", new Location(world, 2.5, 64, .5));
        PlayerDisguise disguise = mock(PlayerDisguise.class); PlayerWatcher watcher = mock(PlayerWatcher.class);
        when(disguise.getWatcher()).thenReturn(watcher);
        ActiveNpc dressed = new ActiveNpc(seated.definition(), seated.anchor(), seated.entity(), disguise);
        when(random.nextInt(4)).thenReturn(0);
        assertTrue(dances.start(dressed, dressed.position(), 0));
        assertEquals(DanceController.Result.DANCING, dances.tick(dressed, 0, 2, 2.4));
        verify(watcher).setSneaking(true);
        dances.cancel("seated"); verify(watcher).setSneaking(false);
    }

    @Test void lowCeilingPreventsJumpAndNearbyPlayersAreNeverScanned() {
        ceiling = true;
        when(random.nextInt(4)).thenReturn(1);
        assertTrue(dances.start(seated, seated.position(), 0));
        dances.tick(seated, 0, 2, 2.4);
        dances.tick(seated, 2, 2, 2.4);
        assertEquals(64, seated.position().getY());
        verifyNoInteractions(teleport);
        verify(world, never()).getPlayers();
        verify(world, never()).getNearbyPlayers(any(Location.class), anyDouble());
        verify(world, never()).getNearbyPlayers(any(Location.class), anyDouble(), any());
    }

    @Test void unloadedMusicPositionCannotRequestBlocksOrBeginNavigation() {
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(false);
        clearInvocations(world);
        assertFalse(dances.start(seated, seated.position(), 0));
        verify(world, never()).getBlockAt(anyInt(), anyInt(), anyInt());
        verifyNoInteractions(navigator, teleport);
    }

    @Test void anotherWorldOrLeavingHearingRadiusEndsDanceBeforeMovement() {
        assertTrue(dances.start(seated, seated.position(), 0));
        positions.put("seated", new Location(world, 20, 64, .5));
        assertEquals(DanceController.Result.FINISHED, dances.tick(seated, 2, 2, 2.4));
        verify(navigator, never()).move(any(), any(), anyDouble(), anyLong(), anyInt());
    }

    @Test void fixedSixtySecondDurationBeginsOnArrivalAndIgnoresOldRandomDurationOptions() {
        YamlConfiguration options = new YamlConfiguration(); options.set("routines.dance-min-seconds", 1); options.set("routines.dance-max-seconds", 1);
        when(plugin.settings()).thenReturn(Settings.parse(options));
        doReturn(RoutineNavigator.Result.WAITING).when(navigator).move(any(), any(), anyDouble(), anyLong(), anyInt());
        assertTrue(dances.start(seated, seated.position(), 0));
        assertEquals(DanceController.Result.MOVING, dances.tick(seated, 200, 2, 2.4));
        doAnswer(call -> {
            ActiveNpc n = call.getArgument(0); positions.put(n.definition().id(), ((Location)call.getArgument(1)).clone());
            return RoutineNavigator.Result.ARRIVED;
        }).when(navigator).move(any(), any(), anyDouble(), anyLong(), anyInt());
        assertEquals(DanceController.Result.DANCING, dances.tick(seated, 300, 2, 2.4));
        assertEquals(DanceController.Result.DANCING, dances.tick(seated, 1498, 2, 2.4));
        assertEquals(DanceController.Result.FINISHED, dances.tick(seated, 1500, 2, 2.4));
    }

    @Test void configuredFixedDurationEndsOnItsExactTick() {
        YamlConfiguration options = new YamlConfiguration(); options.set("routines.dance-duration-seconds", 2);
        when(plugin.settings()).thenReturn(Settings.parse(options)); assertTrue(dances.start(seated, seated.position(), 0));
        assertEquals(DanceController.Result.DANCING, dances.tick(seated, 0, 2, 2.4));
        assertEquals(DanceController.Result.DANCING, dances.tick(seated, 38, 2, 2.4));
        assertEquals(DanceController.Result.FINISHED, dances.tick(seated, 40, 2, 2.4));
    }

    @Test void danceMovesContinuouslyAcrossMoreSpaceWithNoAdditionalPaperQueriesOrHeightChanges() {
        assertTrue(dances.start(seated, seated.position(), 0)); dances.tick(seated, 0, 2, 2.4);
        clearInvocations(navigator); Location previous = seated.position(); double travelled = 0;
        double minX = previous.getX(), maxX = minX, minZ = previous.getZ(), maxZ = minZ;
        for (int tick = 2; tick <= 160; tick += 2) {
            assertEquals(DanceController.Result.DANCING, dances.tick(seated, tick, 2, 2.4));
            Location current = seated.position(); double moved = previous.distance(current);
            assertTrue(moved <= .181, "Steps must stay short and smooth"); assertEquals(64d, current.getY());
            assertTrue(current.distanceSquared(musician.position()) <= 9.001);
            travelled += moved; minX = Math.min(minX, current.getX()); maxX = Math.max(maxX, current.getX());
            minZ = Math.min(minZ, current.getZ()); maxZ = Math.max(maxZ, current.getZ()); previous = current;
        }
        assertTrue(travelled > 6, "A dancer should use its floor instead of one tiny repeated sidestep");
        assertTrue(Math.hypot(maxX - minX, maxZ - minZ) > 2);
        verify(navigator, never()).move(any(), any(), anyDouble(), anyLong(), anyInt());
        verify(world, never()).getPlayers(); verify(world, never()).getNearbyPlayers(any(Location.class), anyDouble());
    }

    @Test void dancersNeverChooseRaisedBlocksAsTheirFloor() {
        for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) obstacles.add(x + ",64," + z);
        assertFalse(dances.start(seated, seated.position(), 0)); verifyNoInteractions(navigator, teleport);
    }

    @Test void flatSegmentRejectsADiagonalHoleAndCannotChangeToTheRaisedNeighbour() {
        DanceFloor floor = new DanceFloor(musician.position(), 64, new DoorController(plugin), 0);
        Location a = new Location(world, 2.6, 64, .4), b = new Location(world, 1.4, 64, 2.6);
        holes.add("2,1"); obstacles.add("2,64,1");
        assertTrue(floor.stand(a)); assertTrue(floor.stand(b));
        assertFalse(floor.segment(a, b)); assertFalse(floor.plannedSegment(a, b));
        assertFalse(floor.stand(new Location(world, 2.5, 65, 1.5)));
    }

    @Test void cachedCandidateGeometryDoesNotRescanBlocksButEveryRealStepChecksFreshSupport() {
        DanceFloor floor = new DanceFloor(musician.position(), 64, new DoorController(plugin), 0);
        Location a = new Location(world, 2.6, 64, .4), b = new Location(world, 1.4, 64, 2.6);
        assertTrue(floor.plannedSegment(a, b)); clearInvocations(world);
        for (int i = 0; i < 50; i++) assertTrue(floor.plannedSegment(a, b));
        verify(world, never()).getBlockAt(anyInt(), anyInt(), anyInt());
        holes.add("2,1"); assertFalse(floor.segment(a, b));
        floor.refresh(40); assertFalse(floor.plannedSegment(a, b));
    }

    @Test void musicOnAPodiumStillKeepsTheDancersWithinThreeActualBlocks() {
        positions.put("musician", new Location(world, .5, 65, .5)); assertTrue(dances.start(seated, seated.position(), 0));
        for (int tick = 0; tick < 100; tick += 2) {
            assertEquals(DanceController.Result.DANCING, dances.tick(seated, tick, 2, 2.4));
            assertEquals(64d, seated.position().getY()); assertTrue(seated.position().distanceSquared(musician.position()) <= 9.001);
        }
    }

    @Test void severalDancersUseSeparatedPositionsAndKeepTheirDistanceWhileMoving() {
        List<ActiveNpc> dancers = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            ActiveNpc n = npc("party_" + i, 7.5); assertTrue(dances.start(n, n.position(), 0));
            assertEquals(DanceController.Result.DANCING, dances.tick(n, 0, 2, 2.4)); dancers.add(n);
        }
        for (int tick = 2; tick <= 120; tick += 2) {
            for (ActiveNpc n : dancers) assertEquals(DanceController.Result.DANCING, dances.tick(n, tick, 2, 2.4));
            for (int a = 0; a < dancers.size(); a++) for (int b = a + 1; b < dancers.size(); b++)
                assertTrue(dancers.get(a).position().distanceSquared(dancers.get(b).position()) >= .639,
                        "Dancers must not all occupy the same one or two blocks");
        }
    }

    @Test void fullFloorLeavesAdditionalPartygoersSeatedAndCancellationReleasesItsPlace() {
        List<ActiveNpc> accepted = new ArrayList<>(); int refused = 0;
        for (int i = 0; i < 50; i++) {
            ActiveNpc n = npc("crowded_" + i, 7.5);
            if (dances.start(n, n.position(), 0)) accepted.add(n); else refused++;
        }
        assertTrue(accepted.size() >= 6); assertTrue(refused > 0);
        for (ActiveNpc n : accepted) dances.cancel(n.definition().id());
        ActiveNpc later = npc("later", 7.5); assertTrue(dances.start(later, later.position(), 2));
    }

    @Test void replacedIdentityAndRemovedGroundFinishSafelyWithoutSnappingHeight() {
        assertTrue(dances.start(seated, seated.position(), 0)); dances.tick(seated, 0, 2, 2.4);
        Location at = seated.position(); holes.add(at.getBlockX() + "," + at.getBlockZ()); clearInvocations(teleport);
        assertEquals(DanceController.Result.FINISHED, dances.tick(seated, 2, 2, 2.4)); verifyNoInteractions(teleport);
        holes.clear(); assertTrue(dances.start(seated, seated.position(), 4));
        ActiveNpc replacement = new ActiveNpc(seated.definition(), seated.anchor(), mock(Villager.class), null);
        assertEquals(DanceController.Result.FINISHED, dances.tick(replacement, 6, 2, 2.4));
        assertNull(dances.returnTo("seated"));
    }

    @Test void musicianMovingWithinTheSameBlockUpdatesTheRealRadiusWithoutSnappingTheDancer() {
        positions.put("seated", new Location(world, 3.3, 64, .5));
        assertTrue(dances.start(seated, seated.anchor(), 0));
        assertEquals(DanceController.Result.DANCING, dances.tick(seated, 0, 2, 2.4));
        positions.put("musician", new Location(world, .1, 64, .5)); clearInvocations(teleport);
        assertEquals(DanceController.Result.FINISHED, dances.tick(seated, 2, 2, 2.4));
        assertEquals(3.3, seated.position().getX()); assertEquals(64d, seated.position().getY());
        verifyNoInteractions(teleport);
    }

    @Test void endingAJumpSettlesOnTheOriginalSafeFloorEvenWhenTheBandMovedAway() {
        positions.put("seated", new Location(world, 3.2, 64, .5));
        when(random.nextInt(4)).thenReturn(1); assertTrue(dances.start(seated, seated.anchor(), 0));
        dances.tick(seated, 0, 2, 2.4); dances.tick(seated, 2, 2, 2.4);
        assertTrue(seated.position().getY() > 64);
        positions.put("musician", new Location(world, .1, 64, .5));
        assertEquals(DanceController.Result.DANCING, dances.tick(seated, 4, 2, 2.4));
        assertEquals(DanceController.Result.FINISHED, dances.tick(seated, 12, 2, 2.4));
        assertEquals(64d, seated.position().getY()); assertEquals(3.2, seated.position().getX());
        verify(seated.entity(), never()).setAI(anyBoolean()); verify(seated.entity(), never()).setGravity(anyBoolean());
    }
}
