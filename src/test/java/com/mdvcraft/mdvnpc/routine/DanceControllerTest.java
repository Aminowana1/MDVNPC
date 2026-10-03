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
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(invocation -> {
            int x = invocation.getArgument(0), y = invocation.getArgument(1), z = invocation.getArgument(2);
            return block(x, y, z, y == 63 || ceiling && y == 66);
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
        assertEquals(DanceController.Result.FINISHED, dances.tick(seated, 400, 2, 2.4));
        assertNull(dances.returnTo("seated"));
        assertTrue(dances.start(seated, seated.position(), 402));
        clearInvocations(navigator);
        dances.clear();
        verify(navigator).cancel("seated");
        assertEquals(DanceController.Result.FINISHED, dances.tick(seated, 404, 2, 2.4));
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
}
