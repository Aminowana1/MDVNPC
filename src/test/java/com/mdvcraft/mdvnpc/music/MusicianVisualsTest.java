package com.mdvcraft.mdvnpc.music;

import com.mdvcraft.mdvnpc.MdvNpcPlugin;
import com.mdvcraft.mdvnpc.config.NpcParser;
import com.mdvcraft.mdvnpc.config.Settings;
import com.mdvcraft.mdvnpc.model.NpcDefinition.Mode;
import com.mdvcraft.mdvnpc.runtime.ActiveNpc;
import me.libraryaddict.disguise.disguisetypes.PlayerDisguise;
import me.libraryaddict.disguise.disguisetypes.watchers.PlayerWatcher;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MusicianVisualsTest {
    private MdvNpcPlugin plugin;
    private World world;
    private Player observer;
    private MusicianVisuals visuals;
    private final AtomicInteger instrumentsCreated = new AtomicInteger();

    private record Fixture(ActiveNpc npc, PlayerWatcher watcher, AtomicReference<ItemStack> hand,
                           AtomicBoolean raised, AtomicReference<Location> position,
                           AtomicReference<Float> bodyYaw) {}

    @BeforeEach void setup() {
        MockBukkit.mock();
        plugin = mock(MdvNpcPlugin.class);
        when(plugin.settings()).thenReturn(Settings.parse(new YamlConfiguration()));
        world = mock(World.class);
        when(world.getName()).thenReturn("world");
        observer = player(1);
        visuals = new MusicianVisuals(plugin, mode -> {
            instrumentsCreated.incrementAndGet();
            return new ItemStack(mode == Mode.MUSICIAN_FLUTE ? Material.BAMBOO : Material.IRON_HORSE_ARMOR);
        });
    }

    @AfterEach void cleanup() {
        try { visuals.stop(); } finally { MockBukkit.unmock(); }
    }

    private Fixture npc(String id, String mode) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("npcs." + id + ".location.world", "world");
        yaml.set("npcs." + id + ".mode", mode);
        Villager entity = mock(Villager.class);
        AtomicReference<Location> position = new AtomicReference<>(new Location(world, 0, 64, 0, 30, -3));
        AtomicReference<Float> bodyYaw = new AtomicReference<>(18f);
        when(entity.isValid()).thenReturn(true);
        when(entity.getWorld()).thenReturn(world);
        when(entity.getLocation()).thenAnswer(ignored -> position.get().clone());
        when(entity.getBodyYaw()).thenAnswer(ignored -> bodyYaw.get());
        doAnswer(call -> {
            Location p = position.get().clone();
            p.setYaw(call.getArgument(0)); p.setPitch(call.getArgument(1));
            position.set(p); return null;
        }).when(entity).setRotation(anyFloat(), anyFloat());
        doAnswer(call -> { bodyYaw.set(call.getArgument(0)); return null; })
                .when(entity).setBodyYaw(anyFloat());
        PlayerDisguise disguise = mock(PlayerDisguise.class);
        PlayerWatcher watcher = mock(PlayerWatcher.class);
        when(disguise.getWatcher()).thenReturn(watcher);
        AtomicReference<ItemStack> hand = new AtomicReference<>(new ItemStack(Material.STICK));
        AtomicBoolean raised = new AtomicBoolean();
        when(watcher.getItemInMainHand()).thenAnswer(ignored -> hand.get());
        when(watcher.isMainHandRaised()).thenAnswer(ignored -> raised.get());
        doAnswer(call -> {
            ItemStack item = call.getArgument(0);
            hand.set(item == null ? null : item.clone()); return null;
        }).when(watcher).setItemInMainHand(any());
        doAnswer(call -> { raised.set(call.getArgument(0)); return null; })
                .when(watcher).setMainHandRaised(anyBoolean());
        ActiveNpc npc = new ActiveNpc(NpcParser.parse(yaml).get(id), position.get().clone(), entity, disguise);
        return new Fixture(npc, watcher, hand, raised, position, bodyYaw);
    }

    private Player player(double x) {
        Player player = mock(Player.class);
        when(player.isOnline()).thenReturn(true);
        when(player.getWorld()).thenReturn(world);
        when(player.getLocation()).thenReturn(new Location(world, x, 64, 0));
        return player;
    }

    private void tick(Fixture musician, long clock, boolean note) {
        visuals.tick(musician.npc(), List.of(observer), 14, clock, clock, note);
    }

    @Test void instrumentIsEquippedOnceAndOriginalClonedHandAndPoseAreRestored() {
        Fixture flute = npc("flute", "musician_flute");
        ItemStack original = flute.hand().get();
        flute.raised().set(true);
        for (int clock = 0; clock < 30; clock++) tick(flute, clock, true);
        assertEquals(Material.BAMBOO, flute.hand().get().getType());
        assertTrue(flute.raised().get()); assertTrue(visuals.isAnimating(flute.npc()));
        assertEquals(1, instrumentsCreated.get());
        verify(flute.watcher()).setItemInMainHand(any());
        assertEquals(Material.STICK, original.getType());
        original.setType(Material.DIRT);
        visuals.remove("flute");
        assertEquals(Material.STICK, flute.hand().get().getType(), "Captured equipment must be a clone");
        assertTrue(flute.raised().get()); assertFalse(visuals.isAnimating(flute.npc()));
        assertEquals(30, flute.position().get().getYaw()); assertEquals(-3, flute.position().get().getPitch());
        assertEquals(18f, flute.bodyYaw().get());
        clearInvocations(flute.watcher()); visuals.remove("flute"); verifyNoInteractions(flute.watcher());
    }

    @Test void guitarUsesHorseArmorAndStrumsAtABoundedCadence() {
        Fixture guitar = npc("guitar", "musician_guitar"); guitar.raised().set(true);
        for (int clock = 0; clock <= 32; clock++) tick(guitar, clock, true);
        assertEquals(Material.IRON_HORSE_ARMOR, guitar.hand().get().getType()); assertFalse(guitar.raised().get());
        verify(guitar.npc().entity(), times(3)).swingMainHand();
        verify(guitar.npc().entity(), times(9)).setRotation(anyFloat(), anyFloat());
        visuals.stop(); assertEquals(Material.STICK, guitar.hand().get().getType()); assertTrue(guitar.raised().get());
        assertFalse(visuals.isAnimating(guitar.npc()));
    }

    @Test void fluteHeadMovesSmoothlyAndRestsDoNotGenerateSwingsOrNotes() {
        Fixture flute = npc("flute", "musician_flute"); Player nearby = player(1);
        Location previous = flute.position().get().clone();
        for (int clock = 0; clock < 60; clock++) {
            visuals.tick(flute.npc(), List.of(nearby), 14, clock, clock, false);
            Location current = flute.position().get();
            assertTrue(Math.abs(current.getYaw() - previous.getYaw()) <= 2.501);
            assertTrue(Math.abs(current.getPitch() - previous.getPitch()) <= 2.501);
            assertTrue(Math.abs(current.getYaw() - 30) <= 6.001);
            assertTrue(Math.abs(current.getPitch() + 3) <= 7.001);
            previous = current.clone();
        }
        verify(flute.npc().entity(), times(15)).setRotation(anyFloat(), anyFloat());
        verify(flute.npc().entity(), never()).swingMainHand();
        verify(nearby, never()).spawnParticle(any(Particle.class), any(Location.class), anyInt(), anyDouble(), anyDouble(), anyDouble(), anyDouble());
    }

    @Test void notesAreSingleAscendingParticlesOnlyForTheCachedLocalAudience() {
        Fixture flute = npc("flute", "musician_flute");
        Player near = player(2), boundary = player(14), far = player(14.01), offline = player(1), dead = player(1), otherWorld = player(1);
        when(offline.isOnline()).thenReturn(false); when(dead.isDead()).thenReturn(true);
        when(otherWorld.getWorld()).thenReturn(mock(World.class));
        List<Player> audience = List.of(near, boundary, far, offline, dead, otherWorld);
        for (int clock = 0; clock <= 20; clock++) visuals.tick(flute.npc(), audience, 14, clock, clock, true);
        ArgumentCaptor<Location> hand = ArgumentCaptor.forClass(Location.class);
        ArgumentCaptor<Double> hue = ArgumentCaptor.forClass(Double.class);
        verify(near, times(3)).spawnParticle(eq(Particle.NOTE), hand.capture(), eq(0), hue.capture().doubleValue(), eq(0d), eq(0d), eq(1d));
        verify(boundary, times(3)).spawnParticle(eq(Particle.NOTE), any(Location.class), eq(0), anyDouble(), eq(0d), eq(0d), eq(1d));
        for (Player excluded : List.of(far, offline, dead, otherWorld))
            verify(excluded, never()).spawnParticle(any(Particle.class), any(Location.class), anyInt(), anyDouble(), anyDouble(), anyDouble(), anyDouble());
        assertTrue(hand.getAllValues().stream().allMatch(p -> p.getY() > 65 && p.getWorld() == world));
        assertTrue(hue.getAllValues().stream().allMatch(value -> value >= 0 && value < 1));
        assertEquals(List.of(0d, 2 / 24d, 4 / 24d), hue.getAllValues());
        verify(world, never()).getPlayers();
        verify(world, never()).getNearbyPlayers(any(Location.class), anyDouble());
        verify(plugin, never()).getServer();
    }

    @Test void staleAudienceIsRecheckedAndRadiusCannotBecomeGlobal() {
        Fixture guitar = npc("guitar", "musician_guitar"); Player near = player(2), distant = player(16);
        visuals.tick(guitar.npc(), List.of(near, distant), 1000, 0, 0, true);
        verify(near).spawnParticle(any(Particle.class), any(Location.class), anyInt(), anyDouble(), anyDouble(), anyDouble(), anyDouble());
        verify(distant, never()).spawnParticle(any(Particle.class), any(Location.class), anyInt(), anyDouble(), anyDouble(), anyDouble(), anyDouble());
        when(near.getLocation()).thenReturn(new Location(world, 15.01, 64, 0)); clearInvocations(near);
        visuals.tick(guitar.npc(), List.of(near), 1000, 8, 8, true);
        verify(near, never()).spawnParticle(any(Particle.class), any(Location.class), anyInt(), anyDouble(), anyDouble(), anyDouble(), anyDouble());
    }

    @Test void restorationDoesNotOverwriteAnotherFeaturesEquipmentOrHead() {
        Fixture flute = npc("flute", "musician_flute"); tick(flute, 0, true);
        ItemStack beer = new ItemStack(Material.POTION); flute.hand().set(beer); flute.raised().set(false);
        Location reaction = flute.position().get().clone(); reaction.setYaw(110); reaction.setPitch(20); flute.position().set(reaction);
        flute.bodyYaw().set(70f); clearInvocations(flute.watcher(), flute.npc().entity());
        visuals.remove("flute");
        assertSame(beer, flute.hand().get()); assertFalse(flute.raised().get());
        verify(flute.watcher(), never()).setItemInMainHand(any()); verify(flute.watcher(), never()).setMainHandRaised(anyBoolean());
        verify(flute.npc().entity(), never()).setRotation(anyFloat(), anyFloat());
        verify(flute.npc().entity(), never()).setBodyYaw(anyFloat());
    }

    @Test void independentlyChangedRaisedStateAndBodyYawSurviveRemoval() {
        Fixture flute = npc("flute", "musician_flute"); flute.raised().set(true); tick(flute, 0, true);
        flute.raised().set(false); flute.bodyYaw().set(123f); visuals.remove("flute");
        assertFalse(flute.raised().get()); assertEquals(123f, flute.bodyYaw().get());
        assertEquals(30f, flute.position().get().getYaw()); assertEquals(-3f, flute.position().get().getPitch());
    }

    @Test void anOwnerMutatingTheWatchersItemReferenceCannotCorruptTheRestorationGuard() {
        Fixture flute = npc("flute", "musician_flute");
        doAnswer(call -> { flute.hand().set(call.getArgument(0)); return null; })
                .when(flute.watcher()).setItemInMainHand(any());
        tick(flute, 0, false);
        flute.hand().get().setType(Material.POTION);
        clearInvocations(flute.watcher()); visuals.remove("flute");
        assertEquals(Material.POTION, flute.hand().get().getType());
        verify(flute.watcher(), never()).setItemInMainHand(any());
    }

    @Test void sameIdReplacementRestoresOldIdentityBeforeAnimatingTheNewNpc() {
        Fixture old = npc("same", "musician_flute"); Fixture replacement = npc("same", "musician_guitar");
        tick(old, 0, true); tick(replacement, 1, true);
        assertEquals(Material.STICK, old.hand().get().getType()); assertFalse(old.raised().get());
        assertFalse(visuals.isAnimating(old.npc())); assertTrue(visuals.isAnimating(replacement.npc()));
        assertEquals(Material.IRON_HORSE_ARMOR, replacement.hand().get().getType());
        visuals.stop(); assertEquals(Material.STICK, replacement.hand().get().getType()); assertEquals(2, instrumentsCreated.get());
    }

    @Test void disablingHeadAndParticlesRestoresRotationButKeepsTheInstrument() {
        Fixture flute = npc("flute", "musician_flute"); Player near = player(2); tick(flute, 0, true);
        YamlConfiguration options = new YamlConfiguration(); options.set("music.visuals.head-movement", false); options.set("music.visuals.note-particles", false);
        when(plugin.settings()).thenReturn(Settings.parse(options)); clearInvocations(near);
        visuals.tick(flute.npc(), List.of(near), 14, 8, 8, true);
        assertEquals(30f, flute.position().get().getYaw()); assertEquals(-3f, flute.position().get().getPitch());
        assertEquals(18f, flute.bodyYaw().get()); assertEquals(Material.BAMBOO, flute.hand().get().getType());
        assertFalse(visuals.isAnimating(flute.npc())); verifyNoInteractions(near);
    }

    @Test void settingsAreCachedUntilASettingsReloadAndDisablingEverythingRestoresState() {
        Fixture flute = npc("flute", "musician_flute"); Settings settings = plugin.settings(); tick(flute, 0, false);
        settings.messages().set("music.visuals.enabled", false); tick(flute, 4, false);
        assertTrue(visuals.isAnimating(flute.npc()), "The same Settings instance must keep the cached flags");
        when(plugin.settings()).thenReturn(Settings.parse(settings.messages())); tick(flute, 8, false);
        assertFalse(visuals.isAnimating(flute.npc())); assertEquals(Material.STICK, flute.hand().get().getType());
        assertEquals(30f, flute.position().get().getYaw()); assertEquals(-3f, flute.position().get().getPitch());
    }

    @Test void invalidNormalAndUndisguisedNpcsDoNotCreateInstruments() {
        Fixture normal = npc("normal", "normal"), invalid = npc("invalid", "musician_flute"), bare = npc("bare", "musician_guitar");
        when(invalid.npc().entity().isValid()).thenReturn(false);
        tick(normal, 0, true); tick(invalid, 0, true);
        ActiveNpc undisguised = new ActiveNpc(bare.npc().definition(), bare.npc().anchor(), bare.npc().entity(), null);
        visuals.tick(undisguised, List.of(), 14, 0, 0, true);
        assertEquals(0, instrumentsCreated.get()); assertFalse(visuals.isAnimating(undisguised));
        verify(normal.watcher(), never()).setItemInMainHand(any()); verify(invalid.watcher(), never()).setItemInMainHand(any());
    }

    @Test void invalidRadiusOrNoAudienceProducesNoParticlePackets() {
        Fixture flute = npc("flute", "musician_flute"); Player near = player(1);
        visuals.tick(flute.npc(), List.of(near), Double.NaN, 0, 0, true);
        visuals.tick(flute.npc(), List.of(near), 0, 8, 8, true);
        visuals.tick(flute.npc(), null, 14, 16, 16, true);
        verifyNoInteractions(near);
    }

    @Test void emptyOrStaleAudienceSkipsAllRepeatedGesturesUntilAListenerArrives() {
        Fixture guitar = npc("guitar", "musician_guitar"); Player stale = player(15);
        for (int clock = 0; clock < 20; clock++)
            visuals.tick(guitar.npc(), clock < 10 ? List.of() : List.of(stale), 14, clock, clock, true);
        assertEquals(Material.IRON_HORSE_ARMOR, guitar.hand().get().getType());
        verify(guitar.npc().entity(), never()).setRotation(anyFloat(), anyFloat());
        verify(guitar.npc().entity(), never()).setBodyYaw(anyFloat());
        verify(guitar.npc().entity(), never()).swingMainHand();
        tick(guitar, 20, true);
        verify(guitar.npc().entity()).setRotation(anyFloat(), anyFloat());
        verify(guitar.npc().entity()).swingMainHand();
        assertEquals(1, instrumentsCreated.get());
    }

    @Test void failedEquipmentRestorationStillRestoresTheHeadAndOtherMusicians() {
        Fixture flute = npc("flute", "musician_flute"), guitar = npc("guitar", "musician_guitar");
        tick(flute, 0, true); tick(guitar, 0, true);
        doThrow(new IllegalStateException("failed watcher packet")).when(flute.watcher()).setItemInMainHand(any());
        assertThrows(IllegalStateException.class, visuals::stop);
        assertFalse(visuals.isAnimating(flute.npc())); assertFalse(visuals.isAnimating(guitar.npc()));
        assertEquals(30f, flute.position().get().getYaw()); assertEquals(-3f, flute.position().get().getPitch());
        assertEquals(18f, flute.bodyYaw().get()); assertEquals(Material.STICK, guitar.hand().get().getType());
        assertEquals(30f, guitar.position().get().getYaw()); assertEquals(-3f, guitar.position().get().getPitch());
        assertDoesNotThrow(visuals::stop);
    }

    @Test void shutdownAccumulatesFailuresWhileRemovingEveryState() {
        Fixture flute = npc("flute", "musician_flute"), guitar = npc("guitar", "musician_guitar");
        tick(flute, 0, true); tick(guitar, 0, true);
        doThrow(new IllegalStateException("flute packet")).when(flute.watcher()).setItemInMainHand(any());
        doThrow(new IllegalStateException("guitar packet")).when(guitar.watcher()).setItemInMainHand(any());
        IllegalStateException failure = assertThrows(IllegalStateException.class, visuals::stop);
        assertEquals(1, failure.getSuppressed().length);
        assertFalse(visuals.isAnimating(flute.npc())); assertFalse(visuals.isAnimating(guitar.npc()));
        assertEquals(30f, flute.position().get().getYaw()); assertEquals(30f, guitar.position().get().getYaw());
    }
}
